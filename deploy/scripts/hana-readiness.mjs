// INT-03: generowany wariant istniejącego HDI. Budowanie i walidacja bez jakichkolwiek wywołań CF.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync, execFileSync } from 'node:child_process';
import YAML from 'yaml';
import Zip from 'adm-zip';
import { cfModuleType, descriptor as smokeDescriptor, loadMta, root, smokeEnv, verifyArchive as verifySmoke } from './identity-smoke.mjs';
import { hashes, sha256, sourceHash } from './release-evidence.mjs';

export const hanaEnv = Object.freeze({
    ...Object.fromEntries(Object.entries(smokeEnv).filter(([key]) => key !== 'SPRING_SQL_INIT_PLATFORM')),
    AINV_DEPLOYMENT_IDENTITY_SMOKE: 'false', AINV_DEPLOYMENT_HANA_READINESS: 'true',
    AINV_KSEF_STORAGE_PROVIDER: 'off', SPRING_SQL_INIT_MODE: 'never',
    SPRING_APPLICATION_JSON: JSON.stringify({ cds: { outbox: { persistent: { enabled: false }, inMemory: { enabled: false } } } })
});

/** Jedno źródło MTA + walidowany wariant smoke; brak kopiowania deskryptora do utrzymania. */
export function descriptor(source = loadMta()) {
    const mta = smokeDescriptor(source);
    mta.version = '0.7.3';
    mta.description = 'AInvoicer HANA readiness: existing HDI/XSUAA/Destination, external effects OFF';
    const srv = mta.modules.find(m => m.name === 'ainvoicer-srv');
    srv.properties = { ...Object.fromEntries(Object.entries(srv.properties).filter(([key]) => !(key in smokeEnv))), ...hanaEnv };
    srv.requires.push({ name: 'ainvoicer-db' });
    srv['deployed-after'] = ['ainvoicer-db-deployer'];
    mta.modules.find(m => m.name === 'ainvoicer-approuter')['deployed-after'] = ['ainvoicer-srv'];
    const db = structuredClone(source.modules.find(m => m.name === 'ainvoicer-db-deployer'));
    db.type = cfModuleType(db.type);
    delete db['build-parameters'];
    db.path = 'ainvoicer-db-deployer/data.zip';
    db.parameters['no-route'] = true;
    mta.modules.push(db);
    mta.resources.push({ name: 'ainvoicer-db', type: 'org.cloudfoundry.existing-service', parameters: { 'service-name': 'ainvoicer-db' } });
    return mta;
}

/** Nie akceptuje dodatkowych usług, flag, tras ani automatycznego tworzenia HDI/HANA. */
export function validate(mta) { assert.deepEqual(mta, descriptor()); return mta; }

export function verifyArchive(file) {
    const zip = new Zip(file);
    validate(YAML.parse(zip.readAsText('META-INF/mtad.yaml')));
    assert.deepEqual(zip.getEntries().filter(e => !e.isDirectory).map(e => e.entryName).sort(), [
        'META-INF/MANIFEST.MF', 'META-INF/mtad.yaml', 'ainvoicer-approuter/data.zip',
        'ainvoicer-db-deployer/data.zip', 'ainvoicer-srv/data.zip'
    ]);
    const db = new Zip(zip.readFile('ainvoicer-db-deployer/data.zip'));
    const files = db.getEntries().filter(e => !e.isDirectory).map(e => e.entryName);
    assert.ok(files.includes('src/gen/.hdiconfig') && files.includes('src/gen/cds.outbox.Messages.hdbtable'));
    assert.ok(files.includes('package-lock.json'));
    for (const name of files) assert.ok(/^(package(-lock)?\.json|src\/gen\/[^/]+\.(hdbtable|hdbview|hdbindex)|src\/gen\/\.hdi(config|namespace))$/.test(name), name);
    assert.ok(!files.some(f => /AinvoicerH01|ZAINV|undeploy|\.csv|\.hdbtabledata/i.test(f)));
    const pkg = JSON.parse(db.readAsText('package.json'));
    assert.equal(pkg.scripts.start, JSON.parse(readFileSync(join(root, 'cap-java/db/package.json'), 'utf8')).scripts.start);
    assert.ok(!JSON.stringify(pkg).includes('auto-undeploy'));
    // Wspólna weryfikacja payloadów JAR/UI5/approuter; projekcja archiwum wyłącznie w pamięci.
    zip.deleteFile('ainvoicer-db-deployer/data.zip');
    zip.updateFile('META-INF/mtad.yaml', Buffer.from(YAML.stringify(smokeDescriptor(loadMta()))));
    verifySmoke(zip.toBuffer());
    return { tables: files.filter(f => f.endsWith('.hdbtable')).length,
        indexes: files.filter(f => f.endsWith('.hdbindex')).length, views: files.filter(f => f.endsWith('.hdbview')).length };
}

