// CAP-HANA-02 — katalogowa weryfikacja kontenera HDI WYŁĄCZNIE do odczytu.
//
// Cel: potwierdzić obecność tabel, widoków i indeksów wygenerowanych przez CAP (`cds build --for hana`)
// w już wdrożonym HDI `ainvoicer-db`, bez service key, bez DBADMIN, bez nowej usługi/bindingu
// i bez restartu CAP. Kod jest wykonywany jako jednorazowy CF task na ISTNIEJĄCYM droplecie
// `ainvoicer-db-deployer` (Node 24 + sterownik `hdb` + istniejący binding HDI z VCAP_SERVICES).
//
// Zakres: jedno zapytanie SELECT do widoków katalogowych SYS.TABLES / SYS.VIEWS / SYS.INDEXES,
// ograniczone parametrem do schematu kontenera z bindingu. Brak DML/DDL/CALL, brak odczytu danych
// biznesowych. Wynik zawiera wyłącznie nazwy obiektów i liczniki; nigdy hosta, schematu, użytkownika,
// hasła, certyfikatu ani komunikatu błędu serwera (może zawierać host) — tylko kod/SQLState.
//
// Uruchomienie na laptopie (offline, bez połączenia z HANA) jedynie generuje treść żądania CAPI
// albo pokazuje dokładny program wysyłany do taska (do review):
//   node deploy/scripts/hdi-catalog-readonly.cjs --task-json > <scratch>/hdi-catalog-task.json
//   node deploy/scripts/hdi-catalog-readonly.cjs --print-runtime-source
// Wykonanie taska jest osobną decyzją właściciela (patrz runbook CAP-HANA-02).

const crypto = require('node:crypto');
const zlib = require('node:zlib');

const HDI_NAME = 'ainvoicer-db';
const TASK_NAME = 'cap-hana-02-hdi-catalog-readonly';

// Nazwy katalogowe (identyfikatory bez cudzysłowów → HANA uppercase) z `cds build --for hana --production`
// modelu wdrożonego w 0.7.3 (model bez zmian między fe0a0c1 a bd38156). Zmiana modelu = aktualizacja listy.
const EXPECTED = Object.freeze({
    TABLE: Object.freeze([
        'CDS_OUTBOX_MESSAGES',
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDACCESSGRANTS',
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDDOCUMENTS',
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDINTEGRITYCONFLICTS',
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDSYNCCHECKPOINTS',
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDSYNCLEASES',
        'CUSTOMER_CAP_JAVA_KSEFINVOICES',
        'CUSTOMER_CAP_JAVA_KSEFINVOICESNAPSHOTS',
        'CUSTOMER_CAP_JAVA_KSEFOPERATIONS',
        'CUSTOMER_CAP_JAVA_KSEFSENDRESERVATIONS',
        'CUSTOMER_CAP_JAVA_KSEFSUBMISSIONHISTORY',
        'CUSTOMER_CAP_JAVA_KSEFSUBMISSIONS',
        'CUSTOMER_CAP_JAVA_SAPRESULTWRITEHISTORY',
        'CUSTOMER_CAP_JAVA_SAPRESULTWRITES',
    ]),
    VIEW: Object.freeze([
        'KSEFSERVICE_INBOUNDINVOICEOVERVIEW',
        'KSEFSERVICE_INVOICEINTEGRATIONSTATUS',
        'KSEFSERVICE_KSEFINVOICES',
        'KSEFSERVICE_KSEFOPERATIONS',
    ]),
    UNIQUE_INDEX: Object.freeze([
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDACCESSGRANTS_SCOPE',
        'CUSTOMER_CAP_JAVA_KSEFINBOUNDDOCUMENTS_IDENTITY',
        'CUSTOMER_CAP_JAVA_KSEFINVOICESNAPSHOTS_DOCUMENT',
        'CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY',
        'CUSTOMER_CAP_JAVA_KSEFSUBMISSIONS_SNAPSHOT',
        'CUSTOMER_CAP_JAVA_SAPRESULTWRITES_KSEFNUMBER',
    ]),
});

// Jedyne polecenie SQL. Trzy parametry = schemat kontenera z bindingu (nie jest wypisywany).
const CATALOG_SQL = "SELECT 'TABLE' AS KIND, TABLE_NAME AS NAME, '-' AS DETAIL FROM SYS.TABLES WHERE SCHEMA_NAME = ?"
    + " UNION ALL SELECT 'VIEW', VIEW_NAME, '-' FROM SYS.VIEWS WHERE SCHEMA_NAME = ?"
    + " UNION ALL SELECT 'INDEX', INDEX_NAME, IFNULL(\"CONSTRAINT\", '-') || '@' || TABLE_NAME FROM SYS.INDEXES WHERE SCHEMA_NAME = ?"
    + ' ORDER BY 1, 2';

