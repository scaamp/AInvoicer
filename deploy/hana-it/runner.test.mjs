// Testy spakowanego JAR w odrębnych JVM: tylko self-test/preflight i odmowa startu. Zero HANA.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, cpSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Zip from 'adm-zip';
import { FLAGS, TARGET, APP, out, verify } from '../scripts/hana-it-runner.mjs';

const safe = phase => ({ ...FLAGS, AINV_HANA_TEST_PHASE: phase,
    AINV_HANA_TEST_CONSENT: 'READ_ONLY_CHECK_APPROVED_HDI', AINV_HANA_TEST_HDI_NAME: 'ainvoicer-db',
    AINV_HANA_TEST_RUN_ID: '1e335114-c800-4ff3-9ed8-56a914126af8', AINV_HANA_TEST_NIP: '7770002317',
    VCAP_APPLICATION: JSON.stringify({ application_name: APP, cf_api: TARGET.api, organization_name: TARGET.org,
        space_name: TARGET.space, application_uris: [], uris: [] }),
    VCAP_SERVICES: JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db', plan: 'hdi-shared',
        credentials: { schema: 'OFFLINE', user: 'OFFLINE', password: 'SENTINEL-PASSWORD-NEVER-LOG', host: '127.0.0.1', port: 1 } }],
    xsuaa: [{ label: 'xsuaa', name: 'ainvoicer-auth', plan: 'application', credentials: {
        clientid: 'OFFLINE', clientsecret: 'SENTINEL-SECRET-NEVER-LOG', url: 'https://invalid.local', identityzoneid: 'OFFLINE' } }] }) });

function run(args, extra = {}, jarPath = join(out, 'runner.jar')) {
    // Zachowaj tylko środowisko systemowe; CI wolno ominąć wyłącznie w tym offline preflight, nigdy w --run.
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) =>
        !/^(AINV_|SPRING_|CDS_|VCAP_|CI$|GITHUB_ACTIONS$|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|JDK_JAVA_OPTIONS$|SERVICE_BINDING_ROOT$)/i.test(k)));
    const result = spawnSync('java', ['-jar', jarPath, ...args], { env: { ...env, ...extra }, encoding: 'utf8', timeout: 30000 });
    assert.equal(result.error, undefined);
    assert.doesNotMatch(result.stdout + result.stderr, /SENTINEL|jdbc:|127\.0\.0\.1|clientsecret|password|Exception|at org\./i);
    return result;
}

test('packaged JUnit loads the original harness and checks all phase gates offline', () => {
    const result = run(['--self-test']);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /R2_OFFLINE_SELF_TEST=PASS/);
});

test('default command and --run without consent fail before Spring for every phase', () => {
    assert.equal(run([]).status, 2);
    for (const phase of ['check', 'seed', 'recover', 'contend', 'cleanup', 'verify-cleanup']) {
        const env = safe(phase); delete env.AINV_HANA_TEST_CONSENT;
        assert.equal(run(['--run', phase], env).status, 2);
    }
});

test('fresh JVM preserves phases and enforces read-only vs write consent without connecting', () => {
    for (const phase of ['check', 'seed', 'recover', 'contend', 'cleanup', 'verify-cleanup']) {
        const env = safe(phase);
        const read = ['check', 'recover', 'verify-cleanup'].includes(phase);
        assert.equal(run(['--preflight', phase], env).status, read ? 0 : 2, phase);
        if (!read) env.AINV_HANA_TEST_CONSENT = 'WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI';
        const result = run(['--preflight', phase], env);
        assert.equal(result.status, 0, phase); assert.match(result.stdout, new RegExp('R2_PREFLIGHT_PASS=' + phase));
    }
});

test('fresh JVM rejects wrong target/routes/bindings/DBADMIN/flags, CI and malformed input without secrets', () => {
    const mutations = [
        e => { e.VCAP_APPLICATION = e.VCAP_APPLICATION.replace(TARGET.space, 'prod'); },
        e => { e.VCAP_APPLICATION = e.VCAP_APPLICATION.replace('"application_uris":[]', '"application_uris":["public.example"]'); },
        e => { e.VCAP_SERVICES = e.VCAP_SERVICES.replace('"user":"OFFLINE"', '"user":"DBADMIN"'); },
        e => { e.VCAP_SERVICES = e.VCAP_SERVICES.replace('ainvoicer-db', 'wrong-db'); },
        e => { const b = JSON.parse(e.VCAP_SERVICES); delete b.xsuaa; e.VCAP_SERVICES = JSON.stringify(b); },
        e => { const b = JSON.parse(e.VCAP_SERVICES); b.hana.push(b.hana[0]); e.VCAP_SERVICES = JSON.stringify(b); },
        e => { const b = JSON.parse(e.VCAP_SERVICES); b.hana[0].service_key_name = 'forbidden'; e.VCAP_SERVICES = JSON.stringify(b); },
        e => { e.VCAP_SERVICES = 'SENTINEL-invalid'; },
        e => { e.AINV_HANA_TEST_PHASE = 'seed'; }, e => { e.GITHUB_ACTIONS = 'true'; }, e => { e.CI = 'true'; },
        e => { e.SPRING_DATASOURCE_URL = 'SENTINEL'; }, e => { e.SPRING_APPLICATION_JSON = '{}'; },
        ...Object.keys(FLAGS).filter(k => !k.startsWith('JBP_')).map(k => e => { e[k] = 'unsafe'; })
    ];
    for (const mutate of mutations) { const env = safe('check'); mutate(env); assert.equal(run(['--preflight', 'check'], env).status, 2); }
    assert.equal(run(['--preflight', 'invalid'], safe('check')).status, 2);
});

test('integrity rejects changed manifest, removed class and corrupted nested dependency', () => {
    verify();
    const dir = mkdtempSync(join(tmpdir(), 'ainv-r2-integrity-'));
    try {
        for (const name of ['runner.jar', 'manifest.yml', 'evidence.json']) cpSync(join(out, name), join(dir, name));
        writeFileSync(join(dir, 'manifest.yml'), 'applications: []');
        assert.throws(() => verify(dir));
        cpSync(join(out, 'manifest.yml'), join(dir, 'manifest.yml'));
        const zip = new Zip(join(out, 'runner.jar'));
        const exploded = join(dir, 'exploded');
        zip.extractAllTo(exploded);
        const launch = spawnSync('java', ['-cp', exploded, 'org.springframework.boot.loader.launch.JarLauncher', '--self-test'],
            { encoding: 'utf8', timeout: 30000 });
        assert.equal(launch.status, 0, launch.stdout + launch.stderr);
        assert.match(launch.stdout, /R2_OFFLINE_SELF_TEST=PASS/);
        zip.deleteFile('BOOT-INF/classes/customer/cap_java/hana/HanaPersistenceIT.class');
        zip.writeZip(join(dir, 'runner.jar')); assert.throws(() => verify(dir));
        // Brak klasy powoduje rzeczywisty FAIL JUnit, nigdy fałszywy PASS/0.
        assert.equal(run(['--self-test'], {}, join(dir, 'runner.jar')).status, 1);
        const original = readFileSync(join(out, 'runner.jar')); original[100] ^= 1;
        writeFileSync(join(dir, 'runner.jar'), original); assert.throws(() => verify(dir));
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
