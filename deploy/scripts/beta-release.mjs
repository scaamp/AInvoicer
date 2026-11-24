// BETA-02: opt-in, kompletny MTAR „Beta 0.1” (srv + AppRouter z UI5 Inbox Beta + deployer HDI). Bez CF/HANA/KSeF.
//
// Zasady (nie zmienia `mta.yaml`, `build:btp:smoke` ani `build:btp:hana`):
//  * baza = zweryfikowany kandydat HANA `ainvoicer_0.7.4.mtar` (`npm run build:btp:smoke && npm run build:btp:hana`);
//  * payload `ainvoicer-srv` i `ainvoicer-db-deployer` kopiowane BAJT W BAJT z 0.7.4 (ten sam JAR, ten sam HDI +1/0/0);
//  * przebudowany wyłącznie payload AppRoutera: te same package*.json/xs-app.json, `resources/` = `dist-cf-inbox-beta`
//    zbudowane i zweryfikowane regułami `build-cf.mjs` (Inbox read-only CAP; viewXml tylko z `--with-xml-download`);
//  * deskryptor = deskryptor HANA 0.7.4 ze zmienioną wyłącznie etykietą wersji/opisem: te same usługi existing-service,
//    te same flagi srv (KSeF/INBOUND/SEND/SAP WRITE/storage/xml-download OFF, guard readiness aktywny);
//  * walidacja po `mbt assemble` na gotowym pliku; dowód z SHA-256. Artefakt nie jest później modyfikowany.
//
//   node deploy/scripts/beta-release.mjs build [--with-xml-download]
//   node deploy/scripts/beta-release.mjs verify [--with-xml-download]
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync, execFileSync } from 'node:child_process';
import YAML from 'yaml';
import Zip from 'adm-zip';
import { normalizePermissions, verifyPermissions } from './portable-zip.mjs';
import { loadMta, root } from './identity-smoke.mjs';
import { descriptor as hanaDescriptor, verifyArchive as verifyHanaArchive } from './hana-readiness.mjs';
import { verifyRelease } from './stor04-release.mjs';
import { sha256, sourceHash } from './release-evidence.mjs';

export const BASE = join(root, 'deploy/build/ainvoicer_0.7.4.mtar');
export const VARIANTS = Object.freeze({
    'inbox-beta': Object.freeze({ version: '0.7.5', xmlDownload: false, dir: 'beta-inbox' }),
    'inbox-beta-xml': Object.freeze({ version: '0.7.6', xmlDownload: true, dir: 'beta-inbox-xml' }),
});
const ROUTER_FILES = ['package.json', 'package-lock.json', 'xs-app.json'];

export function variantOf(argv) {
    const extra = argv.filter(a => a !== '--with-xml-download');
    assert.deepEqual(extra, [], 'unknown arguments: ' + extra.join(' '));
    return argv.includes('--with-xml-download') ? 'inbox-beta-xml' : 'inbox-beta';
}

/** Deskryptor HANA 0.7.4 bez jakiejkolwiek zmiany modułów/usług/flag — inna jest wyłącznie etykieta i opis. */
export function descriptor(variant, source = loadMta()) {
    const settings = VARIANTS[variant];
    assert.ok(settings, 'unknown beta variant');
    const mta = hanaDescriptor(source);
    mta.version = settings.version;
    mta.description = 'AInvoicer Beta 0.1 candidate: UI5 KSeF Inbox read-only over CAP'
        + (settings.xmlDownload ? ' + XML download button (backend flag OFF until approved)' : '')
        + '; srv/HDI identical to 0.7.4; external effects OFF';
    return mta;
}

export const artifactPath = variant => join(root, 'deploy/build', VARIANTS[variant].dir, `ainvoicer_${VARIANTS[variant].version}.mtar`);
const evidencePath = variant => join(root, 'deploy/build', VARIANTS[variant].dir, 'beta-release-evidence.json');

