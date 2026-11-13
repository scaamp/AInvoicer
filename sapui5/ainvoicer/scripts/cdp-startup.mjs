import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

// Chrome wybiera wolny port i publikuje go we własnym, odizolowanym profilu.
// Deadline obejmuje zimny start procesu i utworzenie strony; brak gotowości nadal oznacza FAIL.
export async function waitForCdp({ browser, profileDir, diagnostics = () => '', launchError = () => '',
    timeoutMs = 90000, now = Date.now, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)),
    fetchImpl = fetch }) {
    const started = now();
    let lastState = 'DevToolsActivePort not published';
    while (now() - started < timeoutMs) {
        if (launchError() || browser.exitCode !== null || browser.signalCode) {
            throw new Error(`ENVIRONMENT: browser failed to start: ${launchError() || browser.signalCode || browser.exitCode}\n${diagnostics()}`);
        }
        try {
            const activePort = await readFile(join(profileDir, 'DevToolsActivePort'), 'utf8');
            const port = Number(activePort.split('\n')[0]);
            if (!Number.isInteger(port) || port < 1 || port > 65535) {
                throw new Error('Invalid DevToolsActivePort');
            }
            const remaining = timeoutMs - (now() - started);
            if (remaining <= 0) { break; }
            const response = await fetchImpl(`http://127.0.0.1:${port}/json/list`, {
                signal: AbortSignal.timeout(Math.min(1000, remaining))
            });
            if (!response.ok) { throw new Error(`DevTools HTTP ${response.status}`); }
            const targets = await response.json();
            if (!Array.isArray(targets)) { throw new Error('Invalid DevTools target list'); }
            const page = targets.find(target => target.type === 'page' && target.webSocketDebuggerUrl);
            if (page) { return page.webSocketDebuggerUrl; }
            lastState = `DevTools reachable, page not ready (${targets.length} targets)`;
        } catch (error) {
            // Zachowujemy przyczynę zamiast mylić brak strony z brakiem endpointu.
            lastState = error.code === 'ENOENT' ? 'DevToolsActivePort not published'
                : `${error.message}${error.cause?.code ? ` (${error.cause.code})` : ''}`;
        }
        await sleep(Math.min(200, Math.max(0, timeoutMs - (now() - started))));
    }
    throw new Error(`ENVIRONMENT: DevTools startup timeout after ${now() - started}ms: ${lastState}\n${diagnostics()}`);
}