function run(command, args, cwd = root) {
    assert.equal(spawnSync(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' && command === 'npm' }).status, 0);
}

function main() {
    const out = join(root, 'deploy/build'); mkdirSync(out, { recursive: true });
    const artifact = join(out, 'ainvoicer_0.7.3.mtar');
    const evidenceFile = join(out, 'hana-release-evidence.json');
    const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
    if (process.argv[2] === 'validate') { validate(descriptor()); console.log('HANA descriptor PASS; all services must already exist'); return; }
    if (process.argv[2] === 'verify') {
        verifyArchive(artifact);
        const evidence = JSON.parse(readFileSync(evidenceFile, 'utf8'));
        assert.deepEqual(evidence.sha256, { ...hashes(artifact), hdiPayload: sha256(new Zip(artifact).readFile('ainvoicer-db-deployer/data.zip')) });
        assert.equal(evidence.sourceFilesSha256, sourceHash(git));
        console.log('Undeployed HANA release evidence PASS'); return;
    }
    assert.equal(process.argv[2], 'build');
    // Najpierw zweryfikowany smoke MTAR z tego samego checkoutu (osobny pełny build).
    run('npm', ['run', 'verify:release'], join(root, 'deploy'));
    const inputs = sourceHash(git);
    run('npm', ['run', 'build'], join(root, 'cap-java/db'));
    const stage = mkdtempSync(join(tmpdir(), 'ainv-hana-'));
    try {
        const zip = new Zip(join(out, 'ainvoicer_0.7.2.mtar'));
        const assembly = join(stage, 'assembly'); mkdirSync(assembly);
        writeFileSync(join(assembly, 'mtad.yaml'), YAML.stringify(descriptor()));
        for (const name of ['ainvoicer-srv', 'ainvoicer-approuter']) {
            mkdirSync(join(assembly, name)); writeFileSync(join(assembly, name, 'data.zip'), zip.readFile(name + '/data.zip'));
        }
        const db = new Zip();
        for (const name of ['package.json', 'package-lock.json']) db.addLocalFile(join(root, 'cap-java/db', name));
        db.addLocalFolder(join(root, 'cap-java/db/src/gen'), 'src/gen');
        mkdirSync(join(assembly, 'ainvoicer-db-deployer')); db.writeZip(join(assembly, 'ainvoicer-db-deployer/data.zip'));
        rmSync(artifact, { force: true });
        run(process.execPath, [join(root, 'deploy/node_modules/mbt/bin/mbt'), 'assemble', '-s', assembly, '-t', out], assembly);
        const schema = verifyArchive(artifact);
        assert.equal(inputs, sourceHash(git), 'source changed during HANA build');
        writeFileSync(join(out, 'hana-readiness.mtad.yaml'), YAML.stringify(descriptor()));
        writeFileSync(evidenceFile, JSON.stringify({ kind: 'UNDEPLOYED_HANA_READINESS', sourceCommit: git(['rev-parse', 'HEAD']),
            sourceFilesSha256: inputs, workingTreeDirty: !!git(['status', '--porcelain']), at: new Date().toISOString(), schema,
            sha256: { ...hashes(artifact), hdiPayload: sha256(new Zip(artifact).readFile('ainvoicer-db-deployer/data.zip')) },
            deploymentApproved: false, liveHanaCap: 'NOT EXECUTED', hdiCreation: 'NOT EXECUTED' }, null, 2) + '\n');
        console.log('HANA MTAR validated OFFLINE: ' + artifact);
    } catch (error) { rmSync(artifact, { force: true }); throw error; }
    finally { rmSync(stage, { recursive: true, force: true }); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