/** Pełna weryfikacja gotowego archiwum względem bazy 0.7.4 i reguł UI5; zwraca raport bez treści plików. */
export async function verifyArchive(file, variant, base = BASE) {
    const settings = VARIANTS[variant];
    const archive = new Zip(file);
    const baseArchive = new Zip(base);
    assert.deepEqual(archive.getEntries().filter(e => !e.isDirectory).map(e => e.entryName).sort(), [
        'META-INF/MANIFEST.MF', 'META-INF/mtad.yaml', 'ainvoicer-approuter/data.zip',
        'ainvoicer-db-deployer/data.zip', 'ainvoicer-srv/data.zip'
    ]);
    assert.deepEqual(YAML.parse(archive.readAsText('META-INF/mtad.yaml')), descriptor(variant), 'descriptor drift');
    // srv i HDI: bajty identyczne z zatwierdzonym kandydatem 0.7.4 (ten sam JAR, ten sam payload +1/0/0).
    for (const name of ['ainvoicer-srv/data.zip', 'ainvoicer-db-deployer/data.zip']) {
        assert.equal(sha256(archive.readFile(name)), sha256(baseArchive.readFile(name)), name + ' differs from 0.7.4');
    }
    const stor04 = verifyRelease(readFileSync(file));
    // AppRouter: pliki poza resources/ identyczne z 0.7.4; resources/ = artefakt beta zgodny z regułami build-cf.
    const router = new Zip(archive.readFile('ainvoicer-approuter/data.zip'));
    const baseRouter = new Zip(baseArchive.readFile('ainvoicer-approuter/data.zip'));
    verifyPermissions(router);
    const routerFiles = router.getEntries().filter(e => !e.isDirectory).map(e => e.entryName);
    assert.deepEqual(routerFiles.filter(n => !n.startsWith('resources/')).sort(), [...ROUTER_FILES].sort());
    for (const name of ROUTER_FILES) {
        assert.equal(sha256(router.readFile(name)), sha256(baseRouter.readFile(name)), 'approuter ' + name + ' differs from 0.7.4');
    }
    assert.deepEqual(JSON.parse(router.readAsText('xs-app.json')),
        JSON.parse(readFileSync(join(root, 'approuter/xs-app.json'), 'utf8')), 'routes must match reviewed source');
    assert.ok(!routerFiles.some(n => /(^|\/)(node_modules|test|localService|\.env|default-env|default-services)(\/|$)/i.test(n)));
    const stage = mkdtempSync(join(tmpdir(), 'ainv-beta-verify-'));
    try {
        router.extractAllTo(stage, true);
        const { verifyArtifact } = await import(pathToFileURL(join(root, 'sapui5/ainvoicer/scripts/build-cf.mjs')).href);
        const report = verifyArtifact(join(stage, 'resources'), { variant: 'inbox-beta', xmlDownload: settings.xmlDownload });
        assert.deepEqual(report.errors, [], 'UI5 beta artifact rules');
        const inbox = JSON.parse(readFileSync(join(stage, 'resources/manifest.json'), 'utf8'))['sap.ui5'].config.ksefInbox;
        assert.equal(inbox.dataMode, 'cap');
        assert.equal(inbox.capabilities.viewXml, settings.xmlDownload);
    } finally { rmSync(stage, { recursive: true, force: true }); }
    return {
        variant, mtaVersion: settings.version, mtarSha256: sha256(readFileSync(file)),
        payloadSha256: Object.fromEntries(['ainvoicer-srv/data.zip', 'ainvoicer-db-deployer/data.zip', 'ainvoicer-approuter/data.zip']
            .map(n => [n, sha256(archive.readFile(n))])),
        baseMtarSha256: sha256(readFileSync(base)), srvAndHdiIdenticalToBase: true,
        hdi: { added: stor04.hdi.added, changed: stor04.hdi.changed, removed: stor04.hdi.removed },
        viewXml: settings.xmlDownload, deploymentApproved: false, liveDeploy: 'NOT EXECUTED',
    };
}

