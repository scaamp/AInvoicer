import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { waitForCdp } from './cdp-startup.mjs';

async function fixture(run) {
    const profileDir = await mkdtemp(join(tmpdir(), 'ainv-cdp-startup-'));
    try {
        await writeFile(join(profileDir, 'DevToolsActivePort'), '43210\n/devtools/browser/fixture');
        await run(profileDir);
    } finally { await rm(profileDir, { recursive: true, force: true }); }
}

test('cold start may publish a page after the old 30 second deadline', () => fixture(async profileDir => {
    let elapsed = 0;
    const endpoint = await waitForCdp({ profileDir, browser: { exitCode: null },
        now: () => elapsed, sleep: async ms => { elapsed += ms; },
        fetchImpl: async url => {
            assert.equal(url, 'http://127.0.0.1:43210/json/list');
            if (elapsed < 5000) { throw new Error('connection refused'); }
            return { ok: true, json: async () => elapsed < 35000 ? []
                : [{ type: 'page', webSocketDebuggerUrl: 'ws://127.0.0.1:43210/devtools/page/fixture' }] };
        } });
    assert.equal(elapsed, 35000);
    assert.equal(endpoint, 'ws://127.0.0.1:43210/devtools/page/fixture');
}));

test('no page still fails at a bounded deadline with the last readiness state', () => fixture(async profileDir => {
    let elapsed = 0;
    await assert.rejects(waitForCdp({ profileDir, browser: { exitCode: null }, timeoutMs: 600,
        now: () => elapsed, sleep: async ms => { elapsed += ms; },
        fetchImpl: async () => ({ ok: true, json: async () => [] }) }),
    /timeout after 600ms: DevTools reachable, page not ready/);
    assert.equal(elapsed, 600);
}));

test('HTTP discovery failures retain their cause instead of reporting an unreachable browser', () => fixture(async profileDir => {
    let elapsed = 0;
    await assert.rejects(waitForCdp({ profileDir, browser: { exitCode: null }, timeoutMs: 200,
        now: () => elapsed, sleep: async ms => { elapsed += ms; },
        fetchImpl: async () => ({ ok: false, status: 503 }) }), /DevTools HTTP 503/);
}));

test('process exit, signal and spawn failure fail immediately without polling', async () => {
    for (const state of [{ exitCode: 1 }, { exitCode: null, signalCode: 'SIGTERM' }, { exitCode: null, error: 'ENOENT' }]) {
        await assert.rejects(waitForCdp({ browser: state, profileDir: 'unused',
            launchError: () => state.error, diagnostics: () => 'launch diagnostic',
            fetchImpl: () => { assert.fail('must not poll an exited browser'); } }),
        /browser failed to start: (1|SIGTERM|ENOENT)\nlaunch diagnostic/);
    }
});
