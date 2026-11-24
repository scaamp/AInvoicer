// BETA-02: interaktywny LOKALNY UAT właściciela — `npm run start:beta-uat` (lub `-- --smoke` do automatycznej kontroli).
// Wykorzystuje istniejące elementy: InboundLocalServer + InboundFixtures (CAP HTTP/H2 w pamięci, mock users),
// ui5-inbox.yaml (UI5 + proxy /odata → CAP) i transformację `build:cf:inbox-beta -- --with-xml-download`.
// Bez KSeF API, SAP H01, BTP/CF, HANA i sekretów; dane wyłącznie syntetyczne; tylko loopback.
// Procesy działają do Ctrl+C. Nie przejmuje zajętych portów ani nie otwiera przeglądarki.
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { readFileSync, readdirSync, openSync, closeSync, mkdirSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { once } from "node:events";

const ui = fileURLToPath(new URL("../", import.meta.url));
const root = resolve(ui, "../..");
const smoke = process.argv.includes("--smoke");
const unknown = process.argv.slice(2).filter(a => a !== "--smoke");
if (unknown.length) { console.error("Unknown arguments: " + unknown.join(" ")); process.exit(2); }
if (process.env.VCAP_APPLICATION || process.env.VCAP_SERVICES) { console.error("LOCAL UAT ONLY"); process.exit(2); }

const CAP = "http://127.0.0.1:8092";
const UI = "http://localhost:8087";
const APP = UI + "/index.html#/ksef-inbox";
const logDir = join(ui, "build", "beta-uat");
mkdirSync(logDir, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const children = new Set();

/** Classpath testowy CAP z ostatniego `mvn verify`/`mvn test` (ten sam mechanizm co test:inbox:e2e). */
function capClasspath() {
    const reports = join(root, "cap-java/srv/target/surefire-reports");
    const report = existsSync(reports) && readdirSync(reports).find(f => f.startsWith("TEST-") && f.endsWith(".xml"));
    if (!report) {
        console.error("Brak skompilowanego CAP z testami. Najpierw w cap-java/: mvn clean verify -Dcds.cds.skipVersionCheck=true");
        process.exit(2);
    }
    const xml = readFileSync(join(reports, report), "utf8");
    const encoded = xml.match(/<property name="java.class.path" value="([^"]*)"/)[1];
    return encoded.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
}

function start(command, args, name, cwd = root) {
    const fd = openSync(join(logDir, name + ".log"), "w");
    const child = spawn(command, args, { cwd, env: process.env, stdio: ["ignore", fd, fd],
        windowsHide: true, detached: process.platform !== "win32" });
    closeSync(fd);
    children.add(child);
    return child;
}

async function stopAll() {
    for (const child of [...children].reverse()) {
        children.delete(child);
        if (child.exitCode !== null) { continue; }
        const done = once(child, "exit");
        if (process.platform === "win32") { spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" }); }
        else { process.kill(-child.pid, "SIGTERM"); }
        await done;
    }
}

async function ready(url, expected, child) {
    for (let i = 0; i < 180; i++) {
        assert.equal(child.exitCode, null, "process exited; inspect " + logDir);
        try {
            const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
            if (response.status === expected) { return; }
        } catch { /* starting */ }
        await sleep(500);
    }
    throw new Error("local server did not become ready; inspect " + logDir);
}

const basic = (user, password) => ({ Authorization: "Basic " + Buffer.from(user + ":" + password).toString("base64"), "OData-MaxVersion": "4.0" });
const overview = "/odata/v4/KsefService/InboundInvoiceOverview";
const xmlOf = id => "/odata/v4/KsefService/InboundInvoiceXml(" + id + ")/content";
const OWN = "10000000-0000-4000-8000-000000000001";       // NIP A (BUYER)
const FOREIGN = "10000000-0000-4000-8000-000000000003";   // NIP B (BUYER)

/** Kontrole smoke przez proxy UI5 (ta sama ścieżka co przeglądarka); bez treści XML w wyjściu. */
async function smokeChecks() {
    const results = [];
    const check = async (name, path, headers, expected, extra) => {
        const response = await fetch(UI + path, { headers, redirect: "manual" });
        const body = Buffer.from(await response.arrayBuffer());
        const ok = response.status === expected && (!extra || extra(response, body));
        results.push([name, response.status, ok ? "PASS" : "FAIL"]);
    };
    await check("UI index", "/index.html", {}, 200);
    await check("manifest viewXml=ON (beta+XML)", "/manifest.json", {}, 200,
        (r, b) => JSON.parse(b.toString("utf8"))["sap.ui5"].config.ksefInbox.capabilities.viewXml === true);
    await check("anonymous Overview -> 401", overview, { "OData-MaxVersion": "4.0" }, 401);
    await check("user without role -> 403", overview, basic("user-without-ksef-role", "test-user"), 403);
    await check("operator A list (5 granted)", overview + "?$count=true&$top=0", basic("operator-a", "test-a"), 200,
        (r, b) => JSON.parse(b.toString("utf8"))["@odata.count"] === 5);
    await check("operator B list (2 granted)", overview + "?$count=true&$top=0", basic("operator-b", "test-b"), 200,
        (r, b) => JSON.parse(b.toString("utf8"))["@odata.count"] === 2);
    await check("operator A XML download own", xmlOf(OWN), basic("operator-a", "test-a"), 200,
        (r, b) => b.length > 0 && (r.headers.get("cache-control") || "").includes("no-store")
            && b.toString("utf8").includes("SYNTHETIC DOCUMENT, NOT FROM KSeF"));
    await check("operator A XML of B's invoice -> 404", xmlOf(FOREIGN), basic("operator-a", "test-a"), 404);
    await check("operator without NIP -> 404 XML", xmlOf(OWN), basic("operator-without-taxpayer-attribute", "test-no-nip"), 404);
    await check("storageRef not exposed", overview + "?$top=1", basic("operator-a", "test-a"), 200,
        (r, b) => !/xmlStorageRef|ksefdoc:|sha256/i.test(b.toString("utf8")));
    return results;
}

let stopping = false;
async function shutdown(code) {
    if (stopping) { return; }
    stopping = true;
    await stopAll();
    process.exit(code);
}
process.on("SIGINT", () => { console.log("\nZatrzymywanie lokalnego UAT…"); shutdown(0); });
process.on("SIGTERM", () => shutdown(0));

try {
    for (const url of [UI, CAP]) {
        let occupied = false;
        try { await fetch(url, { signal: AbortSignal.timeout(1000) }); occupied = true; } catch { /* free */ }
        assert.equal(occupied, false, "port already in use: " + url + " (zatrzymaj inny proces; launcher go nie przejmuje)");
    }
    const cp = capClasspath();
    const prepared = spawnSync(process.execPath, ["scripts/prepare-inbox-local.mjs", "--with-xml-download"], { cwd: ui, stdio: "inherit" });
    assert.equal(prepared.status, 0, "prepare-inbox-local failed");
    const backend = start("java", ["-Dspring.devtools.restart.enabled=false", "-Dainv.inbox.local.uat-xml=true", "-cp", cp,
        "customer.cap_java.security.InboundLocalServer"], "cap");
    await ready(CAP + overview, 401, backend);
    const frontend = start(process.execPath, ["node_modules/@sap/ux-ui5-tooling/bin/fiori", "run", "--config", "ui5-inbox.yaml",
        "--port", "8087"], "ui5", ui);
    await ready(UI + "/index.html", 200, frontend);
    // Seed (lista + XML) kończy się tuż po starcie HTTP — czekamy na komplet danych operatora A.
    for (let i = 0; i < 40; i++) {
        const r = await fetch(CAP + overview + "?$count=true&$top=0", { headers: basic("operator-a", "test-a") }).catch(() => null);
        if (r && r.ok && (await r.json())["@odata.count"] === 5) { break; }
        await sleep(300);
    }
    if (smoke) {
        const results = await smokeChecks();
        for (const [name, status, verdict] of results) { console.log(verdict.padEnd(5) + String(status).padEnd(5) + name); }
        const failed = results.filter(r => r[2] !== "PASS").length;
        console.log(failed ? "BETA-02 LOCAL UAT SMOKE FAIL (" + failed + ")" : "BETA-02 LOCAL UAT SMOKE PASS (" + results.length + " checks, no LIVE calls)");
        await shutdown(failed ? 1 : 0);
    } else {
        console.log([
            "",
            "AInvoicer BETA-02 — LOKALNY UAT gotowy (dane syntetyczne, H2 w pamięci; bez KSeF/SAP/BTP/HANA).",
            "  KSeF Inbox:  " + APP,
            "  Kokpit:      " + UI + "/index.html",
            "  Logowanie (okno logowania przeglądarki, Basic; każdy użytkownik w osobnym oknie prywatnym):",
            "    operator-a / test-a                              NIP 1234567802, CompanyCode 1000 — 5 faktur",
            "    operator-b / test-b                              NIP 9876543210, CompanyCode 2000 — 2 faktury",
            "    operator-without-taxpayer-attribute / test-no-nip  brak NIP — pusta lista",
            "    user-without-ksef-role / test-user               brak roli — 403",
            "  Scenariusze: docs/guides/BETA_02_MANUAL_UAT.md   Logi: " + logDir,
            "  Zatrzymanie: Ctrl+C w tym terminalu (zatrzymuje CAP i UI5).",
            "",
        ].join("\n"));
        await new Promise(() => { /* do Ctrl+C */ });
    }
} catch (error) {
    console.error("BETA-02 LOCAL UAT: " + error.message);
    await shutdown(1);
}
