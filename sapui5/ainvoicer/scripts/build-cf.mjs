// Wariant builda SAPUI5 dla SAP BTP Cloud Foundry (CAP-07.1).
//
// Cel: wdrażana aplikacja ma CELOWO czytać RAP (dataMode=rap), a nie przypadkowo demo JSON z trybu mock.
// Źródłowy manifest developerski (dataMode=mock) pozostaje bez zmian. Skrypt:
//   1. kopiuje webapp/ do ignorowanego build/cf-webapp/ BEZ test/ i localService/ (fixture, mock metadata),
//   2. przekształca kopię manifestu (transformManifest) — rap, wszystkie capabilities=false, Legacy OFF,
//      bez early requestów do /sap i bez flex/LREP (approuter nie ma tych tras),
//   3. uruchamia standardowe `ui5 build` z ui5-cf.yaml do dist-cf/,
//   4. twardo weryfikuje artefakt (verifyArtifact): brak testów/fixture, brak credentiali, rap + Send OFF
//      także w Component-preload.js. Każde naruszenie = exit 1, bez częściowego wyniku.
// Funkcje AI/Scanner/Voice/Legacy pozostają w źródłach; CF blokuje je konfiguracją oraz CSP connect-src self.
//
// BETA-01 (opt-in, `npm run build:cf:inbox-beta`, flaga `--variant=inbox-beta`): osobny staging
// build/cf-inbox-beta-webapp/ i wynik dist-cf-inbox-beta/. Różni się WYŁĄCZNIE sekcją ksefInbox: Inbox czyta
// read-only CAP `KsefService.InboundInvoiceOverview` przez trasę AppRouter /odata/v4/KsefService/ (XSUAA).
// Kokpit RAP, Send/sync OFF, Legacy/AI OFF i CSP pozostają identyczne. Pobranie XML (`viewXml`) jest OFF, chyba że
// orkiestrator świadomie doda `--with-xml-download` po integracji backendu. Domyślne `build:cf` nie zmienia się.
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

export const CAP_SERVICE_URL = "/odata/v4/KsefService/";
export const RAP_SERVICE_URL = "/rap-ui/";
const EXCLUDED_SOURCE_DIRS = ["test", "localService"];

/** BETA-01: jedyna encja czytana przez Inbox w wariancie beta i zakres środowisk backendu v1 (TEST). */
export const INBOX_BETA_ENTITY_SET = "InboundInvoiceOverview";
export const INBOX_BETA_ENVIRONMENTS = Object.freeze(["TEST"]);
const INBOX_MAX_PAGE_SIZE = 100;
const INBOX_DEFAULT_PAGE_SIZE = 20;
const INBOX_DEFAULT_TIMEOUT_MS = 15000;
const INBOX_MAX_TIMEOUT_MS = 60000;

/** Warianty builda: osobny staging, konfiguracja ui5 i katalog wyniku (beta nie nadpisuje dist-cf/). */
export const VARIANTS = Object.freeze({
    default: Object.freeze({ stage: "cf-webapp", config: "ui5-cf.yaml", dist: "dist-cf" }),
    "inbox-beta": Object.freeze({ stage: "cf-inbox-beta-webapp", config: "ui5-cf-inbox-beta.yaml", dist: "dist-cf-inbox-beta" })
});

/**
 * Normalizacja opcji wariantu (fail-closed): nieznany wariant lub `xmlDownload` poza wariantem beta = błąd.
 * `xmlDownload` to JEDYNY przełącznik włączający `ksefInbox.capabilities.viewXml` (CLI: --with-xml-download).
 */
export function resolveBuildOptions(options = {}) {
    const variant = options.variant === undefined ? "default" : options.variant;
    if (typeof variant !== "string" || !Object.prototype.hasOwnProperty.call(VARIANTS, variant)) {
        throw new Error("unknown CF build variant: " + String(variant));
    }
    if (options.xmlDownload !== undefined && typeof options.xmlDownload !== "boolean") {
        throw new Error("xmlDownload must be a boolean");
    }
    const xmlDownload = options.xmlDownload === true;
    if (xmlDownload && variant !== "inbox-beta") {
        throw new Error("--with-xml-download is only allowed with --variant=inbox-beta");
    }
    return { variant, xmlDownload };
}

