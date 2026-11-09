// CAP-07C: deskryptor deploy generowany z MTA, bez HANA i bez wywołań CF.
// Pakujemy wyłącznie jawnie wybrane artefakty; lokalne bindingi nie są kopiowane.
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import YAML from 'yaml';
import Zip from 'adm-zip';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const smokeEnv = Object.freeze({
    SPRING_PROFILES_ACTIVE: 'cf', AINV_DEPLOYMENT_IDENTITY_SMOKE: 'true',
    AINV_KSEF_ENABLED: 'false', AINV_SAP_WRITE_ENABLED: 'false',
    AINV_SAP_WRITE_SWEEPER_ENABLED: 'false', SPRING_SQL_INIT_PLATFORM: 'h2'
});
const jreEnv = {
    JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']",
    JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }'
};
export const loadMta = () => YAML.parse(readFileSync(join(root, 'mta.yaml'), 'utf8'));

/** Transformacja strukturalna; whitelist zapobiega dziedziczeniu nowych sekretów/flag z pełnego MTA. */
export function descriptor(source) {
    const mta = structuredClone(source);
    assert.equal(mta.ID, 'ainvoicer');
    assert.deepEqual(mta.modules.map(m => m.name).sort(),
        ['ainvoicer-approuter', 'ainvoicer-db-deployer', 'ainvoicer-srv']);
    assert.deepEqual(mta.resources.map(r => r.name).sort(),
        ['ainvoicer-auth', 'ainvoicer-db', 'ainvoicer-destination']);
    const srv = mta.modules.find(m => m.name === 'ainvoicer-srv');
    const router = mta.modules.find(m => m.name === 'ainvoicer-approuter');
    assert.deepEqual(srv.properties, { ...jreEnv, SPRING_PROFILES_ACTIVE: 'cf',
        AINV_KSEF_ENABLED: 'false', AINV_SAP_WRITE_ENABLED: 'false' }, 'review new source env before packaging');
    delete mta['build-parameters'];
    mta.version = '0.7.2';
    mta.description = 'AInvoicer identity-smoke: read-only, ephemeral H2, existing XSUAA/Destination';
    mta.modules = [srv, router];
    for (const module of mta.modules) {
        delete module['build-parameters'];
        module.path = module.name + '/data.zip';
        module.requires = module.requires.filter(r => r.name !== 'ainvoicer-db');
    }
    srv.properties = { ...jreEnv, ...smokeEnv };
    mta.resources = mta.resources.filter(r => r.name !== 'ainvoicer-db').map(r => ({
        name: r.name, type: 'org.cloudfoundry.existing-service', parameters: { 'service-name': r.name }
    }));
    validate(mta);
    return mta;
}

/** Wąski schemat wariantu i niezmienniki bezpieczeństwa, także dla mtad odczytanego z MTAR. */
export function validate(mta) {
    assert.equal(mta['_schema-version'], '3.1');
    assert.equal(mta.ID, 'ainvoicer');
    assert.equal(mta.version, '0.7.2');
    assert.deepEqual(mta.modules.map(m => m.name).sort(), ['ainvoicer-approuter', 'ainvoicer-srv']);
    assert.deepEqual(mta.resources.map(r => r.name).sort(), ['ainvoicer-auth', 'ainvoicer-destination']);
    assert.equal(mta['build-parameters'], undefined);
    assert.deepEqual(mta.parameters, { 'enable-parallel-deployments': true, 'ainv-host-prefix': 'ainvoicer-${space}' });
    for (const resource of mta.resources) {
        assert.deepEqual(resource, { name: resource.name, type: 'org.cloudfoundry.existing-service',
            parameters: { 'service-name': resource.name } });
    }
    const srv = mta.modules.find(m => m.name === 'ainvoicer-srv');
    const router = mta.modules.find(m => m.name === 'ainvoicer-approuter');
    assert.equal(srv.type, 'java');
    assert.equal(router.type, 'approuter.nodejs');
    assert.deepEqual(srv.properties, { ...jreEnv, ...smokeEnv });
    assert.deepEqual(router.properties, { SESSION_TIMEOUT: 15, destinations: [
        { name: 'ainvoicer-cap', url: '~{srv-api/srv-url}', forwardAuthToken: true },
        { name: 'ui5', url: 'https://ui5.sap.com' }
    ] });
    assert.deepEqual(srv.provides, [{ name: 'srv-api', properties: { 'srv-url': '${default-url}' } }]);
    for (const module of mta.modules) {
        assert.equal(module.path, module.name + '/data.zip');
        assert.equal(module['build-parameters'], undefined);
        assert.equal(module.parameters.host, '${ainv-host-prefix}-' + (module === srv ? 'srv' : 'approuter'));
        assert.deepEqual(module.requires, (module === srv ? ['ainvoicer-auth', 'ainvoicer-destination'] :
            ['srv-api', 'ainvoicer-auth', 'ainvoicer-destination']).map(name => ({ name })));
    }
    assert.ok(!JSON.stringify(mta).includes('ainvoicer-db'));
    return mta;
}

