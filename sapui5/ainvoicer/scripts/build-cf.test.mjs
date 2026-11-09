// Testy wariantu builda CF (CAP-07.1): transformacja manifestu, staging bez testów/fixture i weryfikacja artefaktu.
// Bez sieci i bez BTP. Ostatni test sprawdza rzeczywisty dist-cf, jeśli `npm run build:cf` został wykonany.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CAP_SERVICE_URL, RAP_SERVICE_URL, stageWebapp, transformManifest, verifyArtifact } from "./build-cf.mjs";

const project = join(dirname(fileURLToPath(import.meta.url)), "..");
const sourceManifest = () => JSON.parse(readFileSync(join(project, "webapp", "manifest.json"), "utf8"));
const xsApp = JSON.parse(readFileSync(join(project, "..", "..", "approuter", "xs-app.json"), "utf8"));

test("developer manifest stays mock with Send OFF (CF variant does not change the source default)", () => {
    const cockpit = sourceManifest()["sap.ui5"].config.ksefCockpit;
    assert.equal(cockpit.dataMode, "mock");
    assert.equal(cockpit.capabilities.sendInvoice, false);
});

test("CF manifest reads RAP deliberately, keeps every mutating capability OFF and disables Legacy/flex", () => {
    const manifest = transformManifest(sourceManifest());
    const cockpit = manifest["sap.ui5"].config.ksefCockpit;
    assert.equal(cockpit.dataMode, "rap");
    assert.deepEqual(Object.values(cockpit.capabilities), Object.values(cockpit.capabilities).map(() => false));
    assert.equal(cockpit.mock, undefined, "no demo JSON paths");
    assert.deepEqual(cockpit.legacy, { enabled: false });
    assert.deepEqual(cockpit.externalServices, { ai: false, scanner: false, voice: false, tts: false });
    assert.equal(manifest["sap.ui5"].flexEnabled, false);
    assert.equal(manifest["sap.ui5"].models[""].preload, false);
    assert.equal(manifest["sap.ui5"].models[""].settings.earlyRequests, false);
    for (const dataSource of Object.values(manifest["sap.app"].dataSources)) {
        assert.equal(dataSource.settings.localUri, undefined);
    }
});

test("CF manifest paths match approuter routes (CAP with XSUAA token, RAP via business-user destination)", () => {
    const routeFor = (path) => xsApp.routes.find((route) => new RegExp(route.source).test(path));
    assert.equal(routeFor(CAP_SERVICE_URL + "InvoiceIntegrationStatus").destination, "ainvoicer-cap");
    assert.equal(routeFor(RAP_SERVICE_URL + "Invoices").destination, "AINV_H01_UI_USER");
    // Legacy /sap nie ma trasy backendu — trafia do statycznego katalogu (404), stąd legacy.enabled=false.
    assert.equal(routeFor("/sap/opu/odata4/sap/zui_fi_acdoca_o4/srvd/sap/zui_fi_acdoca_o4/0001/$metadata").localDir, "resources");
});

test("source with an enabled mutating capability is rejected, not silently switched off", () => {
    for (const key of ["sendInvoice", "syncInbound", "viewXml", "downloadUpo", "checkDuplicate"]) {
        const manifest = sourceManifest();
        manifest["sap.ui5"].config.ksefCockpit.capabilities[key] = true;
        assert.throws(() => transformManifest(manifest), new RegExp(key));
    }
    const missing = sourceManifest();
    delete missing["sap.ui5"].config.ksefCockpit.capabilities.sendInvoice;
    assert.throws(() => transformManifest(missing), /sendInvoice must be explicitly false/);
    const wrongCap = sourceManifest();
    wrongCap["sap.ui5"].config.ksefCockpit.cap.serviceUrl = "https://cap.example.invalid/odata/v4/KsefService/";
    assert.throws(() => transformManifest(wrongCap), /cap.serviceUrl/);
});

test("staging copies sources without test/ and localService/", () => {
    const stage = mkdtempSync(join(tmpdir(), "ainv-cf-stage-"));
    try {
        stageWebapp(join(project, "webapp"), stage);
        assert.ok(existsSync(join(stage, "Component.js")));
        assert.ok(existsSync(join(stage, "controller", "Main.controller.js")), "AI/Scanner/Voice/Legacy code preserved");
        assert.equal(existsSync(join(stage, "test")), false);
        assert.equal(existsSync(join(stage, "localService")), false);
        assert.equal(JSON.parse(readFileSync(join(stage, "manifest.json"), "utf8"))["sap.ui5"].config.ksefCockpit.dataMode, "rap");
        assert.match(readFileSync(join(stage, "index.html"), "utf8"), /connect-src 'self'/);
    } finally {
        rmSync(stage, { recursive: true, force: true });
    }
});

test("artifact verification rejects tests, fixtures, credentials and mock mode", () => {
    const dir = mkdtempSync(join(tmpdir(), "ainv-cf-artifact-"));
    try {
        const manifest = transformManifest(sourceManifest());
        writeFileSync(join(dir, "manifest.json"), JSON.stringify(manifest));
        writeFileSync(join(dir, "index.html"), '<meta http-equiv="Content-Security-Policy" content="connect-src \'self\'; form-action \'self\'; base-uri \'self\'; object-src \'none\'">');
        assert.deepEqual(verifyArtifact(dir).errors, []);

        mkdirSync(join(dir, "test", "unit"), { recursive: true });
        writeFileSync(join(dir, "test", "unit", "unitTests.qunit.html"), "");
        mkdirSync(join(dir, "localService", "ksef"), { recursive: true });
        writeFileSync(join(dir, "localService", "ksef", "KsefOperations.json"), "[]");
        writeFileSync(join(dir, "leak.js"), "var k = 'sk-" + "A".repeat(24) + "'; var b = 'Basic " + "QWxhZGRpbjpvcGVuIHNlc2FtZQ==" + "';");
        writeFileSync(join(dir, "mock.js"), JSON.stringify({ dataMode: "mock", sendInvoice: true }));
        const errors = verifyArtifact(dir).errors.join("\n");
        assert.match(errors, /test or fixture file in artifact: test\/unit\/unitTests\.qunit\.html/);
        assert.match(errors, /localService\/ksef\/KsefOperations\.json/);
        assert.match(errors, /API key/);
        assert.match(errors, /Basic credential/);
        assert.match(errors, /non-RAP dataMode in mock\.js/);
        assert.match(errors, /mutating capability enabled in mock\.js/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("built dist-cf (if present) passes the same verification", { skip: !existsSync(join(project, "dist-cf")) }, () => {
    const report = verifyArtifact(join(project, "dist-cf"));
    assert.deepEqual(report.errors, []);
    assert.ok(report.files > 10);
});

test("stale preload cannot re-enable voice or AI behind a safe manifest and CSP", () => {
    const dir = mkdtempSync(join(tmpdir(), "ainv-cf-preload-"));
    try {
        stageWebapp(join(project, "webapp"), dir);
        for (const name of ["ai", "scanner", "voice", "tts"]) {
            const manifest = transformManifest(sourceManifest());
            manifest["sap.ui5"].config.ksefCockpit.externalServices[name] = true;
            writeFileSync(join(dir, "Component-preload.js"), JSON.stringify(manifest));
            assert.match(verifyArtifact(dir).errors.join("\n"), /Component-preload.js: externalServices/);
        }
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