/** Argumenty CLI: wyłącznie znane flagi; każda inna wartość przerywa build (bez zgadywania). */
export function parseBuildArgs(argv) {
    const options = {};
    for (const arg of argv) {
        if (arg === "--variant=inbox-beta" || arg === "--variant=default") {
            options.variant = arg.substring("--variant=".length);
        } else if (arg === "--with-xml-download") {
            options.xmlDownload = true;
        } else {
            throw new Error("unknown build-cf argument: " + arg);
        }
    }
    return resolveBuildOptions(options);
}

/** Czysta transformacja manifestu developerskiego do wariantu CF. Rzuca błąd zamiast „naprawiać” Send ON. */
export function transformManifest(source, options) {
    const buildOptions = resolveBuildOptions(options);
    const manifest = structuredClone(source);
    const ui5 = manifest["sap.ui5"];
    const cockpit = ui5 && ui5.config && ui5.config.ksefCockpit;
    if (!cockpit) {
        throw new Error("manifest: sap.ui5/config/ksefCockpit is missing");
    }
    const enabled = Object.entries(cockpit.capabilities || {}).filter(([, value]) => value !== false);
    if (enabled.length) {
        // Źródło z włączoną mutacją oznacza błąd konfiguracji — nie publikujemy go nawet po przełączeniu.
        throw new Error("manifest: mutating capabilities must be false in source: " + enabled.map(([key]) => key).join(", "));
    }
    if (!cockpit.capabilities || cockpit.capabilities.sendInvoice !== false) {
        throw new Error("manifest: capabilities.sendInvoice must be explicitly false");
    }
    cockpit.dataMode = "rap";
    delete cockpit.mock;
    cockpit.legacy = { enabled: false };
    cockpit.externalServices = { ai: false, scanner: false, voice: false, tts: false };
    if (!cockpit.cap || cockpit.cap.serviceUrl !== CAP_SERVICE_URL) {
        throw new Error("manifest: cap.serviceUrl must be " + CAP_SERVICE_URL);
    }
    if (!cockpit.rap || cockpit.rap.dataSource !== RAP_SERVICE_URL) {
        throw new Error("manifest: rap.dataSource must be " + RAP_SERVICE_URL);
    }
    const dataSources = (manifest["sap.app"] && manifest["sap.app"].dataSources) || {};
    if (!dataSources.rapUiService || dataSources.rapUiService.uri !== RAP_SERVICE_URL) {
        throw new Error("manifest: dataSources.rapUiService.uri must be " + RAP_SERVICE_URL);
    }
    for (const dataSource of Object.values(dataSources)) {
        if (dataSource.settings) {
            delete dataSource.settings.localUri; // localService/ nie trafia do artefaktu
        }
    }
    // Model domyślny = Legacy SAP FI (/sap/...). Bez trasy w approuterze nie wysyłamy wczesnych żądań $metadata.
    const legacyModel = ui5.models && ui5.models[""];
    if (legacyModel) {
        legacyModel.preload = false;
        legacyModel.settings = Object.assign({}, legacyModel.settings, { earlyRequests: false });
    }
    // Flex/LREP (/sap/bc/lrep) nie istnieje za approuterem; personalizacja UI wyłączona w wariancie CF.
    ui5.flexEnabled = false;
    transformInboxConfig(ui5.config, buildOptions);
    return manifest;
}

export const INBOX_CAPABILITIES = ["viewXml", "checkDuplicate", "linkSapDocument", "approveAndPost"];

/** Oczekiwane capabilities Inbox: wszystko OFF; w becie `viewXml` wyłącznie przez jawne `xmlDownload`. */
export function inboxCapabilitiesFor(options) {
    const buildOptions = resolveBuildOptions(options);
    return Object.fromEntries(INBOX_CAPABILITIES.map((key) => [key,
        key === "viewXml" && buildOptions.variant === "inbox-beta" && buildOptions.xmlDownload]));
}

