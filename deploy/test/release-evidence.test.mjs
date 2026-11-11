import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Zip from 'adm-zip';
import YAML from 'yaml';
import { descriptor, loadMta, root, verifyArchive } from '../scripts/identity-smoke.mjs';
import { hashes, sourceHash } from '../scripts/release-evidence.mjs';
import { normalizePermissions } from '../scripts/portable-zip.mjs';

// Syntetyczny ZIP bez wykonywalnego kodu; służy wyłącznie do wykrywania podmiany zawartości MTAR.
const BOOT_MANIFEST = 'Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\n'
    + 'Start-Class: customer.cap_java.Application\r\n\r\n';

function artifact(file, mutate = () => {}, srvPayload = jar => jar) {
    const manifest = { 'sap.ui5': { config: {
        ksefCockpit: { dataMode: 'rap', capabilities: { sendInvoice: false },
            externalServices: { ai: false, scanner: false, voice: false, tts: false } },
        ksefInbox: { dataMode: 'disabled', mock: null, cap: { entitySet: null }, capabilities: { syncInbound: false } }
    } } };
    const jar = new Zip();
    jar.addFile('META-INF/MANIFEST.MF', Buffer.from(BOOT_MANIFEST));
    jar.addFile('BOOT-INF/classes/application.yaml', Buffer.from('synthetic'));
    const router = new Zip();
    router.addFile('xs-app.json', readFileSync(join(root, 'approuter/xs-app.json')));
    mutate({ manifest, jar, router });
    router.addFile('resources/manifest.json', Buffer.from(JSON.stringify(manifest)));
    const zip = new Zip();
    zip.addFile('META-INF/MANIFEST.MF', Buffer.from('Manifest-Version: 1.0\n'));
    zip.addFile('META-INF/mtad.yaml', Buffer.from(YAML.stringify(descriptor(loadMta()))));
    // Payload Java = bajty wykonywalnego JAR (układ przyjmowany przez SAP Java Buildpack).
    zip.addFile('ainvoicer-srv/data.zip', normalizePermissions(srvPayload(normalizePermissions(jar))).toBuffer());
    zip.addFile('ainvoicer-approuter/data.zip', normalizePermissions(router).toBuffer());
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
            ({ manifest }) => { manifest['sap.ui5'].config.ksefInbox.mock = { path: 'fixture.json' }; },
            ({ manifest }) => { manifest['sap.ui5'].config.ksefInbox.cap.entitySet = 'InboundInvoiceOverview'; },
            ({ router }) => router.updateFile('xs-app.json', Buffer.from('{"routes":[]}')),
            ({ jar }) => jar.addFile('BOOT-INF/lib/java-security-test.jar', Buffer.from('synthetic')),
            ({ jar }) => jar.addFile('BOOT-INF/classes/default-env.json', Buffer.from('{}'))
        ]) { artifact(file, mutate); assert.throws(() => verifyArchive(file)); }
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('CF Java payload must be the exploded Spring Boot JAR (regression 40b8e964 BuildpackCompileFailed)', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ainv-java-layout-'));
    try {
        const file = join(dir, 'synthetic.mtar');
        artifact(file); verifyArchive(file);
        const payload = new Zip(new Zip(file).readFile('ainvoicer-srv/data.zip'));
        assert.ok(payload.getEntry('META-INF/MANIFEST.MF'));
        for (const [mutate, wrap] of [
            // Dokładny układ wadliwego 0.7.3/196b9b8c…: ZIP zawierający wyłącznie cap-java-exec.jar.
            [undefined, jar => { const w = new Zip(); w.addFile('cap-java-exec.jar', jar.toBuffer()); return w; }],
            [({ jar }) => jar.deleteFile('META-INF/MANIFEST.MF'), undefined],
            [({ jar }) => jar.updateFile('META-INF/MANIFEST.MF', Buffer.from('Manifest-Version: 1.0\r\nMain-Class: x.Main\r\n')), undefined],
            [({ jar }) => jar.addFile('nested.war', Buffer.from('synthetic')), undefined]
        ]) {
            artifact(file, mutate, wrap);
            assert.throws(() => verifyArchive(file), /CF Java payload|Main-Class|Start-Class|MANIFEST/);
        }
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('source evidence covers real file bytes and ordered filenames', () => {
    const a = sourceHash(() => 'mta.yaml\0deploy/package.json\0');
    assert.equal(a, sourceHash(() => 'deploy/package.json\0mta.yaml\0'));
    assert.notEqual(a, sourceHash(() => 'mta.yaml\0'));
});