/** Fail-closed: dokładnie jeden zarządzany binding HDI o oczekiwanej nazwie; DBADMIN zabroniony. */
function selectBinding(env) {
    if (env.GITHUB_ACTIONS === 'true') throw new Error('HDI_CATALOG_CI_FORBIDDEN');
    let services;
    try { services = JSON.parse(env.VCAP_SERVICES || '{}'); } catch { throw new Error('HDI_CATALOG_BINDING_INVALID'); }
    const hana = Object.values(services).flat().filter(binding => binding && binding.label === 'hana');
    if (hana.length !== 1) throw new Error('HDI_CATALOG_EXACTLY_ONE_HDI_BINDING_REQUIRED');
    const [binding] = hana;
    const credentials = binding.credentials || {};
    if (binding.plan !== 'hdi-shared' || binding.name !== HDI_NAME) throw new Error('HDI_CATALOG_UNEXPECTED_BINDING');
    if (!credentials.schema || !credentials.user || !credentials.password || !credentials.host || !credentials.port) {
        throw new Error('HDI_CATALOG_BINDING_INCOMPLETE');
    }
    if (/^DBADMIN$/i.test(credentials.user)) throw new Error('HDI_CATALOG_DBADMIN_FORBIDDEN');
    return credentials;
}

/** Opcje klienta `hdb` jak w @sap/hdi: certificate → ca, encrypt → useTLS; walidacja certyfikatu nigdy wyłączona. */
function clientOptions(credentials) {
    const options = { host: credentials.host, port: Number(credentials.port), user: credentials.user,
        password: credentials.password, useTLS: credentials.encrypt !== false };
    if (credentials.certificate) {
        options.ca = Array.isArray(credentials.certificate) ? credentials.certificate : [credentials.certificate];
    }
    return options;
}

/** Porównanie katalogu z oczekiwaniami. Każda rozbieżność (brak lub nadmiar) = MISMATCH. */
function evaluate(rows) {
    const names = kind => rows.filter(row => row.KIND === kind).map(row => String(row.NAME));
    const tables = names('TABLE');
    const views = names('VIEW');
    const indexes = rows.filter(row => row.KIND === 'INDEX').map(row => ({ name: String(row.NAME), detail: String(row.DETAIL) }));
    const uniqueIndexes = indexes.filter(index => /UNIQUE/.test(index.detail.split('@')[0])
        && !/PRIMARY KEY/.test(index.detail)).map(index => index.name);
    const primaryKeyTables = new Set(indexes.filter(index => index.detail.startsWith('PRIMARY KEY@'))
        .map(index => index.detail.slice('PRIMARY KEY@'.length)));
    const missing = [];
    const unexpected = [];
    const compare = (label, actual, expected) => {
        for (const name of expected) if (!actual.includes(name)) missing.push(`${label}:${name}`);
        for (const name of actual) if (!expected.includes(name)) unexpected.push(`${label}:${name}`);
    };
    compare('TABLE', tables, EXPECTED.TABLE);
    compare('VIEW', views, EXPECTED.VIEW);
    compare('UNIQUE_INDEX', uniqueIndexes, EXPECTED.UNIQUE_INDEX);
    for (const table of EXPECTED.TABLE) if (!primaryKeyTables.has(table)) missing.push(`PRIMARY_KEY:${table}`);
    return {
        verdict: missing.length === 0 && unexpected.length === 0 ? 'MATCH' : 'MISMATCH',
        tables: tables.length, views: views.length, uniqueIndexes: uniqueIndexes.length,
        primaryKeys: primaryKeyTables.size, missing, unexpected,
    };
}

function summaryLine(result) {
    return `HDI_CATALOG verdict=${result.verdict} tables=${result.tables}/${EXPECTED.TABLE.length}`
        + ` views=${result.views}/${EXPECTED.VIEW.length} uniqueIndexes=${result.uniqueIndexes}/${EXPECTED.UNIQUE_INDEX.length}`
        + ` primaryKeys=${result.primaryKeys}/${EXPECTED.TABLE.length}`
        + ` missing=[${result.missing.join(',')}] unexpected=[${result.unexpected.join(',')}]`;
}

/** Bezpieczny opis błędu: tylko etap, kod i SQLState; komunikat serwera pomijany (może zawierać host). */
function safeError(stage, error) {
    const code = error && /^-?[0-9]{1,6}$/.test(String(error.code)) ? error.code : 'n/a';
    const sqlState = error && /^[A-Z0-9]{5}$/.test(String(error.sqlState)) ? error.sqlState : 'n/a';
    const local = error && /^HDI_CATALOG_[A-Z_]+$/.test(String(error.message)) ? ` reason=${error.message}` : '';
    return `HDI_CATALOG_ERROR stage=${stage} code=${code} sqlState=${sqlState}${local}`;
}