/**
 * UI5-10: KSeF Inbox w CF. Domyślny wariant CF jest WYŁĄCZONY (dataMode=disabled): bez ścieżki mock, bez encji
 * CAP, bez żadnego HTTP. BETA-01 (tylko `variant=inbox-beta`): read-only CAP `InboundInvoiceOverview`, bez mock,
 * bez akcji mutujących; to świadomy wariant builda, nie przełącznik w manifeście źródłowym.
 */
function transformInboxConfig(config, buildOptions) {
    const inbox = config.ksefInbox;
    const beta = buildOptions.variant === "inbox-beta";
    if (!inbox) {
        if (beta) {
            throw new Error("manifest: sap.ui5/config/ksefInbox is required for the inbox-beta variant");
        }
        return; // brak konfiguracji = adapter i tak działa w trybie disabled
    }
    const enabled = INBOX_CAPABILITIES.filter((key) => (inbox.capabilities || {})[key] !== false);
    if (enabled.length) {
        throw new Error("manifest: ksefInbox capabilities must be explicitly false in source: " + enabled.join(", "));
    }
    if (!beta) {
        config.ksefInbox = {
            dataMode: "disabled",
            pageSize: inbox.pageSize,
            environments: inbox.environments,
            cap: { serviceUrl: CAP_SERVICE_URL, entitySet: null },
            capabilities: Object.fromEntries(INBOX_CAPABILITIES.map((key) => [key, false]))
        };
        return;
    }
    if (!inbox.cap || inbox.cap.serviceUrl !== CAP_SERVICE_URL) {
        throw new Error("manifest: ksefInbox.cap.serviceUrl must be " + CAP_SERVICE_URL);
    }
    const pageSize = Number.isSafeInteger(inbox.pageSize) && inbox.pageSize > 0
        ? Math.min(inbox.pageSize, INBOX_MAX_PAGE_SIZE) : INBOX_DEFAULT_PAGE_SIZE;
    const timeout = inbox.cap.readTimeoutMs;
    // Nowy obiekt z listy białej pól: `mock` i inne klucze źródła nie przechodzą do artefaktu.
    config.ksefInbox = {
        dataMode: "cap",
        pageSize,
        environments: [...INBOX_BETA_ENVIRONMENTS],
        cap: {
            serviceUrl: CAP_SERVICE_URL,
            entitySet: INBOX_BETA_ENTITY_SET,
            readTimeoutMs: Number.isSafeInteger(timeout) && timeout > 0 ? Math.min(timeout, INBOX_MAX_TIMEOUT_MS) : INBOX_DEFAULT_TIMEOUT_MS
        },
        capabilities: inboxCapabilitiesFor(buildOptions)
    };
}

/** BETA-01: dokładna kontrola sekcji ksefInbox (manifest i manifest osadzony w Component-preload.js). */
function verifyBetaInbox(inbox, buildOptions, label) {
    const errors = [];
    const fail = (message) => errors.push(label + ": ksefInbox " + message);
    if (!inbox || typeof inbox !== "object") {
        fail("is missing");
        return errors;
    }
    const keys = Object.keys(inbox).sort();
    if (JSON.stringify(keys) !== JSON.stringify(["cap", "capabilities", "dataMode", "environments", "pageSize"])) {
        fail("has unexpected keys (mock or other): " + keys.join(","));
    }
    if (inbox.dataMode !== "cap") {
        fail("dataMode must be cap");
    }
    if (!Number.isSafeInteger(inbox.pageSize) || inbox.pageSize < 1 || inbox.pageSize > INBOX_MAX_PAGE_SIZE) {
        fail("pageSize must be 1.." + INBOX_MAX_PAGE_SIZE);
    }
    if (JSON.stringify(inbox.environments) !== JSON.stringify(INBOX_BETA_ENVIRONMENTS)) {
        fail("environments must be " + INBOX_BETA_ENVIRONMENTS.join(","));
    }
    const cap = inbox.cap || {};
    if (cap.serviceUrl !== CAP_SERVICE_URL || cap.entitySet !== INBOX_BETA_ENTITY_SET
            || JSON.stringify(Object.keys(cap).sort()) !== JSON.stringify(["entitySet", "readTimeoutMs", "serviceUrl"])
            || !Number.isSafeInteger(cap.readTimeoutMs) || cap.readTimeoutMs < 1 || cap.readTimeoutMs > INBOX_MAX_TIMEOUT_MS) {
        fail("cap must read " + CAP_SERVICE_URL + INBOX_BETA_ENTITY_SET + " with a bounded timeout");
    }
    if (JSON.stringify(inbox.capabilities) !== JSON.stringify(inboxCapabilitiesFor(buildOptions))) {
        fail("capabilities must be " + JSON.stringify(inboxCapabilitiesFor(buildOptions)));
    }
    return errors;
}

