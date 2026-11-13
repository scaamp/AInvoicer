// CAP-HANA-02 — katalogowa weryfikacja kontenera HDI WYŁĄCZNIE do odczytu.
//
// Cel: potwierdzić obecność tabel, widoków i indeksów wygenerowanych przez CAP (`cds build --for hana`)
// w już wdrożonym HDI `ainvoicer-db`, bez service key, bez DBADMIN, bez nowej usługi/bindingu
// i bez restartu CAP. Kod jest wykonywany jako jednorazowy CF task na ISTNIEJĄCYM droplecie
// `ainvoicer-db-deployer` (Node 24 + sterownik `hdb` + istniejący binding HDI z VCAP_SERVICES).
//
// Zakres: jedno zapytanie SELECT do widoków katalogowych SYS (także INDEX_COLUMNS/TABLE_COLUMNS),
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

// CORE: zatwierdzony kontrakt 14/4/6. LAB ma osobny, jawny kontrakt poniżej.
// Źródło: artefakty hdbtable/hdbview/hdbindex modelu main=4d4dbfc, nie pomiar LIVE.
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

// Klucze i kolumny odczytane z wygenerowanego design-time; identyfikatory katalogowe są case-sensitive.
const LAB_TABLE = 'CUSTOMER_CAP_JAVA_KSEFDOCUMENTS';
const PRIMARY_KEYS = Object.freeze(Object.fromEntries([...EXPECTED.TABLE, LAB_TABLE].map(table => [table,
    table.endsWith('KSEFSENDRESERVATIONS') ? ['INVOICEUUID', 'ENVIRONMENT']
        : /KSEFINBOUNDSYNC(CHECKPOINTS|LEASES)$/.test(table) ? ['ENVIRONMENT', 'CONTEXTNIP', 'SUBJECTROLE'] : ['ID']])));
// [typ, długość NVARCHAR lub null (brak ograniczenia w DDL), nullable]. BLOB nigdy nie jest odczytywany.
const LAB_COLUMNS = Object.freeze({
    ID: ['NVARCHAR', 36, 'FALSE'], CREATEDAT: ['TIMESTAMP', null, 'TRUE'], CREATEDBY: ['NVARCHAR', 255, 'TRUE'],
    MODIFIEDAT: ['TIMESTAMP', null, 'TRUE'], MODIFIEDBY: ['NVARCHAR', 255, 'TRUE'], FILENAME: ['NVARCHAR', 255, 'TRUE'],
    MIMETYPE: ['NVARCHAR', 100, 'TRUE'], CONTENT: ['BLOB', null, 'TRUE'], SHA256: ['NVARCHAR', 64, 'TRUE'],
});

