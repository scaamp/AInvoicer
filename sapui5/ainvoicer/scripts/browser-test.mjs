#!/usr/bin/env node
/*
 * Headless runner testów QUnit/OPA oraz smoke testu UI bez dodatkowych zależności npm.
 * Używa lokalnego Chrome/Edge przez Chrome DevTools Protocol i globalnego WebSocket (Node >= 22).
 *
 *   node scripts/browser-test.mjs qunit <url> [timeoutMs]
 *   node scripts/browser-test.mjs smoke <url> <screenshot.png> [timeoutMs]
 *
 * Kod wyjścia: 0 = PASS, 1 = testy nieudane / błąd aplikacji, 2 = problem środowiska (brak przeglądarki, timeout).
 * Wymaga uruchomionego serwera UI5, np. `npm run serve-mock` (port 8085).
 */
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const BROWSERS = [
    process.env.CHROME_PATH,
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
].filter(Boolean);

const [mode, url, arg3, arg4] = process.argv.slice(2);
if (!["qunit", "smoke"].includes(mode) || !url) {
    console.error("Usage: browser-test.mjs qunit <url> [timeoutMs] | smoke <url> <screenshot.png> [timeoutMs]");
    process.exit(2);
}
const timeoutMs = Number(mode === "qunit" ? arg3 : arg4) || 180000;
const browserPath = BROWSERS.find((p) => existsSync(p));
if (!browserPath) {
    console.error("ENVIRONMENT: no Chrome/Edge found (set CHROME_PATH)");
    process.exit(2);
}

try {
    await fetch(url, { method: "HEAD", signal: AbortSignal.timeout(5000) });
} catch (e) {
    console.error(`ENVIRONMENT: UI5 server not reachable at ${url}\n`
        + "Start it first in a separate terminal: npm run serve-mock");
    process.exit(2);
}

const port = 9300 + Math.floor(Math.random() * 500);
const profileDir = mkdtempSync(join(tmpdir(), "ainvoicer-cdp-"));
const browser = spawn(browserPath, [
    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
    "--window-size=1600,1000", `--remote-debugging-port=${port}`, `--user-data-dir=${profileDir}`, "about:blank"
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function cdpTarget() {
    for (let i = 0; i < 50; i++) {
        try {
            const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
            const page = list.find((t) => t.type === "page");
            if (page) {
                return page.webSocketDebuggerUrl;
            }
        } catch (e) {
            // przeglądarka jeszcze startuje
        }
        await sleep(200);
    }
    throw new Error("ENVIRONMENT: DevTools endpoint not reachable");
}

function connect(wsUrl) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(wsUrl);
        let id = 0;
        const pending = new Map();
        const listeners = [];
        ws.onmessage = (ev) => {
            const msg = JSON.parse(ev.data);
            if (msg.id && pending.has(msg.id)) {
                const { res, rej } = pending.get(msg.id);
                pending.delete(msg.id);
                msg.error ? rej(new Error(msg.error.message)) : res(msg.result);
            } else if (msg.method) {
                listeners.forEach((fn) => fn(msg));
            }
        };
        ws.onerror = () => reject(new Error("ENVIRONMENT: WebSocket error"));
        ws.onopen = () => resolve({
            send: (method, params = {}) => new Promise((res, rej) => {
                pending.set(++id, { res, rej });
                ws.send(JSON.stringify({ id, method, params }));
            }),
            on: (fn) => listeners.push(fn),
            close: () => ws.close()
        });
    });
}

async function evaluate(cdp, expression) {
    const r = await cdp.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
        throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    }
    return r.result.value;
}

async function waitFor(cdp, expression, label) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        try {
            if (await evaluate(cdp, expression)) {
                return;
            }
        } catch (e) {
            // strona jeszcze się ładuje
        }
        await sleep(500);
    }
    throw new Error(`ENVIRONMENT: timeout waiting for ${label}`);
}

let exitCode = 0;
try {
    const cdp = await connect(await cdpTarget());
    const errors = [];
    cdp.on((msg) => {
        if (msg.method === "Runtime.consoleAPICalled" && msg.params.args.some((a) => String(a.value ?? "").includes("Component-preload.js"))) {
            return; // standardowy brak preload w trybie dev, nie błąd aplikacji
        } else if (msg.method === "Runtime.exceptionThrown") {
            errors.push("exception: " + (msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text));
        } else if (msg.method === "Runtime.consoleAPICalled" && msg.params.type === "error") {
            errors.push("console.error: " + msg.params.args.map((a) => a.value ?? a.description ?? "").join(" "));
        }
    });
    await cdp.send("Runtime.enable");
    await cdp.send("Page.enable");
    await cdp.send("Page.navigate", { url });

    if (mode === "qunit") {
        await waitFor(cdp, "!!(window.QUnit && document.querySelector('#qunit-testresult .total'))", "QUnit result");
        const report = await evaluate(cdp, `(() => {
            const tests = [...document.querySelectorAll('#qunit-tests > li')];
            return {
                summary: document.getElementById('qunit-testresult').innerText.replace(/\\s+/g, ' ').trim(),
                total: tests.length,
                failed: tests.filter((li) => li.classList.contains('fail')).map((li) => ({
                    name: li.querySelector('.module-name')?.innerText + ' :: ' + li.querySelector('.test-name')?.innerText,
                    messages: [...li.querySelectorAll('li.fail')].map((m) => m.innerText).slice(0, 5)
                })),
                passed: tests.filter((li) => li.classList.contains('pass')).map((li) =>
                    li.querySelector('.module-name')?.innerText + ' :: ' + li.querySelector('.test-name')?.innerText)
            };
        })()`);
        report.passed.forEach((name) => console.log("PASS  " + name));
        report.failed.forEach((f) => console.log("FAIL  " + f.name + "\n      " + f.messages.join("\n      ")));
        console.log("\n" + report.summary);
        exitCode = report.failed.length === 0 && report.total > 0 ? 0 : 1;
    } else {
        await waitFor(cdp, `!!(window.sap && sap.ui && sap.ui.getCore && document.querySelector('[id$="ksefInvoiceTable"]'))`, "app render");
        await sleep(2500);
        const shot = await cdp.send("Page.captureScreenshot", { format: "png" });
        writeFileSync(arg3, Buffer.from(shot.data, "base64"));
        console.log("Screenshot: " + arg3);
    }
    if (errors.length) {
        console.log("\nBrowser errors (" + errors.length + "):\n  " + errors.slice(0, 30).join("\n  "));
        if (mode === "smoke") {
            exitCode = 1;
        }
    }
    cdp.close();
} catch (e) {
    console.error(e.message);
    exitCode = e.message.startsWith("ENVIRONMENT") ? 2 : 1;
} finally {
    browser.kill();
    await sleep(300);
    try {
        rmSync(profileDir, { recursive: true, force: true });
    } catch (e) {
        // profil tymczasowy może być jeszcze zablokowany przez przeglądarkę
    }
}
process.exit(exitCode);