function run(command, args, cwd = root) {
    const result = spawnSync(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' && command === 'npm' });
    assert.equal(result.status, 0, command + ' ' + args.join(' '));
}

async function main() {
    const [action, ...rest] = process.argv.slice(2);
    const variant = variantOf(rest);
    const artifact = artifactPath(variant);
    const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
    if (action === 'verify') {
        const report = await verifyArchive(artifact, variant);
        const evidence = JSON.parse(readFileSync(evidencePath(variant), 'utf8'));
        assert.equal(evidence.mtarSha256, report.mtarSha256, 'artifact changed after validation');
        console.log(JSON.stringify(report, null, 2));
        console.log('BETA release evidence PASS (' + variant + ')');
        return;
    }
    assert.equal(action, 'build', 'usage: beta-release.mjs build|verify [--with-xml-download]');
    // Baza musi być zbudowanym i zweryfikowanym kandydatem HANA tego samego checkoutu.
    assert.ok(existsSync(BASE), 'missing 0.7.4 candidate: run npm run build:btp:smoke && npm run build:btp:hana first');
    run('npm', ['run', 'verify:hana'], join(root, 'deploy'));
    verifyHanaArchive(BASE);
    const inputs = sourceHash(git);
    const ui = join(root, 'sapui5/ainvoicer');
    run('npm', ['run', 'build:cf:inbox-beta', ...(VARIANTS[variant].xmlDownload ? ['--', '--with-xml-download'] : [])], ui);
    const out = join(root, 'deploy/build', VARIANTS[variant].dir);
    mkdirSync(out, { recursive: true });
    const stage = mkdtempSync(join(tmpdir(), 'ainv-beta-'));
    try {
        const base = new Zip(BASE);
        const assembly = join(stage, 'assembly'); mkdirSync(assembly);
        writeFileSync(join(assembly, 'mtad.yaml'), YAML.stringify(descriptor(variant)));
        for (const name of ['ainvoicer-srv', 'ainvoicer-db-deployer']) {
            mkdirSync(join(assembly, name));
            writeFileSync(join(assembly, name, 'data.zip'), base.readFile(name + '/data.zip'));
        }
        const router = join(stage, 'modules/ainvoicer-approuter'); mkdirSync(router, { recursive: true });
        const baseRouter = new Zip(base.readFile('ainvoicer-approuter/data.zip'));
        for (const name of ROUTER_FILES) writeFileSync(join(router, name), baseRouter.readFile(name));
        cpSync(join(ui, 'dist-cf-inbox-beta'), join(router, 'resources'), { recursive: true });
        mkdirSync(join(assembly, 'ainvoicer-approuter'));
        const routerZip = new Zip(); routerZip.addLocalFolder(router);
        normalizePermissions(routerZip).writeZip(join(assembly, 'ainvoicer-approuter/data.zip'));
        rmSync(artifact, { force: true });
        run(process.execPath, [join(root, 'deploy/node_modules/mbt/bin/mbt'), 'assemble', '-s', assembly, '-t', out], assembly);
        const report = await verifyArchive(artifact, variant);
        assert.equal(inputs, sourceHash(git), 'source changed during beta build');
        writeFileSync(evidencePath(variant), JSON.stringify({ kind: 'UNDEPLOYED_BETA_RELEASE', sourceCommit: git(['rev-parse', 'HEAD']),
            sourceFilesSha256: inputs, workingTreeDirty: !!git(['status', '--porcelain']), at: new Date().toISOString(), ...report },
            null, 2) + '\n');
        console.log('BETA MTAR validated OFFLINE (' + variant + '): ' + artifact + '\nsha256=' + report.mtarSha256);
    } catch (error) { rmSync(artifact, { force: true }); throw error; }
    finally { rmSync(stage, { recursive: true, force: true }); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    main().catch(error => { console.error('BETA release FAIL: ' + error.message); process.exitCode = 1; });
}
