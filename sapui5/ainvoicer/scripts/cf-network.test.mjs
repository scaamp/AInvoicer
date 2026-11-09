import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { stageWebapp } from './build-cf.mjs';

// Rzeczywisty Chrome/CDP: polityka pochodząca ze stagingu CF blokuje HTTP mimo pominięcia bramki JS.
test('CF Content Security Policy blocks an external HTTP POST before it leaves the browser', async () => {
    const stage = mkdtempSync(join(tmpdir(), 'ainv-csp-'));
    stageWebapp('webapp', stage);
    const meta = readFileSync(join(stage, 'index.html'), 'utf8').match(/<meta http-equiv="Content-Security-Policy"[^>]+>/)[0];
    let foreignRequests = 0, ownRequests = 0;
    const foreign = createServer((req, res) => { foreignRequests++; res.setHeader('Access-Control-Allow-Origin', '*'); res.end('unexpected'); });
    await new Promise(r => foreign.listen(0, '127.0.0.1', r));
    const server = createServer((req, res) => {
        if (req.url === '/allowed') { ownRequests++; res.end('ok'); return; }
        res.setHeader('Content-Type', 'text/html');
        res.end(`<head>${meta}</head><div id="qunit-testresult"></div><ol id="qunit-tests"></ol><script>
            (async () => {
                let denied = false;
                try { await fetch('http://127.0.0.1:${foreign.address().port}/invoice', { method: 'POST', body: 'synthetic-invoice' }); }
                catch (error) { denied = true; }
                await fetch('/allowed');
                window.QUnit = {};
                document.querySelector('#qunit-tests').innerHTML = '<li class="' + (denied ? 'pass' : 'fail') + '"><span class="module-name">CF CSP</span><span class="test-name">external denied</span></li>';
                document.querySelector('#qunit-testresult').innerHTML = '<span class="total">1</span>';
            })();</script>`);
    });
    await new Promise(r => server.listen(0, '127.0.0.1', r));
    try {
        const result = await new Promise((resolve, reject) => {
            const env = { ...process.env };
            delete env.AINV_CAP_LOCAL_TEST_USER; delete env.AINV_CAP_LOCAL_TEST_PASSWORD;
            const child = spawn(process.execPath, ['scripts/browser-test.mjs', 'qunit', `http://127.0.0.1:${server.address().port}/`, '15000'],
                { env, stdio: ['ignore', 'pipe', 'pipe'] });
            let output = '';
            child.stdout.on('data', b => { output += b; }); child.stderr.on('data', b => { output += b; });
            child.on('error', reject); child.on('exit', code => resolve({ code, output }));
        });
        assert.equal(result.code, 0, result.output);
        assert.equal(foreignRequests, 0); assert.equal(ownRequests, 1);
    } finally {
        server.closeAllConnections(); foreign.closeAllConnections();
        await Promise.all([new Promise(r => server.close(r)), new Promise(r => foreign.close(r))]);
        rmSync(stage, { recursive: true, force: true });
    }
});
