import { test } from 'node:test';
import assert from 'node:assert/strict';
import { collect, list, safeEnvironment, safeXsuaa, verifyTarget } from '../scripts/cf-readiness.mjs';

const id = '00000000-0000-0000-0000-000000000001';
const target = { Target: 'https://api.cf.eu20-001.hana.ondemand.com',
    OrganizationFields: { Name: 'National Center for Cryptography_dev-v2xzij9s' }, SpaceFields: { Name: 'dev', GUID: id } };
const marker = 'NEVER_PRINT_THIS_SYNTHETIC_SECRET';

test('inventory refuses wrong API/org/space before CAPI request', async () => {
    assert.equal(verifyTarget(target), id);
    for (const change of [{ Target: 'https://other.example' }, { OrganizationFields: { Name: 'other' } },
        { SpaceFields: { Name: 'prod', GUID: id } }, { SpaceFields: { Name: 'dev', GUID: '../env' } }]) {
        let calls = 0;
        await assert.rejects(collect({ ...target, ...change }, () => { calls++; }), /CF_TARGET_MISMATCH/);
        assert.equal(calls, 0);
    }
});

test('environment projection never copies binding credentials or arbitrary env/URLs', () => {
    const report = safeEnvironment({ system_env_json: { VCAP_SERVICES: marker }, environment_variables: {
        SECRET: marker, AINV_KSEF_ENABLED: marker, SPRING_PROFILES_ACTIVE: marker,
        destinations: JSON.stringify([{ name: 'ainvoicer-cap', url: 'https://user:' + marker + '@example.test',
            forwardAuthToken: true, clientsecret: marker }, { name: marker, url: marker }])
    } });
    assert.ok(!JSON.stringify(report).includes(marker));
    assert.equal(report.flags.AINV_KSEF_ENABLED, 'UNEXPECTED');
    assert.equal(report.destinations[0].expectedUrl, false);
    assert.equal(safeEnvironment({ environment_variables: { destinations: marker } }).destinations.length, 0);
});

test('XSUAA output is only policy checks, without OAuth identity or raw parameters', () => {
    const report = safeXsuaa({ clientsecret: marker, clientid: marker, xsappname: marker,
        scopes: [{ name: marker }], 'oauth2-configuration': { 'redirect-uris': [marker] } });
    assert.ok(!JSON.stringify(report).includes(marker));
    assert.equal(report.narrowRedirectOnly, false);
    assert.equal(report.operatorScope, false);
});

test('CAPI pagination is same-origin GET and bounded; no silent partial inventory', async () => {
    const seen = [];
    const rows = await list(path => { seen.push(path); return seen.length === 1
        ? { resources: [1], pagination: { next: { href: 'https://api.cf.eu20-001.hana.ondemand.com/v3/stacks?page=2' } } }
        : { resources: [2] }; }, '/v3/stacks');
    assert.deepEqual(rows, [1, 2]);
    assert.deepEqual(seen, ['/v3/stacks', '/v3/stacks?page=2']);
    await assert.rejects(list(() => ({ resources: [], pagination: { next: { href: 'https://evil.test/v3/env' } } }), '/v3/stacks'), /UNSAFE/);
    await assert.rejects(list(() => ({ resources: [], pagination: { next: { href: '/v3/stacks' } } }), '/v3/stacks'), /LIMIT/);
});

test('inventory distinguishes fs5 availability from unexecuted JDK25 staging and principal proof', async () => {
    const report = await collect(target, path => {
        if (path.startsWith('/v3/stacks')) return { resources: [{ name: 'cflinuxfs5' }] };
        if (path.startsWith('/v3/buildpacks')) return { resources: [{ name: 'sap_java_buildpack_jakarta',
            stack: 'cflinuxfs5', filename: 'sap_java_buildpack_jakarta-v2.76.0.zip', enabled: true }] };
        return { resources: [] };
    });
    assert.equal(report.cflinuxfs5Available, true);
    assert.equal(report.buildpacks.length, 1);
    assert.match(report.stagingOnFs5Jdk25, /NOT_EXECUTED/);
    assert.match(report.destinationContent, /BLOCKED/);
});
