import { test } from 'node:test';
import assert from 'node:assert/strict';
import { manifest, taskRequest, TARGET, commands, cleanupCommands, validateTarget, FLAGS } from '../scripts/hana-it-runner.mjs';

const env = () => ({ AINV_HANA_TEST_CONSENT: 'READ_ONLY_CHECK_APPROVED_HDI',
    AINV_HANA_TEST_RUN_ID: '1e335114-c800-4ff3-9ed8-56a914126af8', AINV_HANA_TEST_NIP: '7770002317',
    AINV_HANA_TEST_HDI_NAME: 'ainvoicer-db' });

test('R2 manifest has no route, no web instance and no default consent/DML', () => {
    const app = manifest().applications[0];
    assert.equal(app['no-route'], true); assert.equal(app.instances, 0);
    assert.equal(app.routes, undefined); assert.equal(app.services, undefined);
    assert.equal(app.env.AINV_HANA_TEST_CONSENT, undefined);
    assert.equal(app.env.AINV_HANA_TEST_PHASE, undefined);
    assert.equal(app.env.JBP_CONFIG_SAP_MACHINE_JRE, '{ version: 25.+ }');
    for (const key of ['AINV_KSEF_ENABLED', 'AINV_KSEF_INBOUND_ENABLED', 'AINV_SAP_WRITE_ENABLED', 'AINV_SAP_WRITE_SWEEPER_ENABLED'])
        assert.equal(FLAGS[key], 'false');
});

test('R2 task generator requires explicit phase and owner consent, without a default DML', () => {
    for (const phase of ['check', 'recover', 'verify-cleanup']) {
        const task = taskRequest(phase, env());
        assert.match(task.command, new RegExp('--run ' + phase + '$'));
        assert.ok(task.command.length < 4096);
        assert.equal(task.memory_in_mb, 1024);
    }
    for (const phase of [undefined, '', 'all', 'drop', 'seed', 'contend', 'cleanup', 'check;echo x'])
        assert.throws(() => taskRequest(phase, env()));
    for (const key of Object.keys(env())) { const e = env(); delete e[key]; assert.throws(() => taskRequest('check', e)); }
    for (const phase of ['seed', 'contend', 'cleanup']) assert.doesNotThrow(() => taskRequest(phase,
        { ...env(), AINV_HANA_TEST_CONSENT: 'WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI' }));
});

test('R2 rejects other target, HDI, invalid scope and shell input', () => {
    for (const key of Object.keys(TARGET)) {
        const target = { ...TARGET, [key]: 'wrong' };
        assert.throws(() => validateTarget(target)); assert.throws(() => commands(target));
        assert.throws(() => cleanupCommands(target)); assert.throws(() => taskRequest('check', env(), target));
    }
    for (const [key, value] of [['AINV_HANA_TEST_HDI_NAME', 'ainvoicer-db-test'], ['AINV_HANA_TEST_RUN_ID', 'x;id'],
        ['AINV_HANA_TEST_NIP', '5260002008'], ['AINV_HANA_TEST_NIP', '0000000000'], ['AINV_HANA_TEST_CONSENT', 'yes']])
        assert.throws(() => taskRequest('check', { ...env(), [key]: value }));
});

test('R2 generated CF plan stages only the runner, with existing bindings and target guard', () => {
    const plan = commands();
    assert.match(plan, /--no-route --no-start/); assert.match(plan, /--task --no-route/);
    assert.match(plan, /Runner already exists/); assert.match(plan, /target mismatch/);
    assert.match(plan, /bind-service ainvoicer-hana-it ainvoicer-db/);
    assert.match(plan, /bind-service ainvoicer-hana-it ainvoicer-auth/);
    assert.doesNotMatch(plan, /create-service|create-service-key|DBADMIN|cf env|restart|run-task/);
    assert.doesNotMatch(cleanupCommands(), /delete-service |delete .* -r|ainvoicer-srv/);
});