// Jedyne polecenie SQL; pięć parametrów schematu. DETAIL ma stały format metadanych, bez danych biznesowych.
const CATALOG_SQL = "SELECT 'TABLE' AS KIND, TABLE_NAME AS NAME, '-' AS DETAIL, SCHEMA_NAME FROM SYS.TABLES WHERE SCHEMA_NAME = ?"
    + " UNION ALL SELECT 'VIEW', VIEW_NAME, '-', SCHEMA_NAME FROM SYS.VIEWS WHERE SCHEMA_NAME = ?"
    + " UNION ALL SELECT 'INDEX', INDEX_NAME, IFNULL(\"CONSTRAINT\", '-') || '@' || TABLE_NAME, SCHEMA_NAME FROM SYS.INDEXES WHERE SCHEMA_NAME = ?"
    + " UNION ALL SELECT 'PK_COLUMN', TABLE_NAME, TO_NVARCHAR(POSITION) || ':' || COLUMN_NAME, SCHEMA_NAME FROM SYS.INDEX_COLUMNS WHERE SCHEMA_NAME = ? AND \"CONSTRAINT\" IN ('PRIMARY KEY', 'PRIMARY_KEY')"
    + " UNION ALL SELECT 'COLUMN', COLUMN_NAME, DATA_TYPE_NAME || ':' || IFNULL(TO_NVARCHAR(LENGTH), '-') || ':' || IS_NULLABLE, SCHEMA_NAME FROM SYS.TABLE_COLUMNS WHERE SCHEMA_NAME = ? AND TABLE_NAME = '" + LAB_TABLE + "'"
    + ' ORDER BY 1, 2, 3';

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
function evaluate(rows, schema, coreOnly = false) {
    const missing = [], unexpected = [];
    const bad = code => unexpected.push(code);
    const seen = new Set();
    const data = { TABLE: [], VIEW: [], INDEX: [], PK_COLUMN: [], COLUMN: [] };
    if (!Array.isArray(rows) || !schema || typeof schema !== 'string') bad('INVALID_CATALOG');
    for (const row of Array.isArray(rows) ? rows : []) {
        if (!row || !Object.hasOwn(data, row.KIND) || typeof row.NAME !== 'string'
            || !/^[A-Za-z0-9_$#.-]{1,256}$/.test(row.NAME) || typeof row.DETAIL !== 'string') {
            bad('INVALID_ROW'); continue;
        }
        if (row.SCHEMA_NAME !== schema) { bad('SCHEMA_MISMATCH'); continue; }
        const key = row.KIND + ':' + row.NAME + (row.KIND === 'PK_COLUMN' ? ':' + row.DETAIL.split(':')[0] : '');
        if (seen.has(key)) bad('DUPLICATE:' + row.KIND);
        seen.add(key);
        data[row.KIND].push(row);
    }
    const compare = (label, actual, expected) => {
        for (const name of expected) if (!actual.includes(name)) missing.push(label + ':' + name);
        for (const name of actual) if (!expected.includes(name)) bad(label + ':' + name);
    };
    const tables = [...EXPECTED.TABLE, ...(coreOnly ? [] : [LAB_TABLE])];
    compare('TABLE', data.TABLE.map(r => r.NAME), tables);
    compare('VIEW', data.VIEW.map(r => r.NAME), EXPECTED.VIEW);
    for (const row of [...data.TABLE, ...data.VIEW]) if (row.DETAIL !== '-') bad('INVALID_OBJECT_DETAIL');
    const pkTables = [], uniques = [];
    for (const row of data.INDEX) {
        const [constraint, table, extra] = row.DETAIL.split('@');
        const kind = constraint.replaceAll('_', ' ');
        if (extra !== undefined || !tables.includes(table)) { bad('INDEX_TARGET'); continue; }
        if (kind === 'PRIMARY KEY') pkTables.push(table);
        else if (kind === 'UNIQUE' || kind === 'NOT NULL UNIQUE') {
            uniques.push(row.NAME);
            if (row.NAME.slice(0, row.NAME.lastIndexOf('_')) !== table) bad('INDEX_TARGET');
        } else bad('INDEX:' + row.NAME);
    }
    compare('UNIQUE_INDEX', uniques, EXPECTED.UNIQUE_INDEX);
    for (const table of tables) {
        const keys = data.PK_COLUMN.filter(r => r.NAME === table).sort((a, b) => Number(a.DETAIL.split(':')[0]) - Number(b.DETAIL.split(':')[0]));
        if (pkTables.filter(t => t === table).length !== 1) missing.push('PRIMARY_KEY:' + table);
        if (JSON.stringify(keys.map(r => r.DETAIL)) !== JSON.stringify(PRIMARY_KEYS[table].map((c, i) => (i + 1) + ':' + c))) {
            missing.push('PK_COLUMNS:' + table);
        }
    }
    for (const row of data.PK_COLUMN) if (!tables.includes(row.NAME)) bad('PK_TABLE');
    compare('COLUMN', data.COLUMN.map(r => r.NAME), coreOnly ? [] : Object.keys(LAB_COLUMNS));
    for (const row of data.COLUMN) {
        const expected = LAB_COLUMNS[row.NAME];
        const [type, length, nullable, extra] = row.DETAIL.split(':');
        if (!expected || extra !== undefined || type !== expected[0] || nullable !== expected[2]
            || (expected[1] !== null && length !== String(expected[1]))
            || !/^(0|[1-9][0-9]*|-)$/.test(length)) bad('COLUMN_DEFINITION:' + row.NAME);
    }
    const labIssue = item => item.endsWith(':' + LAB_TABLE) || /^(COLUMN|COLUMN_DEFINITION):/.test(item);
    const issues = [...missing, ...unexpected];
    return {
        verdict: issues.length ? 'MISMATCH' : 'MATCH',
        core: issues.some(i => !labIssue(i)) ? 'FAIL' : 'PASS',
        ownerLab: coreOnly ? 'NOT_REQUESTED' : issues.some(i => labIssue(i) || /^(INVALID|SCHEMA|DUPLICATE|INDEX_TARGET|PK_TABLE)/.test(i)) ? 'FAIL' : 'PASS',
        tables: data.TABLE.length, views: data.VIEW.length, uniqueIndexes: uniques.length,
        primaryKeys: pkTables.length, expectedTables: tables.length,
        missing: missing.sort(), unexpected: unexpected.sort(),
    };
}

function summaryLine(result) {
    return `HDI_CATALOG verdict=${result.verdict} tables=${result.tables}/${result.expectedTables}`
        + ` views=${result.views}/${EXPECTED.VIEW.length} uniqueIndexes=${result.uniqueIndexes}/${EXPECTED.UNIQUE_INDEX.length}`
        + ` primaryKeys=${result.primaryKeys}/${result.expectedTables}`
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
function run(env = process.env, hdbModule, log = console.log, timeoutMs = 60000, coreOnly = false) {
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
            if (done) return;
            if (connectError) { log(safeError('CONNECT', connectError)); finish(1); return; }
            client.prepare(CATALOG_SQL, (prepareError, statement) => {
                if (done) return;
                if (prepareError) { log(safeError('PREPARE', prepareError)); finish(1); return; }
                const schema = credentials.schema;
                statement.exec(Array(5).fill(schema), (execError, rows) => {
                    if (done) return;
                    if (execError) { log(safeError('SELECT', execError)); finish(1); return; }
                    const result = evaluate(rows, schema, coreOnly);
                    log(summaryLine(result));
                    log(`HDI_CATALOG_CORE=${result.core} HDI_CATALOG_OWNER_LAB=${result.ownerLab} evidence=SCRIPT_MEASURED`);
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
function runtimeSource(coreOnly = false) {
    const compositeKeys = Object.fromEntries(Object.entries(PRIMARY_KEYS).filter(([, cols]) => cols.length > 1));
    return [
        `const HDI_NAME=${JSON.stringify(HDI_NAME)};`,
        `const EXPECTED=${JSON.stringify(EXPECTED)};`,
        `const CATALOG_SQL=${JSON.stringify(CATALOG_SQL)};`,
        `const LAB_TABLE=${JSON.stringify(LAB_TABLE)},PRIMARY_KEYS=Object.fromEntries([...EXPECTED.TABLE,LAB_TABLE].map(t=>[t,(${JSON.stringify(compositeKeys)})[t]||["ID"]])),LAB_COLUMNS=${JSON.stringify(LAB_COLUMNS)};`,
        ...[selectBinding, clientOptions, evaluate, summaryLine, safeError, run].map(String),
        `module.exports={run,main:()=>run(process.env,undefined,console.log,60000,${coreOnly}).then(code=>{process.exitCode=code;})};`,
    ].join('\n').replace(/\r\n/g, '\n')
        .replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '').replace(/^\s+/gm, '');
    // Usuwamy wyłącznie komentarze/indentację znanych funkcji; test wykonuje dokładnie ten program.
}

/**
 * Treść POST /v3/apps/<deployer-guid>/tasks. Brotli+base64 usuwa problem cytowania (bash w kontenerze,
 * PowerShell/Git Bash na laptopie) i mieści program w limicie długości polecenia.
 */
function taskRequest(coreOnly = false) {
    const encoded = zlib.brotliCompressSync(Buffer.from(runtimeSource(coreOnly), 'utf8'),
        { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11 } }).toString('base64');
    return {
        name: TASK_NAME,
        command: `node -e 'eval(require("zlib").brotliDecompressSync(Buffer.from("${encoded}","base64")).toString());module.exports.main()'`,
        memory_in_mb: 256,
        disk_in_mb: 512,
    };
}

function sha256(text) {
    return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

/** Import offline zawiera surowe metadane, nigdy liczniki/verdict. Nie dowodzi sesji LIVE. */
function fromConsole(value, schema, coreOnly = false) {
    const fail = () => { throw new Error('HDI_CATALOG_CONSOLE_INVALID'); };
    if (!value || Object.keys(value).sort().join(',') !== 'rows,version' || value.version !== 1
        || !Array.isArray(value.rows) || !schema || typeof schema !== 'string') fail();
    for (const row of value.rows) {
        if (!row || Object.keys(row).sort().join(',') !== 'DETAIL,KIND,NAME,SCHEMA_NAME'
            || Object.values(row).some(v => typeof v !== 'string')) fail();
    }
    return evaluate(value.rows, schema, coreOnly);
}

module.exports = { PRIMARY_KEYS, LAB_TABLE, LAB_COLUMNS, EXPECTED, CATALOG_SQL, HDI_NAME, TASK_NAME, MAX_TASK_COMMAND_LENGTH, selectBinding, clientOptions,
    evaluate, summaryLine, safeError, run, runtimeSource, taskRequest, sha256, fromConsole };

// Tryb laptopa: wyłącznie wygenerowanie treści żądania / podglądu programu; brak połączenia z CF/HANA.
if (require.main === module) {
    try {
        const coreOnly = process.argv.includes('--core-only');
        const args = process.argv.slice(2).filter(a => a !== '--core-only');
        if (args.length === 1 && args[0] === '--task-json') {
            const request = taskRequest(coreOnly);
            if (request.command.length > MAX_TASK_COMMAND_LENGTH) throw new Error('HDI_CATALOG_TASK_COMMAND_TOO_LONG');
            process.stdout.write(JSON.stringify(request) + '\n');
            process.stderr.write(`runtimeSourceSha256=${sha256(runtimeSource(coreOnly))} commandLength=${request.command.length}\n`);
        } else if (args.length === 1 && args[0] === '--print-runtime-source') {
            process.stdout.write(runtimeSource(coreOnly) + '\n');
        } else if (args.length === 1 && args[0] === '--print-console-sql') {
            process.stdout.write(CATALOG_SQL.replaceAll('?', 'CURRENT_SCHEMA') + ';\n');
        } else if (args.length === 4 && args[0] === '--from-console' && args[2] === '--schema') {
            const fs = require('node:fs');
            if (fs.statSync(args[1]).size > 5 * 1024 * 1024) throw new Error('HDI_CATALOG_CONSOLE_INVALID');
            const result = fromConsole(JSON.parse(fs.readFileSync(args[1], 'utf8').replace(/^\uFEFF/, '')), args[3], coreOnly);
            console.log(summaryLine(result));
            console.log(`HDI_CATALOG_CORE=${result.core} HDI_CATALOG_OWNER_LAB=${result.ownerLab} evidence=OWNER-REPORTED`);
            process.exitCode = result.verdict === 'MATCH' ? 0 : 2;
        } else {
            process.stderr.write('Usage: node deploy/scripts/hdi-catalog-readonly.cjs [--core-only] --task-json | --print-runtime-source | --print-console-sql | --from-console <json> --schema <expected-schema>\n');
            process.exitCode = 1;
        }
    } catch (error) {
        console.error(safeError('OFFLINE', error));
        process.exitCode = 1;
    }
}