/** Manifest osadzony przez UI5 builder w Component-preload.js jako literał JS `'...'`; null = nie znaleziono. */
export function extractPreloadManifest(text) {
    const match = /"ainvoicer\/manifest\.json":'((?:[^'\\]|\\.)*)'/.exec(text);
    if (!match) {
        return null;
    }
    const decoded = match[1].replace(/\\(u[0-9a-fA-F]{4}|.)/g, (all, escape) => {
        if (escape.length === 5) {
            return String.fromCharCode(parseInt(escape.substring(1), 16));
        }
        return { n: "\n", r: "\r", t: "\t", b: "\b", f: "\f", v: "\v", 0: "\0" }[escape] ?? escape;
    });
    try {
        return JSON.parse(decoded);
    } catch {
        return null;
    }
}

const SECRET_PATTERNS = [
    [/-----BEGIN [A-Z ]*PRIVATE KEY-----/, "private key"],
    [/\bsk-[A-Za-z0-9_-]{20,}/, "API key (sk-…)"],
    [/\bBasic [A-Za-z0-9+/]{16,}={0,2}/, "Basic credential"],
    [/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/, "JWT"],
    [/clientsecret/i, "client secret"]
];
const TEXT_FILE = /\.(js|json|xml|html|properties|css|map|txt)$/i;
const EXTERNAL_HOST = /https?:\/\/(?!ui5\.sap\.com|sapui5\.hana\.ondemand\.com|www\.w3\.org|schemas\.|openui5\.org)([a-z0-9.-]+\.[a-z]{2,})/gi;

function walk(dir) {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
}

/**
 * Weryfikacja gotowego artefaktu; zwraca { errors, warnings, files }. Bez opcji = wariant domyślny (bez zmian).
 * `{ variant: "inbox-beta", xmlDownload }` dokłada dokładną kontrolę bety; reszta reguł jest wspólna.
 */