function run(command, args, cwd = root) {
    // cmd wymaga tylko stałych, kontrolowanych argumentów (brak danych użytkownika i credentiali).
    const result = spawnSync(command, args, { cwd, stdio: 'inherit',
        shell: process.platform === 'win32' && ['npm', 'mvn'].includes(command) });
    assert.equal(result.status, 0, command + ' failed');
}

/** Sprawdza zawartość archiwum po oficjalnym `mbt assemble`, bez modyfikacji MTAR. */
export function verifyArchive(file) {
    const archive = new Zip(file);
    const entries = archive.getEntries().filter(e => !e.isDirectory);
    assert.equal(new Set(entries.map(e => e.entryName)).size, entries.length);
    assert.deepEqual(entries.map(e => e.entryName).sort(), [
        'META-INF/MANIFEST.MF', 'META-INF/mtad.yaml',
        'ainvoicer-approuter/data.zip', 'ainvoicer-srv/data.zip'
    ]);
    validate(YAML.parse(archive.readAsText('META-INF/mtad.yaml')));
    const srv = new Zip(archive.readFile('ainvoicer-srv/data.zip'));
    assert.deepEqual(srv.getEntries().map(e => e.entryName), ['cap-java-exec.jar']);
    const router = new Zip(archive.readFile('ainvoicer-approuter/data.zip'));
    for (const entry of router.getEntries()) {
        assert.ok(!/(^|\/)(node_modules|test|localService|\.env|default-env|default-services)/i.test(entry.entryName), entry.entryName);
        if (!entry.isDirectory && /\.(js|json|html|xml|properties)$/.test(entry.entryName)) {
            assert.ok(!/-----BEGIN [A-Z ]*PRIVATE KEY-----|\bsk-[\w-]{20,}|\beyJ[\w-]{10,}\.[\w-]{10,}\.[\w-]{10,}|clientsecret/i.test(entry.getData().toString()), entry.entryName);
        }
    }
    const manifest = JSON.parse(router.readAsText('resources/manifest.json'));
    assert.equal(manifest['sap.ui5'].config.ksefCockpit.dataMode, 'rap');
    assert.deepEqual(manifest['sap.ui5'].config.ksefCockpit.externalServices,
        { ai: false, scanner: false, voice: false, tts: false });
    console.log('MTAR validation PASS: two modules, existing services only, no HDI, all mutations OFF');
}

function main() {
    const mta = descriptor(loadMta());
    const out = join(root, 'deploy/build');
    mkdirSync(out, { recursive: true });
    writeFileSync(join(out, 'identity-smoke.mtad.yaml'), YAML.stringify(mta));
    if (process.argv[2] === 'validate') { console.log('Smoke descriptor validation PASS'); return; }
    assert.equal(process.argv[2], 'build', 'expected validate or build');
    const target = join(out, 'ainvoicer_0.7.2.mtar');
    rmSync(target, { force: true });
    run('mvn', ['-B', '-f', 'cap-java/pom.xml', 'clean', 'verify', '-Dcds.cds.skipVersionCheck=true']);
    run('npm', ['run', 'test:cf-runtime'], join(root, 'deploy'));
    run('npm', ['ci'], join(root, 'sapui5/ainvoicer'));
    run('npm', ['run', 'build:cf'], join(root, 'sapui5/ainvoicer'));
    run('npm', ['run', 'test:cf-build'], join(root, 'sapui5/ainvoicer'));
    run('npm', ['ci'], join(root, 'approuter'));
    run('npm', ['run', 'test:config'], join(root, 'approuter'));
    const stage = mkdtempSync(join(tmpdir(), 'ainv-smoke-'));
    try {
        const assembly = join(stage, 'assembly');
        mkdirSync(assembly);
        writeFileSync(join(assembly, 'mtad.yaml'), YAML.stringify(mta));
        const srv = join(stage, 'modules/ainvoicer-srv');
        const router = join(stage, 'modules/ainvoicer-approuter');
        mkdirSync(srv, { recursive: true }); mkdirSync(router, { recursive: true });
        cpSync(join(root, 'cap-java/srv/target/cap-java-exec.jar'), join(srv, 'cap-java-exec.jar'));
        for (const file of ['package.json', 'package-lock.json', 'xs-app.json']) {
            cpSync(join(root, 'approuter', file), join(router, file));
        }
        cpSync(join(root, 'sapui5/ainvoicer/dist-cf'), join(router, 'resources'), { recursive: true });
        for (const [name, dir] of [['ainvoicer-srv', srv], ['ainvoicer-approuter', router]]) {
            mkdirSync(join(assembly, name));
            const zip = new Zip(); zip.addLocalFolder(dir);
            zip.writeZip(join(assembly, name, 'data.zip'));
        }
        run(process.execPath, [join(root, 'deploy/node_modules/mbt/bin/mbt'), 'assemble', '-s', assembly, '-t', out], assembly);
        verifyArchive(target);
        console.log('Undeployed artifact: ' + target);
    } catch (error) { rmSync(target, { force: true }); throw error; }
    finally { rmSync(stage, { recursive: true, force: true }); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
