import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkExistingCfIdentity } from '../scripts/existing-cf-identity.mjs';

const config = { Target: 'https://api.cf.eu20-001.hana.ondemand.com',
    OrganizationFields: { Name: 'National Center for Cryptography_dev-v2xzij9s' },
    SpaceFields: { Name: 'dev', GUID: '00000000-0000-0000-0000-000000000001' },
    AccessToken: 'bearer synthetic-never-export' };

test('existing identity refuses wrong target or absent login before HTTP', async () => {
    let requests = 0;
    const fetcher = () => { requests++; };
    await assert.rejects(checkExistingCfIdentity({ ...config, Target: 'https://other.test' }, fetcher), /TARGET/);
    await assert.rejects(checkExistingCfIdentity({ ...config, AccessToken: '' }, fetcher), /UNAVAILABLE/);
    assert.equal(requests, 0);
});

test('existing CF identity goes only to the approved CAP as GET, without redirect or report credential', async () => {
    let requests = 0;
    const report = await checkExistingCfIdentity(config, async (url, options) => {
        requests++;
        assert.equal(new URL(url).origin, 'https://ainvoicer-v2xzij9s-srv.cfapps.eu20-001.hana.ondemand.com');
        assert.equal(options.method, 'GET');
        assert.equal(options.redirect, 'manual');
        assert.equal(options.credentials, 'omit');
        assert.equal(options.headers.Authorization, config.AccessToken);
        return new Response(null, { status: 401 });
    });
    assert.equal(requests, 2);
    assert.ok(report.checks.every(c => c.result === 'PASS'));
    assert.ok(!JSON.stringify(report).includes(config.AccessToken));
});
