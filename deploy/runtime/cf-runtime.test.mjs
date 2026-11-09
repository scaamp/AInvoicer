// Rzeczywisty executable JAR + zmienne środowiskowe CF, bez ręcznego DataSource i bez sieci do BTP.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { root, smokeEnv } from '../scripts/identity-smoke.mjs';

const xsuaa = JSON.stringify({ xsuaa: [{ name: 'synthetic-xsuaa', label: 'xsuaa', plan: 'application', tags: ['xsuaa'],
    credentials: { clientid: 'sb-offline!t1', xsappname: 'offline!t1', uaadomain: 'localhost', url: 'http://localhost:1',
        identityzone: 'offline-test', tenantid: 'offline-test-tenant' } }] });

async function boot(overrides, check) {
    const inherited = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
        !/^(AINV_|CDS_|SPRING_|VCAP_|DESTINATIONS$)/i.test(key)));
    const env = { ...inherited, ...smokeEnv, VCAP_SERVICES: xsuaa,
        VCAP_APPLICATION: JSON.stringify({ application_name: 'ainvoicer-offline-cf', space_name: 'offline' }),
        SERVER_PORT: '0', SERVER_ADDRESS: '127.0.0.1', ...overrides };
    for (const key of Object.keys(env)) if (env[key] === undefined) delete env[key];
    const child = spawn('java', ['-jar', join(root, 'cap-java/srv/target/cap-java-exec.jar')],
        { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '', port;
    let timer;
    const done = new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error('CF JAR startup timeout')), 45000);
        const observe = data => {
            output = (output + data).slice(-2000000);
            port = output.match(/Tomcat started on port (\d+)/)?.[1];
            if (port && /Started Application in/.test(output)) resolve({ started: true });
        };
        child.stdout.on('data', observe); child.stderr.on('data', observe);
        child.on('error', reject); child.on('close', code => resolve({ started: false, code }));
    });
    try { await check(await done, () => output, () => Number(port)); }
    finally {
        clearTimeout(timer);
        if (child.exitCode === null) {
            const closed = new Promise(r => child.once('close', r));
            child.kill(); await closed;
        }
    }
}

test('packaged CF JAR starts with only descriptor env and refuses anonymous public surfaces', async () => {
    await boot({}, async (result, output, port) => {
        assert.equal(result.started, true, 'identity-smoke must start');
        const base = 'http://127.0.0.1:' + port();
        for (const path of ['/', '/$fiori-preview/', '/h2-console/', '/actuator/health', '/actuator/env',
            '/swagger-ui/', '/v3/api-docs', '/odata/v4/KsefService/$metadata']) {
            const response = await fetch(base + path, { redirect: 'manual' });
            assert.ok([401, 403, 404].includes(response.status), path + ': ' + response.status);
            assert.ok(!(await response.text()).includes('KsefService'), 'no anonymous API index');
        }
        for (const auth of ['Bearer not-a-jwt', 'Basic ' + Buffer.from('privileged:').toString('base64')]) {
            assert.equal((await fetch(base + '/odata/v4/KsefService/$metadata', { headers: { Authorization: auth } })).status, 401);
        }
        for (const path of ['requestSend', 'KsefOperations']) {
            const r = await fetch(base + '/odata/v4/KsefService/' + path, {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
            assert.ok([401, 403].includes(r.status));
        }
        assert.ok(output().includes('IDENTITY SMOKE MODE'));
    });
});

const failures = [
    ['no XSUAA', { VCAP_SERVICES: '{}' }, 'requires exactly one XSUAA service binding'],
    ['no HANA, smoke OFF', { AINV_DEPLOYMENT_IDENTITY_SMOKE: 'false' }, 'requires durable SAP HANA Cloud'],
    ['sweeper ON', { AINV_SAP_WRITE_SWEEPER_ENABLED: 'true' }, 'Identity smoke mode requires'],
    ['KSeF ON', { AINV_KSEF_ENABLED: 'true' }, 'Identity smoke mode requires'],
    ['SAP WRITE ON', { AINV_SAP_WRITE_ENABLED: 'true' }, 'Identity smoke mode requires'],
    ['mock ON', { CDS_SECURITY_MOCK_ENABLED: 'true' }, 'requires cds.security.mock.enabled=false'],
    ['no H2 platform', { SPRING_SQL_INIT_PLATFORM: undefined }, 'requires SPRING_SQL_INIT_PLATFORM=h2']
];
for (const [name, overrides, reason] of failures) test('packaged CF JAR fails closed: ' + name, async () => {
    await boot(overrides, async (result, output) => {
        assert.equal(result.started, false, name + ' must fail startup');
        assert.notEqual(result.code, 0);
        assert.ok(output().includes(reason), name + ': expected safe diagnostic ' + reason);
    });
});