export function verifyArtifact(dir, options) {
    const buildOptions = resolveBuildOptions(options);
    const beta = buildOptions.variant === "inbox-beta";
    // Beta czyta CAP w Inbox (dataMode=cap jest oczekiwany); mock nadal zabroniony wszędzie.
    const forbiddenDataMode = beta ? /"dataMode"\s*:\s*"mock"/ : /"dataMode"\s*:\s*"(mock|cap)"/;
    // viewXml=true dopuszczalny tylko w becie z --with-xml-download i tylko w ksefInbox (sprawdzane dokładnie niżej).
    const mutatingKeys = ["sendInvoice", "syncInbound", "viewXml", "downloadUpo", "checkDuplicate", "linkSapDocument", "approveAndPost"]
        .filter((key) => !(key === "viewXml" && beta && buildOptions.xmlDownload));
    const mutatingEnabled = new RegExp('"(' + mutatingKeys.join("|") + ')"\\s*:\\s*true');
    const errors = [];
    const warnings = new Set();
    if (!existsSync(join(dir, "manifest.json")) || !existsSync(join(dir, "index.html"))) {
        errors.push("artifact: manifest.json and index.html are required");
        return { errors, warnings: [], files: 0 };
    }
    const files = walk(dir);
    for (const file of files) {
        const rel = relative(dir, file).split(sep).join("/");
        const segments = rel.split("/");
        if (segments.some((segment) => ["test", "test-resources", "localService"].includes(segment))
                || /\.qunit\.(html|js)$|testsuite|opaTests|unitTests|flp\.html$/i.test(rel)) {
            errors.push("test or fixture file in artifact: " + rel);
        }
        if (/\.json$/i.test(rel) && !/(^|\/)manifest\.json$/.test(rel) && !/sap-ui-cachebuster-info\.json$/.test(rel)) {
            errors.push("unexpected JSON (fixture?) in artifact: " + rel);
        }
        if (!TEXT_FILE.test(rel)) {
            continue;
        }
        const text = readFileSync(file, "utf8");
        for (const [pattern, label] of SECRET_PATTERNS) {
            if (pattern.test(text)) {
                errors.push(label + " pattern in " + rel);
            }
        }
        if (forbiddenDataMode.test(text)) {
            errors.push((beta ? "mock dataMode in " : "non-RAP dataMode in ") + rel);
        }
        if (mutatingEnabled.test(text)) {
            errors.push("mutating capability enabled in " + rel);
        }
        // Syntetyczne faktury Inbox (UI5-10) nigdy nie trafiają do artefaktu CF — ani plik, ani ścieżka.
        if (/InboundInvoices\.json/.test(text)) {
            errors.push("KSeF Inbox demo data reference in " + rel);
        }
        if (!/-dbg\.js$/.test(rel)) {
            for (const match of text.matchAll(EXTERNAL_HOST)) {
                warnings.add(match[1]);
            }
        }
    }
    const manifest = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8"));
    const cockpit = manifest["sap.ui5"].config.ksefCockpit;
    if (cockpit.dataMode !== "rap") {
        errors.push("manifest.json: dataMode must be rap");
    }
    if (Object.values(cockpit.capabilities || {}).some((value) => value !== false)) {
        errors.push("manifest.json: all capabilities must be false");
    }
    const inbox = manifest["sap.ui5"].config.ksefInbox;
    if (beta) {
        errors.push(...verifyBetaInbox(inbox, buildOptions, "manifest.json"));
    } else if (inbox && (inbox.dataMode !== "disabled" || inbox.mock || (inbox.cap && inbox.cap.entitySet)
            || Object.values(inbox.capabilities || {}).some((value) => value !== false))) {
        errors.push("manifest.json: ksefInbox must be disabled without mock data, CAP entity or capabilities");
    }
    if (JSON.stringify(cockpit.externalServices) !== JSON.stringify({ ai: false, scanner: false, voice: false, tts: false })) {
        errors.push("manifest.json: externalServices must all be explicitly false");
    }
    if (!readFileSync(join(dir, "index.html"), "utf8").includes(
            'http-equiv="Content-Security-Policy" content="connect-src \'self\'; form-action \'self\'; base-uri \'self\'; object-src \'none\'"')) {
        errors.push("index.html: CF same-origin connection policy is required");
    }
    const preload = join(dir, "Component-preload.js");
    if (existsSync(preload) && !/\\?"dataMode\\?"\s*:\s*\\?"rap\\?"/.test(readFileSync(preload, "utf8"))) {
        errors.push("Component-preload.js: embedded manifest is not dataMode=rap");
    }
    if (existsSync(preload)) {
        const text = readFileSync(preload, "utf8");
        const groups = [...text.matchAll(/\\?"externalServices\\?"\s*:\s*\{([^}]+)\}/g)];
        if (!groups.length || groups.some(([, group]) =>
                ["ai", "scanner", "voice", "tts"].some(name =>
                    !new RegExp('\\\\?"' + name + '\\\\?"\\s*:\\s*false(?:\\s*[,}]|\\s*$)').test(group))
                || /:\s*true/.test(group))) {
            errors.push("Component-preload.js: externalServices must all be explicitly false");
        }
    }
    if (beta) {
        // Przeglądarka startuje z manifestu w preload: ten sam kontrakt bety musi obowiązywać także tam.
        const embedded = existsSync(preload) ? extractPreloadManifest(readFileSync(preload, "utf8")) : null;
        if (!embedded) {
            errors.push("Component-preload.js: embedded manifest is required and must be valid JSON");
        } else {
            const config = (embedded["sap.ui5"] || {}).config || {};
            const embeddedCockpit = config.ksefCockpit || {};
            if (embeddedCockpit.dataMode !== "rap"
                    || Object.values(embeddedCockpit.capabilities || { missing: true }).some((value) => value !== false)) {
                errors.push("Component-preload.js: ksefCockpit must be rap with every capability false");
            }
            errors.push(...verifyBetaInbox(config.ksefInbox, buildOptions, "Component-preload.js"));
        }
        if (!existsSync(join(dir, "model", "ksef", "inbound", "InboundInboxAdapter.js"))) {
            errors.push("artifact: Inbox adapter is missing");
        }
    }
    return { errors, warnings: [...warnings].sort(), files: files.length };
}

