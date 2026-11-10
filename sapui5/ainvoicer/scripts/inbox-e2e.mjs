// Odtwarzalny, wyłącznie lokalny test: rzeczywiste CAP/H2 i HTTP UI5, bez SDK/KSeF/SAP/BTP.
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { readFileSync, readdirSync, openSync, closeSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
const ui = fileURLToPath(new URL("../", import.meta.url));
const root = resolve(ui, "../..");
assert.ok(!process.env.VCAP_APPLICATION && !process.env.VCAP_SERVICES, "local harness only");
const reports = join(root, "cap-java/srv/target/surefire-reports");
const report = readdirSync(reports).find(f => f.startsWith("TEST-") && f.endsWith(".xml"));
const xml = readFileSync(join(reports, report), "utf8");
const encoded = xml.match(/<property name="java.class.path" value="([^"]*)"/)[1];
const cp = encoded.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
const logDir = join(ui, "build/inbox-e2e");
mkdirSync(logDir, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const children = new Set();
function start(command, args, name, cwd = root) {
    const fd = openSync(join(logDir, name + ".log"), "w");
    const child = spawn(command, args, { cwd, env: process.env, stdio: ["ignore", fd, fd],
        windowsHide: true, detached: process.platform !== "win32" });
    closeSync(fd);
    children.add(child);
    return child;
}
async function stop(child) {
    if (!children.delete(child) || child.exitCode !== null) { return; }
    const done = once(child, "exit");
    if (process.platform === "win32") { spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" }); }
    else { process.kill(-child.pid, "SIGTERM"); }
    await done;
}
async function ready(url, expected, child) {
    for (let i = 0; i < 120; i++) {
        assert.equal(child.exitCode, null, "server exited; inspect " + logDir);
        try {
            const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
            if (response.status === expected) { return; }
        } catch { /* starting */ }
        await sleep(500);
    }
    throw new Error("local server did not become ready; inspect " + logDir);
}
async function browser(page, user, password) {
    const env = { ...process.env };
    delete env.AINV_CAP_LOCAL_TEST_USER;
    delete env.AINV_CAP_LOCAL_TEST_PASSWORD;
    env.AINV_CAP_LOCAL_CANCEL_AUTH = "true";
    if (user) { env.AINV_CAP_LOCAL_TEST_USER = user; env.AINV_CAP_LOCAL_TEST_PASSWORD = password; }
    const child = spawn(process.execPath, ["scripts/browser-test.mjs", "qunit", "http://localhost:8087/test/integration/" + page],
        { cwd: ui, env, windowsHide: true, stdio: "inherit" });
    const [status] = await once(child, "exit");
    assert.equal(status, 0, "browser test failed: " + page);
}
function cap() { return start("java", ["-Dspring.devtools.restart.enabled=false", "-cp", cp,
    "customer.cap_java.security.InboundLocalServer"], "cap"); }
try {
    // Nie przejmujemy procesów użytkownika na stałych portach.
    for (const port of [8087, 8092]) {
        let occupied = false;
        try { await fetch("http://" + (port === 8087 ? "localhost" : "127.0.0.1") + ":" + port, { signal: AbortSignal.timeout(1000) }); occupied = true; } catch { /* free */ }
        assert.equal(occupied, false, "port " + port + " is already occupied");
    }
    const prepared = spawnSync(process.execPath, ["scripts/prepare-inbox-local.mjs"], { cwd: ui, stdio: "inherit" });
    assert.equal(prepared.status, 0);
    let backend = cap();
    await ready("http://127.0.0.1:8092/odata/v4/KsefService/InboundInvoiceOverview", 401, backend);
    const frontend = start(process.execPath, ["node_modules/@sap/ux-ui5-tooling/bin/fiori", "run", "--config", "ui5-inbox.yaml", "--port", "8087"], "ui5", ui);
    await ready("http://localhost:8087/test/integration/inboundCap.qunit.html", 200, frontend);
    // Poczekaj na zakończenie seed: HTTP 401 potwierdza serwer, ale fixture powstają tuż po starcie.
    const auth = "Basic " + Buffer.from("operator-a:test-a").toString("base64");
    let seeded = false;
    for (let i = 0; i < 30; i++) {
        const r = await fetch("http://127.0.0.1:8092/odata/v4/KsefService/InboundInvoiceOverview?$count=true", { headers: { Authorization: auth, "OData-MaxVersion": "4.0" } });
        if (r.ok && (await r.json())["@odata.count"] === 5) { seeded = true; break; }
        await sleep(300);
    }
    assert.ok(seeded, "synthetic staging not ready");
    await browser("inboundCap.qunit.html", "operator-a", "test-a");
    for (const [scenario, user, password] of [["b", "operator-b", "test-b"], ["no-grant", "operator-without-taxpayer-attribute", "test-no-nip"],
            ["no-role", "user-without-ksef-role", "test-user"], ["anonymous", null, null]]) {
        await browser("inboundCapAccess.qunit.html?scenario=" + scenario, user, password);
    }
    await stop(backend);
    await browser("inboundCapAccess.qunit.html?scenario=outage", "operator-a", "test-a");
    backend = cap();
    await ready("http://127.0.0.1:8092/odata/v4/KsefService/InboundInvoiceOverview", 401, backend);
    await browser("inboundCap.qunit.html", "operator-a", "test-a");
    console.log("INT-02 real CAP/UI5 HTTP PASS: A/B, grants, 401/403/404, outage and restart; no LIVE calls");
} finally {
    for (const child of [...children].reverse()) { await stop(child); }
}
