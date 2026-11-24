// Testy wariantu builda CF (CAP-07.1): transformacja manifestu, staging bez testów/fixture i weryfikacja artefaktu.
// Bez sieci i bez BTP. Ostatni test sprawdza rzeczywisty dist-cf, jeśli `npm run build:cf` został wykonany.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
    CAP_SERVICE_URL, INBOX_BETA_ENTITY_SET, INBOX_CAPABILITIES, RAP_SERVICE_URL, VARIANTS, extractPreloadManifest, inboxCapabilitiesFor,
    parseBuildArgs, resolveBuildOptions, stageWebapp, transformManifest, verifyArtifact
} from "./build-cf.mjs";

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
        writeArtifact(dir, manifest);
        assert.deepEqual(verifyArtifact(dir).errors, []);

        mkdirSync(join(dir, "test", "unit"), { recursive: true });
        writeFileSync(join(dir, "test", "unit", "unitTests.qunit.html"), "");
        mkdirSync(join(dir, "localService", "ksef"), { recursive: true });
        writeFileSync(join(dir, "localService", "ksef", "KsefOperations.json"), "[]");
        writeFileSync(join(dir, "leak.js"), "var k = 'sk-" + "A".repeat(24) + "'; var b = 'Basic " + "QWxhZGRpbjpvcGVuIHNlc2FtZQ==" + "';");
        writeFileSync(join(dir, "test-certificate.p12"), Buffer.from([0]));
        writeFileSync(join(dir, "mock.js"), JSON.stringify({ dataMode: "mock", sendInvoice: true }));
        const errors = verifyArtifact(dir).errors.join("\n");
        assert.match(errors, /test or fixture file in artifact: test\/unit\/unitTests\.qunit\.html/);
        assert.match(errors, /localService\/ksef\/KsefOperations\.json/);
        assert.match(errors, /API key/);
        assert.match(errors, /Basic credential/);
        assert.match(errors, /credential file in artifact: test-certificate\.p12/);
        assert.match(errors, /non-RAP dataMode in mock\.js/);
        assert.match(errors, /mutating capability enabled in mock\.js/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("required files fail verification for every CF variant", () => {
    for (const options of [undefined, BETA, BETA_XML]) {
        withArtifactDir((dir) => {
            assert.match(verifyArtifact(dir, options).errors.join("\n"), /manifest.json, index.html and Component-preload.js are required/);
            writeArtifact(dir, transformManifest(sourceManifest(), options), null);
            assert.match(verifyArtifact(dir, options).errors.join("\n"), /Component-preload.js are required/);
        });
    }
});

test("UI5-10: developer manifest uses clearly configured KSeF Inbox mock data with every action OFF", () => {
    const inbox = sourceManifest()["sap.ui5"].config.ksefInbox;
    assert.equal(inbox.dataMode, "mock");
    assert.equal(inbox.cap.entitySet, null, "no CAP endpoint is assumed before INT-02");
    assert.deepEqual(Object.values(inbox.capabilities), INBOX_CAPABILITIES.map(() => false));
});

test("UI5-10: CF manifest disables the KSeF Inbox (no mock path, no CAP entity, no fallback)", () => {
    const inbox = transformManifest(sourceManifest())["sap.ui5"].config.ksefInbox;
    assert.equal(inbox.dataMode, "disabled");
    assert.equal(inbox.mock, undefined, "no demo data path in CF");
    assert.equal(inbox.cap.entitySet, null);
    assert.equal(inbox.cap.serviceUrl, CAP_SERVICE_URL);
    assert.deepEqual(inbox.capabilities, Object.fromEntries(INBOX_CAPABILITIES.map((key) => [key, false])));
    // Nawet źródło przełączone na "cap" z encją nie przechodzi do CF w tym etapie.
    const capSource = sourceManifest();
    Object.assign(capSource["sap.ui5"].config.ksefInbox, { dataMode: "cap" });
    capSource["sap.ui5"].config.ksefInbox.cap.entitySet = "InboundInvoiceOverview";
    assert.equal(transformManifest(capSource)["sap.ui5"].config.ksefInbox.dataMode, "disabled");
    assert.equal(transformManifest(capSource)["sap.ui5"].config.ksefInbox.cap.entitySet, null);
});

test("UI5-10: source with an enabled KSeF Inbox action is rejected", () => {
    for (const key of INBOX_CAPABILITIES) {
        const manifest = sourceManifest();
        manifest["sap.ui5"].config.ksefInbox.capabilities[key] = true;
        assert.throws(() => transformManifest(manifest), new RegExp("ksefInbox capabilities.*" + key));
    }
});

test("UI5-10: artifact verification rejects Inbox demo data, mock mode and enabled Inbox actions", () => {
    const dir = mkdtempSync(join(tmpdir(), "ainv-cf-inbox-"));
    try {
        const manifest = transformManifest(sourceManifest());
        writeArtifact(dir, manifest);
        assert.deepEqual(verifyArtifact(dir).errors, []);

        const mockInbox = structuredClone(manifest);
        mockInbox["sap.ui5"].config.ksefInbox = sourceManifest()["sap.ui5"].config.ksefInbox;
        writeFileSync(join(dir, "manifest.json"), JSON.stringify(mockInbox));
        writeFileSync(join(dir, "leak.js"), "var p='ainvoicer/localService/ksef/inbound/InboundInvoices.json'; var c={\"approveAndPost\": true};");
        const errors = verifyArtifact(dir).errors.join("\n");
        assert.match(errors, /ksefInbox must be disabled/);
        assert.match(errors, /KSeF Inbox demo data reference in leak\.js/);
        assert.match(errors, /mutating capability enabled in leak\.js/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("UI5-10: staged CF sources contain no Inbox fixture", () => {
    const stage = mkdtempSync(join(tmpdir(), "ainv-cf-inbox-stage-"));
    try {
        stageWebapp(join(project, "webapp"), stage);
        assert.ok(existsSync(join(stage, "controller", "InboundInbox.controller.js")), "Inbox code is shipped (disabled)");
        assert.equal(existsSync(join(stage, "localService", "ksef", "inbound")), false);
        assert.equal(JSON.parse(readFileSync(join(stage, "manifest.json"), "utf8"))["sap.ui5"].config.ksefInbox.dataMode, "disabled");
    } finally {
        rmSync(stage, { recursive: true, force: true });
    }
});

test("stale preload cannot re-enable voice or AI behind a safe manifest and CSP", () => {
    const dir = mkdtempSync(join(tmpdir(), "ainv-cf-preload-"));
    try {
        stageWebapp(join(project, "webapp"), dir);
        for (const name of ["ai", "scanner", "voice", "tts"]) {
            const manifest = transformManifest(sourceManifest());
            manifest["sap.ui5"].config.ksefCockpit.externalServices[name] = true;
            writeFileSync(join(dir, "Component-preload.js"), preloadFor(manifest));
            assert.match(verifyArtifact(dir).errors.join("\n"), /Component-preload.js: externalServices/);
        }
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

// ---------------------------------------------------------------- BETA-01: opt-in wariant Inbox beta
const CSP_META = '<meta http-equiv="Content-Security-Policy" content="connect-src \'self\'; form-action \'self\'; base-uri \'self\'; object-src \'none\'">';
const BETA = { variant: "inbox-beta" };
const BETA_XML = { variant: "inbox-beta", xmlDownload: true };
const BETA_UUID = "10000000-0000-4000-8000-000000000001";
const allOff = () => Object.fromEntries(INBOX_CAPABILITIES.map((key) => [key, false]));

/** Preload jak w UI5 builderze: manifest jako literał JS w apostrofach. */
function preloadFor(manifest) {
    return "//@ui5-bundle ainvoicer/Component-preload.js\nsap.ui.require.preload({\n\t\"ainvoicer/manifest.json\":'"
        + JSON.stringify(manifest).replace(/\\/g, "\\\\").replace(/'/g, "\\'") + "'\n});\n";
}

function writeArtifact(dir, manifest, preloadManifest = manifest) {
    writeFileSync(join(dir, "manifest.json"), JSON.stringify(manifest));
    writeFileSync(join(dir, "index.html"), CSP_META);
    mkdirSync(join(dir, "model", "ksef", "inbound"), { recursive: true });
    writeFileSync(join(dir, "model", "ksef", "inbound", "InboundInboxAdapter.js"), "sap.ui.define([], function () {});");
    if (preloadManifest) {
        writeFileSync(join(dir, "Component-preload.js"), preloadFor(preloadManifest));
    }
}

function withArtifactDir(fn) {
    const dir = mkdtempSync(join(tmpdir(), "ainv-cf-beta-"));
    try { fn(dir); } finally { rmSync(dir, { recursive: true, force: true }); }
}

test("BETA-01: default build:cf command, options and manifest stay unchanged", () => {
    const pkg = JSON.parse(readFileSync(join(project, "package.json"), "utf8"));
    assert.equal(pkg.scripts["build:cf"], "node scripts/build-cf.mjs");
    assert.equal(pkg.scripts["build:cf:inbox-beta"], "node scripts/build-cf.mjs --variant=inbox-beta");
    assert.deepEqual(parseBuildArgs([]), { variant: "default", xmlDownload: false });
    assert.deepEqual(VARIANTS.default, { stage: "cf-webapp", config: "ui5-cf.yaml", dist: "dist-cf" });
    const source = sourceManifest();
    assert.deepEqual(transformManifest(source), transformManifest(source, { variant: "default" }));
    assert.deepEqual(transformManifest(source), transformManifest(source, {}));
    assert.deepEqual(transformManifest(source)["sap.ui5"].config.ksefInbox, {
        dataMode: "disabled", pageSize: 20, environments: ["TEST", "DEMO"],
        cap: { serviceUrl: CAP_SERVICE_URL, entitySet: null }, capabilities: allOff()
    });
});

test("BETA-01: build arguments are explicit and fail closed", () => {
    assert.deepEqual(parseBuildArgs(["--variant=inbox-beta"]), { variant: "inbox-beta", xmlDownload: false });
    assert.deepEqual(parseBuildArgs(["--variant=inbox-beta", "--with-xml-download"]), { variant: "inbox-beta", xmlDownload: true });
    assert.throws(() => parseBuildArgs(["--with-xml-download"]), /only allowed with --variant=inbox-beta/);
    assert.throws(() => parseBuildArgs(["--variant=prod"]), /unknown build-cf argument/);
    assert.throws(() => parseBuildArgs(["--viewXml=true"]), /unknown build-cf argument/);
    assert.throws(() => resolveBuildOptions({ variant: "prod" }), /unknown CF build variant/);
    assert.throws(() => resolveBuildOptions({ variant: "inbox-beta", xmlDownload: "true" }), /boolean/);
    assert.throws(() => transformManifest(sourceManifest(), { xmlDownload: true }), /only allowed/);
    assert.notEqual(VARIANTS["inbox-beta"].dist, VARIANTS.default.dist, "beta never overwrites dist-cf");
    assert.notEqual(VARIANTS["inbox-beta"].stage, VARIANTS.default.stage);
    assert.match(readFileSync(join(project, VARIANTS["inbox-beta"].config), "utf8"), /webapp: build\/cf-inbox-beta-webapp/);
});

test("BETA-01: beta manifest differs from default ONLY in a read-only CAP Inbox section", () => {
    const source = sourceManifest();
    const base = transformManifest(source);
    const beta = transformManifest(source, BETA);
    const strip = (manifest) => { const copy = structuredClone(manifest); delete copy["sap.ui5"].config.ksefInbox; return copy; };
    assert.deepEqual(strip(beta), strip(base), "cockpit rap, Send/sync OFF, Legacy/AI OFF, flex OFF unchanged");
    assert.deepEqual(beta["sap.ui5"].config.ksefInbox, {
        dataMode: "cap", pageSize: 20, environments: ["TEST"],
        cap: { serviceUrl: CAP_SERVICE_URL, entitySet: "InboundInvoiceOverview", readTimeoutMs: 15000 },
        capabilities: allOff()
    });
    assert.equal(INBOX_BETA_ENTITY_SET, "InboundInvoiceOverview");
    const xml = transformManifest(source, BETA_XML)["sap.ui5"].config.ksefInbox;
    assert.deepEqual(xml.capabilities, { ...allOff(), viewXml: true }, "--with-xml-download enables ONLY viewXml");
    assert.deepEqual(inboxCapabilitiesFor(BETA_XML), { ...allOff(), viewXml: true });
    assert.deepEqual(inboxCapabilitiesFor({ variant: "default" }), allOff());
    assert.deepEqual(transformManifest(source, BETA_XML)["sap.ui5"].config.ksefCockpit.capabilities,
        base["sap.ui5"].config.ksefCockpit.capabilities, "cockpit viewXml stays false");
});

test("BETA-01: beta transform rejects unsafe sources and never carries mock data", () => {
    for (const key of INBOX_CAPABILITIES) {
        const manifest = sourceManifest();
        manifest["sap.ui5"].config.ksefInbox.capabilities[key] = true;
        assert.throws(() => transformManifest(manifest, BETA_XML), new RegExp("ksefInbox capabilities.*" + key));
    }
    const noInbox = sourceManifest();
    delete noInbox["sap.ui5"].config.ksefInbox;
    assert.throws(() => transformManifest(noInbox, BETA), /required for the inbox-beta variant/);
    const foreign = sourceManifest();
    foreign["sap.ui5"].config.ksefInbox.cap.serviceUrl = "https://cap.example.invalid/odata/v4/KsefService/";
    assert.throws(() => transformManifest(foreign, BETA), /ksefInbox.cap.serviceUrl/);
    const big = sourceManifest();
    Object.assign(big["sap.ui5"].config.ksefInbox, { pageSize: 5000, environments: ["PROD"], extra: "x" });
    big["sap.ui5"].config.ksefInbox.cap.readTimeoutMs = 999999;
    const inbox = transformManifest(big, BETA)["sap.ui5"].config.ksefInbox;
    assert.equal(inbox.pageSize, 100);
    assert.deepEqual(inbox.environments, ["TEST"], "never PROD");
    assert.equal(inbox.cap.readTimeoutMs, 60000);
    assert.equal(inbox.mock, undefined);
    assert.equal(inbox.extra, undefined);
    // Mutująca capability kokpitu nadal przerywa build także w becie.
    const send = sourceManifest();
    send["sap.ui5"].config.ksefCockpit.capabilities.sendInvoice = true;
    assert.throws(() => transformManifest(send, BETA_XML), /sendInvoice/);
});

test("BETA-01: Inbox reads and XML download use the existing XSUAA AppRouter route to CAP (no new route)", () => {
    const routeFor = (path) => xsApp.routes.find((route) => new RegExp(route.source).test(path));
    for (const path of [CAP_SERVICE_URL + "InboundInvoiceOverview?$top=20&$count=true",
        CAP_SERVICE_URL + "InboundInvoiceOverview(" + BETA_UUID + ")",
        CAP_SERVICE_URL + "InboundInvoiceXml(" + BETA_UUID + ")/content"]) {
        const route = routeFor(path);
        assert.equal(route.destination, "ainvoicer-cap", path);
        assert.equal(route.authenticationType, "xsuaa", path);
        assert.equal(route.csrfProtection, true, "CSRF for modifying methods; GET reads need no token");
        assert.equal(path.replace(new RegExp(route.source), route.target), path, "CAP receives the same OData path");
    }
    assert.deepEqual(xsApp.routes.filter((route) => route.authenticationType === "none").map((route) => route.source),
        ["^/resources/(.*)$"], "only UI5 resources are anonymous");
});

test("BETA-01: staged beta sources contain no tests/fixtures and the beta Inbox config", () => {
    const stage = mkdtempSync(join(tmpdir(), "ainv-cf-beta-stage-"));
    try {
        stageWebapp(join(project, "webapp"), stage, BETA);
        assert.equal(existsSync(join(stage, "test")), false);
        assert.equal(existsSync(join(stage, "localService")), false);
        const manifest = JSON.parse(readFileSync(join(stage, "manifest.json"), "utf8"));
        assert.equal(manifest["sap.ui5"].config.ksefCockpit.dataMode, "rap");
        assert.equal(manifest["sap.ui5"].config.ksefInbox.dataMode, "cap");
        assert.match(readFileSync(join(stage, "index.html"), "utf8"), /connect-src 'self'/);
    } finally {
        rmSync(stage, { recursive: true, force: true });
    }
});

test("BETA-01: preload manifest extraction reads the UI5 bundle literal", () => {
    const manifest = transformManifest(sourceManifest(), BETA);
    manifest["sap.app"].title = "It's \"quoted\" \\ path";
    assert.deepEqual(extractPreloadManifest(preloadFor(manifest)), manifest);
    assert.equal(extractPreloadManifest("sap.ui.require.preload({});"), null);
    assert.equal(extractPreloadManifest("\"ainvoicer/manifest.json\":'{broken'"), null);
});

test("BETA-01: beta verifier accepts the exact beta artifact and each variant rejects the other", () => {
    withArtifactDir((dir) => {
        writeArtifact(dir, transformManifest(sourceManifest(), BETA));
        assert.deepEqual(verifyArtifact(dir, BETA).errors, []);
        const asDefault = verifyArtifact(dir).errors.join("\n");
        assert.match(asDefault, /non-RAP dataMode in manifest\.json/);
        assert.match(asDefault, /ksefInbox must be disabled/);
        assert.match(verifyArtifact(dir, BETA_XML).errors.join("\n"), /ksefInbox capabilities must be/);
    });
    withArtifactDir((dir) => {
        writeArtifact(dir, transformManifest(sourceManifest(), BETA_XML));
        assert.deepEqual(verifyArtifact(dir, BETA_XML).errors, []);
        const withoutFlag = verifyArtifact(dir, BETA).errors.join("\n");
        assert.match(withoutFlag, /mutating capability enabled in manifest\.json/);
        assert.match(withoutFlag, /ksefInbox capabilities must be/);
    });
    withArtifactDir((dir) => {
        writeArtifact(dir, transformManifest(sourceManifest()));
        assert.deepEqual(verifyArtifact(dir).errors, [], "default artifact still passes the default verifier");
        assert.match(verifyArtifact(dir, BETA).errors.join("\n"), /ksefInbox dataMode must be cap/);
    });
});

test("BETA-01: beta verifier rejects mock, PROD, foreign entity, enabled actions and a tampered preload", () => {
    const beta = () => transformManifest(sourceManifest(), BETA_XML);
    const cases = [
        [(m) => { m["sap.ui5"].config.ksefInbox.mock = { invoicesPath: "x.json" }; }, /unexpected keys/],
        [(m) => { m["sap.ui5"].config.ksefInbox.environments = ["TEST", "PROD"]; }, /environments must be TEST/],
        [(m) => { m["sap.ui5"].config.ksefInbox.cap.entitySet = "KsefInboundAccessGrants"; }, /cap must read/],
        [(m) => { m["sap.ui5"].config.ksefInbox.cap.serviceUrl = "https://evil.example/odata/"; }, /cap must read/],
        [(m) => { m["sap.ui5"].config.ksefInbox.pageSize = 1000; }, /pageSize/],
        [(m) => { m["sap.ui5"].config.ksefInbox.dataMode = "mock"; }, /mock dataMode in manifest\.json/],
        [(m) => { m["sap.ui5"].config.ksefInbox.capabilities.approveAndPost = true; }, /mutating capability enabled/],
        [(m) => { m["sap.ui5"].config.ksefCockpit.capabilities.viewXml = true; }, /all cockpit capabilities must be false/],
        [(m) => { m["sap.ui5"].config.ksefCockpit.dataMode = "cap"; }, /dataMode must be rap/]
    ];
    for (const [mutate, expected] of cases) {
        withArtifactDir((dir) => {
            const manifest = beta();
            mutate(manifest);
            writeArtifact(dir, manifest);
            assert.match(verifyArtifact(dir, BETA_XML).errors.join("\n"), expected);
        });
    }
    withArtifactDir((dir) => {
        const stale = beta();
        stale["sap.ui5"].config.ksefInbox.cap.entitySet = "InboundInvoices";
        writeArtifact(dir, beta(), stale);
        assert.match(verifyArtifact(dir, BETA_XML).errors.join("\n"), /Component-preload\.js: ksefInbox cap must read/);
    });
    withArtifactDir((dir) => {
        const stale = beta();
        stale["sap.ui5"].config.ksefCockpit.capabilities.sendInvoice = true;
        writeArtifact(dir, beta(), stale);
        const errors = verifyArtifact(dir, BETA_XML).errors.join("\n");
        assert.match(errors, /mutating capability enabled in Component-preload\.js/);
        assert.match(errors, /Component-preload\.js: all cockpit capabilities must be false/);
    });
    withArtifactDir((dir) => {
        writeArtifact(dir, beta());
        writeFileSync(join(dir, "Component-preload.js"), "invalid embedded manifest");
        assert.match(verifyArtifact(dir, BETA_XML).errors.join("\n"), /embedded manifest is required/);
    });
    withArtifactDir((dir) => {
        writeArtifact(dir, beta());
        rmSync(join(dir, "model"), { recursive: true, force: true });
        mkdirSync(join(dir, "localService", "ksef", "inbound"), { recursive: true });
        writeFileSync(join(dir, "localService", "ksef", "inbound", "InboundInvoices.json"), "{\"value\":[]}");
        writeFileSync(join(dir, "mock.js"), "var p='ainvoicer/localService/ksef/inbound/InboundInvoices.json';");
        const errors = verifyArtifact(dir, BETA_XML).errors.join("\n");
        assert.match(errors, /test or fixture file in artifact: localService/);
        assert.match(errors, /KSeF Inbox demo data reference in mock\.js/);
        assert.match(errors, /Inbox adapter is missing/);
    });
});

test("default CF rejects a beta Inbox or missing send/sync flags in the embedded manifest", () => {
    withArtifactDir((dir) => {
        const manifest = transformManifest(sourceManifest());
        writeArtifact(dir, manifest, transformManifest(sourceManifest(), BETA));
        assert.match(verifyArtifact(dir).errors.join("\n"), /Component-preload\.js: ksefInbox must be disabled/);
        for (const key of ["sendInvoice", "syncInbound"]) {
            const stale = structuredClone(manifest);
            delete stale["sap.ui5"].config.ksefCockpit.capabilities[key];
            writeArtifact(dir, manifest, stale);
            assert.match(verifyArtifact(dir).errors.join("\n"), /Component-preload\.js: all cockpit capabilities must be false/);
        }
    });
});
