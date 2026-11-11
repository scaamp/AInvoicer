import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Zip from 'adm-zip';
import YAML from 'yaml';
import { descriptor, loadMta, root, verifyArchive } from '../scripts/identity-smoke.mjs';
import { hashes, sourceHash } from '../scripts/release-evidence.mjs';

// Syntetyczny ZIP bez wykonywalnego kodu; służy wyłącznie do wykrywania podmiany zawartości MTAR.
function artifact(file, mutate = () => {}) {
    const manifest = { 'sap.ui5': { config: {
        ksefCockpit: { dataMode: 'rap', capabilities: { sendInvoice: false },
            externalServices: { ai: false, scanner: false, voice: false, tts: false } },
        ksefInbox: { dataMode: 'disabled', capabilities: { syncInbound: false } }
    } } };
    const jar = new Zip(); jar.addFile('BOOT-INF/classes/application.yaml', Buffer.from('synthetic'));
    const router = new Zip();
    router.addFile('xs-app.json', readFileSync(join(root, 'approuter/xs-app.json')));
    mutate({ manifest, jar, router });
    router.addFile('resources/manifest.json', Buffer.from(JSON.stringify(manifest)));
    const srv = new Zip(); srv.addFile('cap-java-exec.jar', jar.toBuffer());
    const zip = new Zip();
    zip.addFile('META-INF/MANIFEST.MF', Buffer.from('Manifest-Version: 1.0\n'));
    zip.addFile('META-INF/mtad.yaml', Buffer.from(YAML.stringify(descriptor(loadMta()))));
    zip.addFile('ainvoicer-srv/data.zip', srv.toBuffer());
    zip.addFile('ainvoicer-approuter/data.zip', router.toBuffer());
    zip.writeZip(file);
}

test('release hashes include executable payload, routes and descriptor; payload change invalidates digest', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ainv-evidence-test-'));
    try {
        const file = join(dir, 'synthetic.mtar');
        artifact(file); verifyArchive(file);
        const first = hashes(file);
        assert.ok(Object.values(first).every(h => /^[a-f\d]{64}$/.test(h)));
        const archive = new Zip(file);
        archive.updateFile('META-INF/mtad.yaml', Buffer.from('changed'));
        archive.writeZip(file);
        const second = hashes(file);
        assert.notEqual(first.mtar, second.mtar);
        assert.notEqual(first.descriptor, second.descriptor);
        assert.equal(first.capJar, second.capJar);
        assert.throws(() => verifyArchive(file));
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('archive rejects SEND, Inbox opt-in, changed routes and test identity in runtime JAR', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ainv-evidence-negative-'));
    try {
        const file = join(dir, 'synthetic.mtar');
        for (const mutate of [
            ({ manifest }) => { manifest['sap.ui5'].config.ksefCockpit.capabilities.sendInvoice = true; },
            ({ manifest }) => { manifest['sap.ui5'].config.ksefInbox.dataMode = 'cap'; },
            ({ router }) => router.updateFile('xs-app.json', Buffer.from('{"routes":[]}')),
            ({ jar }) => jar.addFile('BOOT-INF/lib/java-security-test.jar', Buffer.from('synthetic')),
            ({ jar }) => jar.addFile('BOOT-INF/classes/default-env.json', Buffer.from('{}'))
        ]) { artifact(file, mutate); assert.throws(() => verifyArchive(file)); }
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('source evidence covers real file bytes and ordered filenames', () => {
    const a = sourceHash(() => 'mta.yaml\0deploy/package.json\0');
    assert.equal(a, sourceHash(() => 'deploy/package.json\0mta.yaml\0'));
    assert.notEqual(a, sourceHash(() => 'mta.yaml\0'));
});