/**
 * Wykonanie w tasku CF. `hdbModule` i `log` wstrzykiwane w testach; domyślnie sterownik `hdb` z dropletu deployera.
 * Zwraca kod wyjścia: 0 MATCH, 2 MISMATCH, 1 błąd/odmowa.
 */
function run(env = process.env, hdbModule, log = console.log, timeoutMs = 60000) {
    return new Promise(resolve => {
        let credentials;
        try { credentials = selectBinding(env); } catch (error) { log(safeError('BINDING', error)); resolve(1); return; }
        let client;
        try { client = (hdbModule || require('hdb')).createClient(clientOptions(credentials)); }
        catch (error) { log(safeError('CLIENT', error)); resolve(1); return; }
        let done = false;
        const finish = code => {
            if (done) return;
            done = true;
            clearTimeout(timer);
            try { client.end(); } catch { /* zamknięcie best effort */ }
            resolve(code);
        };
        // Brak zawieszonego taska: twardy limit czasu, potem zamknięcie połączenia i kod 1.
        const timer = setTimeout(() => { if (!done) { log(safeError('TIMEOUT', null)); finish(1); } }, timeoutMs);
        if (typeof client.on === 'function') client.on('error', () => { /* raportowane przez callback */ });
        client.connect(connectError => {
            if (connectError) { log(safeError('CONNECT', connectError)); finish(1); return; }
            client.prepare(CATALOG_SQL, (prepareError, statement) => {
                if (prepareError) { log(safeError('PREPARE', prepareError)); finish(1); return; }
                const schema = credentials.schema;
                statement.exec([schema, schema, schema], (execError, rows) => {
                    if (execError) { log(safeError('SELECT', execError)); finish(1); return; }
                    const result = evaluate(Array.isArray(rows) ? rows : []);
                    log(summaryLine(result));
                    finish(result.verdict === 'MATCH' ? 0 : 2);
                });
            });
        });
    });
}

// Limit bezpieczeństwa długości polecenia taska (CAPI ogranicza rozmiar `command`); test pilnuje marginesu.
const MAX_TASK_COMMAND_LENGTH = 4096;

/**
 * Program wykonywany w tasku: dokładnie te same funkcje co powyżej (Function#toString) + stałe.
 * Bez trybu laptopa, bez odczytu plików i bez innych modułów niż `hdb`.
 */
function runtimeSource() {
    return [
        `const HDI_NAME=${JSON.stringify(HDI_NAME)};`,
        `const EXPECTED=${JSON.stringify(EXPECTED)};`,
        `const CATALOG_SQL=${JSON.stringify(CATALOG_SQL)};`,
        ...[selectBinding, clientOptions, evaluate, summaryLine, safeError, run].map(String),
        'module.exports={run,main:()=>run().then(code=>{process.exitCode=code;})};',
    ].join('\n').replace(/\r\n/g, '\n'); // Ten sam SHA niezależnie od checkoutu CRLF/LF.
}

/**
 * Treść POST /v3/apps/<deployer-guid>/tasks. gzip+base64 usuwa problem cytowania (bash w kontenerze,
 * PowerShell/Git Bash na laptopie) i mieści program w limicie długości polecenia.
 */
function taskRequest() {
    const encoded = zlib.gzipSync(Buffer.from(runtimeSource(), 'utf8'), { level: 9 }).toString('base64');
    return {
        name: TASK_NAME,
        command: `node -e 'eval(require("zlib").gunzipSync(Buffer.from("${encoded}","base64")).toString());module.exports.main()'`,
        memory_in_mb: 256,
        disk_in_mb: 512,
    };
}

function sha256(text) {
    return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

module.exports = { EXPECTED, CATALOG_SQL, HDI_NAME, TASK_NAME, MAX_TASK_COMMAND_LENGTH, selectBinding, clientOptions,
    evaluate, summaryLine, safeError, run, runtimeSource, taskRequest, sha256 };

// Tryb laptopa: wyłącznie wygenerowanie treści żądania / podglądu programu; brak połączenia z CF/HANA.
if (require.main === module) {
    if (process.argv.includes('--task-json')) {
        const request = taskRequest();
        if (request.command.length > MAX_TASK_COMMAND_LENGTH) throw new Error('HDI_CATALOG_TASK_COMMAND_TOO_LONG');
        process.stdout.write(JSON.stringify(request) + '\n');
        process.stderr.write(`runtimeSourceSha256=${sha256(runtimeSource())} commandLength=${request.command.length}\n`);
    } else if (process.argv.includes('--print-runtime-source')) {
        process.stdout.write(runtimeSource() + '\n');
    } else {
        process.stderr.write('Usage: node deploy/scripts/hdi-catalog-readonly.cjs --task-json | --print-runtime-source\n');
        process.exitCode = 1;
    }
}