/** Kopia źródeł bez test/ i localService/ (na poziomie webapp/). */
export function stageWebapp(webapp, stage, options) {
    rmSync(stage, { recursive: true, force: true });
    mkdirSync(stage, { recursive: true });
    cpSync(webapp, stage, {
        recursive: true,
        filter: (path) => {
            const rel = relative(webapp, path).split(sep);
            return !EXCLUDED_SOURCE_DIRS.includes(rel[0]);
        }
    });
    const manifestPath = join(stage, "manifest.json");
    const manifest = transformManifest(JSON.parse(readFileSync(manifestPath, "utf8")), options);
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + "\n");
    // Druga bariera w przeglądarce: nawet pominięta bramka JS nie połączy się ze starym hostem AI.
    const indexPath = join(stage, "index.html");
    writeFileSync(indexPath, readFileSync(indexPath, "utf8").replace('<head>',
        '<head>\n    <meta http-equiv="Content-Security-Policy" content="connect-src \'self\'; form-action \'self\'; base-uri \'self\'; object-src \'none\'">'));
    return manifest;
}

function main() {
    let buildOptions;
    try {
        buildOptions = parseBuildArgs(process.argv.slice(2));
    } catch (error) {
        console.error("FAIL " + error.message);
        process.exit(1);
    }
    const variant = VARIANTS[buildOptions.variant];
    const project = join(dirname(fileURLToPath(import.meta.url)), "..");
    const stage = join(project, "build", variant.stage);
    const dist = join(project, variant.dist);
    stageWebapp(join(project, "webapp"), stage, buildOptions);
    const ui5 = join(project, "node_modules", "@ui5", "cli", "bin", "ui5.cjs");
    const result = spawnSync(process.execPath, [ui5, "build", "--config=" + variant.config, "--clean-dest", "--dest", variant.dist],
        { cwd: project, stdio: "inherit" });
    if (result.status !== 0) {
        console.error("ui5 build failed (exit " + result.status + ")");
        process.exit(result.status || 1);
    }
    const report = verifyArtifact(dist, buildOptions);
    for (const host of report.warnings) {
        console.warn("INFO preserved external host (CF capabilities OFF, CSP blocks connections): " + host);
    }
    if (report.errors.length) {
        report.errors.forEach((error) => console.error("FAIL " + error));
        rmSync(dist, { recursive: true, force: true }); // brak częściowego artefaktu do przypadkowego wdrożenia
        process.exit(1);
    }
    if (buildOptions.variant === "default") {
        console.log("CF build PASS: " + report.files + " files in dist-cf (dataMode=rap, capabilities OFF, no tests/fixtures)");
    } else {
        console.log("CF inbox-beta build PASS: " + report.files + " files in " + variant.dist + " (cockpit rap, Inbox read-only CAP "
            + INBOX_BETA_ENTITY_SET + ", viewXml=" + buildOptions.xmlDownload + ", other capabilities OFF, no tests/fixtures)");
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    main();
}
