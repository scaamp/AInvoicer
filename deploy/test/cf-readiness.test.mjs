import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bindingIdentity, checkedCapi, collect, list, safeEnvironment, safeXsuaa, sapMachineVersion, verifyTarget } from '../scripts/cf-readiness.mjs';

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

test('CAPI error body with CLI exit0 remains BLOCKED, never a failed XSUAA policy', async () => {
    const unsupported = { errors: [{ code: 120004, title: 'CF-ServiceFetchInstanceParametersNotSupported', detail: marker }] };
    assert.throws(() => checkedCapi(unsupported), /CF_PARAMETERS_UNSUPPORTED/);
    assert.throws(() => safeXsuaa(unsupported), /CF_PARAMETERS_UNSUPPORTED/);
    await assert.rejects(list(() => ({ errors: [{ code: 10003, detail: marker }] }), '/v3/apps'), /CF_READ_BLOCKED/);
    assert.ok(Object.values(safeXsuaa({})).every(v => v === null), 'missing descriptor is not six negative policy checks');
});

test('binding identity and JRE diagnostics redact credentials and distinguish absent evidence', () => {
    const report = bindingIdentity({ system_env_json: { VCAP_SERVICES: { xsuaa: [{ credentials: {
        clientid: marker, clientsecret: marker, verificationkey: marker, tenantmode: 'dedicated', xsappname: 'ainvoicer-dev!t123'
    } }] } } });
    assert.deepEqual(report, { exactlyOne: true, dedicated: true, expectedApplicationFamily: true });
    assert.ok(!JSON.stringify(report).includes(marker));
    assert.equal(sapMachineVersion({}), null);
    assert.equal(sapMachineVersion({ detect_output: 'sap_machine_jre=25.0.4' }), '25.0.4');
    assert.equal(sapMachineVersion({ detect_output: 'SapMachine JRE 25.0.5+3' }), '25.0.5+3');
});

test('live-shaped inventory keeps unsupported broker and unconstrained stack separate from valid bindings', async () => {
    const report = await collect(target, path => {
        if (path.startsWith('/v3/buildpacks')) return { resources: [{ name: 'sap_java_buildpack_jakarta', stack: null, enabled: true }] };
        if (path.startsWith('/v3/stacks')) return { resources: [{ name: 'cflinuxfs5' }] };
        if (path.startsWith('/v3/service_instances?')) return { resources: [{ name: 'ainvoicer-auth', guid: id },
            { name: 'ainvoicer-hana-dev', guid: id, relationships: { service_plan: { data: { guid: id } } },
                last_operation: { state: 'succeeded' } }] };
        if (path.startsWith('/v3/service_plans?')) return { resources: [{ guid: id, name: 'hana-free' }] };
        if (path.endsWith('/parameters')) return { errors: [{ code: 120004, detail: marker }] };
        if (path.startsWith('/v3/apps?')) return { resources: [{ name: 'ainvoicer-srv', guid: id },
            { name: 'ainvoicer-approuter', guid: id }] };
        if (path.endsWith('/env')) return { environment_variables: {}, system_env_json: { VCAP_SERVICES: {
            xsuaa: [{ credentials: { xsappname: 'ainvoicer-dev!t123', tenantid: marker,
                tenantmode: 'dedicated', clientsecret: marker } }]
        } } };
        if (path.endsWith('/droplets/current')) return { stack: 'cflinuxfs4', buildpacks: [{ name: 'sap_java_buildpack_jakarta' }] };
        return { resources: [] };
    });
    assert.match(report.xsuaaConfiguration, /BLOCKED.*CF120004/);
    assert.equal(report.sameBoundApplicationAndTenant, true);
    assert.equal(report.buildpacks[0].stack, null);
    assert.equal(report.apps[0].currentDroplet.buildpacks[0].sapMachineVersion, null);
    assert.deepEqual(report.services.find(s => s.name === 'ainvoicer-hana-dev'),
        { name: 'ainvoicer-hana-dev', plan: 'hana-free', lastOperationSucceeded: true });
    assert.ok(!JSON.stringify(report).includes(marker));
});
