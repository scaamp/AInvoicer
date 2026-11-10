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
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

export const CAP_SERVICE_URL = "/odata/v4/KsefService/";
export const RAP_SERVICE_URL = "/rap-ui/";
const EXCLUDED_SOURCE_DIRS = ["test", "localService"];

/** Czysta transformacja manifestu developerskiego do wariantu CF. Rzuca błąd zamiast „naprawiać” Send ON. */
export function transformManifest(source) {
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
    transformInboxConfig(ui5.config);
    return manifest;
}

export const INBOX_CAPABILITIES = ["viewXml", "checkDuplicate", "linkSapDocument", "approveAndPost"];

/**
 * UI5-10: KSeF Inbox w CF. Backend read-only istnieje lokalnie (INT-02); bramki trwałości/SSO nadal otwarte, więc
 * wariant CF jest WYŁĄCZONY (dataMode=disabled): bez ścieżki mock, bez encji CAP, bez żadnego HTTP.
 * Włączenie po akceptacji CF wymaga świadomej zmiany tej funkcji i testów — nie przełącznika w manifeście.
 */
function transformInboxConfig(config) {
    const inbox = config.ksefInbox;
    if (!inbox) {
        return; // brak konfiguracji = adapter i tak działa w trybie disabled
    }
    const enabled = INBOX_CAPABILITIES.filter((key) => (inbox.capabilities || {})[key] !== false);
    if (enabled.length) {
        throw new Error("manifest: ksefInbox capabilities must be explicitly false in source: " + enabled.join(", "));
    }
    config.ksefInbox = {
        dataMode: "disabled",
        pageSize: inbox.pageSize,
        environments: inbox.environments,
        cap: { serviceUrl: CAP_SERVICE_URL, entitySet: null },
        capabilities: Object.fromEntries(INBOX_CAPABILITIES.map((key) => [key, false]))
    };
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

/** Weryfikacja gotowego artefaktu; zwraca { errors, warnings, files }. */
export function verifyArtifact(dir) {
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
        if (/"dataMode"\s*:\s*"(mock|cap)"/.test(text)) {
            errors.push("non-RAP dataMode in " + rel);
        }
        if (/"(sendInvoice|syncInbound|viewXml|downloadUpo|checkDuplicate|linkSapDocument|approveAndPost)"\s*:\s*true/.test(text)) {
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
    if (inbox && (inbox.dataMode !== "disabled" || inbox.mock || (inbox.cap && inbox.cap.entitySet)
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
    return { errors, warnings: [...warnings].sort(), files: files.length };
}

/** Kopia źródeł bez test/ i localService/ (na poziomie webapp/). */
export function stageWebapp(webapp, stage) {
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
    const manifest = transformManifest(JSON.parse(readFileSync(manifestPath, "utf8")));
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + "\n");
    // Druga bariera w przeglądarce: nawet pominięta bramka JS nie połączy się ze starym hostem AI.
    const indexPath = join(stage, "index.html");
    writeFileSync(indexPath, readFileSync(indexPath, "utf8").replace('<head>',
        '<head>\n    <meta http-equiv="Content-Security-Policy" content="connect-src \'self\'; form-action \'self\'; base-uri \'self\'; object-src \'none\'">'));
    return manifest;
}

function main() {
    const project = join(dirname(fileURLToPath(import.meta.url)), "..");
    const stage = join(project, "build", "cf-webapp");
    const dist = join(project, "dist-cf");
    stageWebapp(join(project, "webapp"), stage);
    const ui5 = join(project, "node_modules", "@ui5", "cli", "bin", "ui5.cjs");
    const result = spawnSync(process.execPath, [ui5, "build", "--config=ui5-cf.yaml", "--clean-dest", "--dest", "dist-cf"],
        { cwd: project, stdio: "inherit" });
    if (result.status !== 0) {
        console.error("ui5 build failed (exit " + result.status + ")");
        process.exit(result.status || 1);
    }
    const report = verifyArtifact(dist);
    for (const host of report.warnings) {
        console.warn("INFO preserved external host (CF capabilities OFF, CSP blocks connections): " + host);
    }
    if (report.errors.length) {
        report.errors.forEach((error) => console.error("FAIL " + error));
        rmSync(dist, { recursive: true, force: true }); // brak częściowego artefaktu do przypadkowego wdrożenia
        process.exit(1);
    }
    console.log("CF build PASS: " + report.files + " files in dist-cf (dataMode=rap, capabilities OFF, no tests/fixtures)");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    main();
}
