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