// KSEF-STOR-04R: fazy harnessu prywatnej tabeli; generator nie wykonuje CF i nie ustawia zgody.
import { stor04TaskRequest, STOR04_PHASES, STOR04_READ_CONSENT, STOR04_WRITE_CONSENT } from '../scripts/hana-it-runner.mjs';
const COMMIT = '0123456789abcdef0123456789abcdef01234567';
const clean = { commit: COMMIT, workingTreeDirty: false };
const storEnv = (phase, extra = {}) => ({ AINV_STOR04_IT_CONSENT: STOR04_PHASES[phase].write ? STOR04_WRITE_CONSENT : STOR04_READ_CONSENT,
    AINV_STOR04_IT_RUN_ID: '1e335114-c800-4ff3-9ed8-56a914126af8', AINV_STOR04_IT_NIP: '7770002317',
    AINV_STOR04_IT_ENVIRONMENT: 'TEST', AINV_STOR04_IT_HDI_NAME: 'ainvoicer-db', AINV_STOR04_IT_COMMIT: COMMIT,
    AINV_STOR04_IT_MAX_WRITES: String(STOR04_PHASES[phase].rows), ...extra });

test('STOR04 task generator: exact phase, consent, pinned clean commit and write limit; one task per call', () => {
    assert.deepEqual(Object.keys(STOR04_PHASES), ['stor04-catalog', 'stor04-roundtrip', 'stor04-contend', 'stor04-recover']);
    assert.deepEqual(Object.values(STOR04_PHASES).map(p => p.rows), [0, 3, 1, 0]);
    for (const phase of Object.keys(STOR04_PHASES)) {
        const task = stor04TaskRequest(phase, storEnv(phase), clean);
        assert.match(task.command, new RegExp(`AINV_STOR04_IT_COMMIT=${COMMIT} AINV_STOR04_IT_MAX_WRITES=${STOR04_PHASES[phase].rows} `));
        assert.match(task.command, new RegExp('--run ' + phase + '$'));
        assert.match(task.name, /^stor04-it-(catalog|roundtrip|contend|recover)-1e335114$/);
        assert.ok(task.command.length < 4096);
        assert.equal(task.memory_in_mb, 1024);
        assert.doesNotMatch(task.command, /AINV_HANA_TEST_|VCAP|password|secret/i);
        assert.deepEqual(stor04TaskRequest(phase, storEnv(phase), clean), task, 'deterministic');
    }
    for (const phase of ['stor04-roundtrip', 'stor04-contend']) {
        assert.throws(() => stor04TaskRequest(phase, storEnv(phase, { AINV_STOR04_IT_CONSENT: STOR04_READ_CONSENT }), clean));
        assert.throws(() => stor04TaskRequest(phase, storEnv(phase, { AINV_STOR04_IT_CONSENT: 'WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI' }), clean));
        assert.throws(() => stor04TaskRequest(phase, storEnv(phase, { AINV_STOR04_IT_MAX_WRITES: String(STOR04_PHASES[phase].rows - 1) }), clean));
    }
    for (const phase of ['stor04-catalog', 'stor04-recover']) {
        assert.throws(() => stor04TaskRequest(phase, storEnv(phase, { AINV_STOR04_IT_CONSENT: STOR04_WRITE_CONSENT }), clean));
        assert.throws(() => stor04TaskRequest(phase, storEnv(phase, { AINV_STOR04_IT_MAX_WRITES: '1' }), clean));
    }
    const p = 'stor04-roundtrip';
    for (const [key, value] of [['AINV_STOR04_IT_COMMIT', 'f'.repeat(40)], ['AINV_STOR04_IT_ENVIRONMENT', 'PROD'],
        ['AINV_STOR04_IT_HDI_NAME', 'ainvoicer-db-test'], ['AINV_STOR04_IT_RUN_ID', 'x;id'], ['AINV_STOR04_IT_NIP', '5260002008'],
        ['AINV_STOR04_IT_MAX_WRITES', '9'], ['AINV_STOR04_IT_MAX_WRITES', '3;rm'], ['AINV_STOR04_IT_CONSENT', undefined]])
        assert.throws(() => stor04TaskRequest(p, storEnv(p, { [key]: value }), clean), key);
    assert.throws(() => stor04TaskRequest(p, storEnv(p), { ...clean, workingTreeDirty: true }), /clean commit/);
    assert.throws(() => stor04TaskRequest(p, storEnv(p), undefined));
    assert.throws(() => stor04TaskRequest(p, storEnv(p), clean, { ...TARGET, space: 'prod' }));
    for (const phase of [undefined, '', 'stor04-all', 'stor04-cleanup', 'roundtrip', 'check', '__proto__', 'constructor'])
        assert.throws(() => stor04TaskRequest(phase, storEnv('stor04-catalog'), clean), String(phase));
    // Stary generator nie przyjmuje faz storage, nowy nie przyjmuje faz CAP-HANA-02.
    assert.throws(() => taskRequest('stor04-catalog', env()));
});
