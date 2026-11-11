import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import { origin, plan, probe } from '../scripts/live-security.mjs';
import { root } from '../scripts/identity-smoke.mjs';

test('probe origins reject credentials, HTTP remote hosts, path/query/fragment before requests', () => {
    for (const url of ['https://u:p@example.test', 'http://example.test', 'https://example.test/other',
        'https://example.test/?token=hidden', 'https://example.test/#hidden']) assert.throws(() => origin(url));
    assert.equal(origin('https://example.test'), 'https://example.test');
    assert.ok(plan('http://127.0.0.1:8080', 'https://example.test').every(p => !p.authorization || p.authorization === 'Bearer not-a-jwt'));
});

test('actual HTTP probes use only GET, suppress secrets and never follow redirects', async () => {
    let followed = false;
    const methods = [];
    const server = createServer((req, res) => {
        methods.push(req.method);
        if (req.url === '/redirect') { res.writeHead(302, { Location: '/secret', 'Set-Cookie': 'SECRET=never-print' }); res.end(); }
        else if (req.url === '/secret') { followed = true; res.end('SECRET'); }
        else { res.writeHead(401); res.end('safe denial'); }
    });
    await new Promise(r => server.listen(0, '127.0.0.1', r));
    try {
        const base = 'http://127.0.0.1:' + server.address().port;
        assert.equal((await probe({ id: '401', url: base, expected: [401] })).result, 'PASS');
        const report = await probe({ id: 'redirect', url: base + '/redirect', expected: [302] });
        assert.equal(report.result, 'PASS');
        assert.ok(!JSON.stringify(report).includes('SECRET'));
        assert.equal(followed, false);
        assert.deepEqual(methods, ['GET', 'GET']);
    } finally { await new Promise(r => server.close(r)); }
});

test('probe reports exposed CAP index, unsafe error and oversized body as FAIL without raw body', async () => {
    for (const [status, text] of [[200, 'KsefService index'], [500, 'clientsecret=synthetic-hidden'], [401, 'x'.repeat(65537)]]) {
        const report = await probe({ id: 'negative', url: 'https://example.test', expected: [status] },
            async () => new Response(text, { status }));
        assert.equal(report.result, 'FAIL');
        assert.ok(!JSON.stringify(report).includes('synthetic-hidden'));
    }
    const report = await probe({ id: 'offline', url: 'https://example.test', expected: [401] }, () => { throw new Error('synthetic-hidden'); });
    assert.equal(report.result, 'BLOCKED');
    assert.ok(!JSON.stringify(report).includes('synthetic-hidden'));
});

function browser(fetcher, originValue = 'https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com') {
    const context = vm.createContext({ location: { origin: originValue }, crypto: webcrypto, fetch: fetcher,
        AbortSignal, TextDecoder, URL, console });
    vm.runInContext(readFileSync(join(root, 'deploy/scripts/browser-session-acceptance.js'), 'utf8'), context);
    return context.ainvSecurityAcceptance;
}

test('browser helper requires approved origin/mode/reviewed UUID before the first HTTP call', async () => {
    let calls = 0;
    const fetcher = () => { calls++; };
    await assert.rejects(browser(fetcher, 'https://evil.test').run({ mode: 'operator' }), /origin/);
    await assert.rejects(browser(fetcher).run({ mode: 'unknown' }), /mode/);
    await assert.rejects(browser(fetcher).run({ mode: 'operator', rapForeignKey: '../ApplyKsefResult' }), /UUID/);
    assert.equal(calls, 0);
});

test('browser helper uses same-origin GET; nonempty/empty data alone never proves A/B isolation', async () => {
    const calls = [];
    const suite = browser(async (path, options) => {
        calls.push({ path, options });
        assert.ok(path.startsWith('/odata/v4/KsefService/') || path.startsWith('/rap-ui/'));
        assert.equal(options.method, 'GET');
        assert.equal(options.credentials, 'same-origin');
        assert.equal(options.redirect, 'manual');
        assert.equal(options.headers.Authorization, undefined);
        if (path.includes('KsefInbound') || path.includes('SapResultWrites')) return new Response('{}', { status: 404 });
        if (path.endsWith('$metadata')) return new Response('<xml/>');
        return Response.json({ value: [], '@odata.count': 0 });
    });
    const report = await suite.run({ mode: 'operator' });
    assert.equal(report.checks.find(c => c.id === 'CAP InvoiceIntegrationStatus count/list').result, 'BLOCKED_EMPTY_FIXTURE');
    assert.match(report.principal, /BLOCKED/);
    assert.ok(report.checks.some(c => c.result === 'BLOCKED'));
    assert.ok(!JSON.stringify(report).includes('<xml/>'));
    assert.ok(calls.length > 5);
});

test('logout helper accepts denial, rejects still-active session and blocks network errors', async () => {
    const closed = await browser(async () => new Response(null, { status: 401 })).deniedAfterSessionEnd();
    assert.ok(closed.checks.every(c => c.result === 'PASS'));
    const active = await browser(async () => Response.json({ value: [] })).deniedAfterSessionEnd();
    assert.ok(active.checks.every(c => c.result === 'FAIL'));
    const offline = await browser(() => { throw new Error('network'); }).deniedAfterSessionEnd();
    assert.ok(offline.checks.every(c => c.result === 'BLOCKED'));
});

test('no CAP role denies data while authenticated metadata may still be visible', async () => {
    const suite = browser(async path => {
        if (path.endsWith('$metadata')) return new Response('<xml/>');
        if (path.startsWith('/odata/')) return new Response('{}', { status: 403 });
        return Response.json({ value: [], '@odata.count': 0 });
    });
    const report = await suite.run({ mode: 'no-cap-role' });
    assert.equal(report.checks.find(c => c.id === 'CAP metadata').result, 'OBSERVED_ONLY');
    assert.equal(report.checks.find(c => c.id === 'CAP InvoiceIntegrationStatus count/list').result, 'PASS');
    assert.ok(report.checks.every(c => c.result !== 'FAIL'));
});
