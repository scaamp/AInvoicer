// KSEF-STOR-04R — wersjonowany walidator katalogu HDI PO zatwierdzonym release tabeli KsefStoredDocuments.
//
// Kontrakt: deploy/contracts/hdi-catalog-stor04.v1.json — dokładnie 16 TABLE / 5 VIEW / 6 UNIQUE / 16 PK,
// pełne PK, 9 kolumn LAB i 8 kolumn storage (typ, długość NVARCHAR, nullability). Brak wildcardów,
// brak „15 albo 16”, brak MISSING=PASS i brak autodetekcji kontraktu z liczby obiektów.
//
// Stary walidator deploy/scripts/hdi-catalog-readonly.cjs (15/5/6/15) pozostaje BEZ ZMIAN i jest jedynym
// poprawnym G1 dla katalogu sprzed deployu. Wybór kontraktu = wybór pliku przez operatora w zatwierdzonym etapie
// release (CURRENT G1 → HDI DEPLOY → NEW G1). Ten plik współdzieli z nim niezmienione funkcje runtime
// (binding, klient TLS, kontrola widoku LAB, bezpieczne błędy, `run`) przez Function#toString, więc kontrole LAB
// i BlobService są tymi samymi, zrecenzowanymi instrukcjami. Własne są wyłącznie: stałe kontraktu, SQL
// z kwalifikowanymi kolumnami dwóch tabel, `evaluate` i `summaryLine`.
//
// Laptop (offline, bez połączenia CF/HANA):
//   node deploy/scripts/hdi-catalog-stor04.cjs --task-json > <scratch>/hdi-catalog-stor04-task.json
//   node deploy/scripts/hdi-catalog-stor04.cjs --print-runtime-source
//   node deploy/scripts/hdi-catalog-stor04.cjs --print-console-sql
//   node deploy/scripts/hdi-catalog-stor04.cjs --from-console <json> --schema <oczekiwany-schemat-HDI>
// Wykonanie taska jest osobną decyzją właściciela (docs/guides/KSEF_STOR_04R_HANA_RELEASE_READINESS.md).

const zlib = require('node:zlib');
const path = require('node:path');
const fs = require('node:fs');
const base = require('./hdi-catalog-readonly.cjs');

const CONTRACT_FILE = path.join(__dirname, '..', 'contracts', 'hdi-catalog-stor04.v1.json');
const TASK_NAME = 'ksef-stor-04-hdi-catalog-readonly';
const STOR_TABLE = 'CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS';
const { HDI_NAME, LAB_TABLE, LAB_VIEW, LAB_VIEW_COLUMNS, MAX_TASK_COMMAND_LENGTH, sha256 } = base;

/** Ścisłe wczytanie kontraktu: dokładne klucze, liczności, PK każdej tabeli i 8 kolumn storage. */
function loadContract(value = JSON.parse(fs.readFileSync(CONTRACT_FILE, 'utf8'))) {
    const fail = () => { throw new Error('HDI_CATALOG_CONTRACT_INVALID'); };
    const keys = ['executable', 'id', 'primaryKeys', 'status', 'storageColumns', 'storageDdlSha256', 'tables',
        'uniqueIndexes', 'validator', 'version', 'views'];
    if (!value || Object.keys(value).sort().join(',') !== keys.join(',')) fail();
    if (value.version !== 1 || value.id !== 'STOR04-CATALOG-16-5-6-16' || value.executable !== true
        || value.validator !== 'deploy/scripts/hdi-catalog-stor04.cjs' || !/^[0-9a-f]{64}$/.test(value.storageDdlSha256)) fail();
    const names = list => Array.isArray(list) && list.every(n => typeof n === 'string' && /^[A-Z0-9_]{1,128}$/.test(n))
        && new Set(list).size === list.length;
    if (!names(value.tables) || !names(value.views) || !names(value.uniqueIndexes)) fail();
    if (value.tables.length !== 16 || value.views.length !== 5 || value.uniqueIndexes.length !== 6) fail();
    if (!value.tables.includes(STOR_TABLE) || !value.tables.includes(LAB_TABLE) || !value.views.includes(LAB_VIEW)) fail();
    // Stary kontrakt CORE+LAB jest podzbiorem: release dodaje wyłącznie jedną tabelę.
    const old = [...base.EXPECTED.TABLE, LAB_TABLE];
    if (JSON.stringify([...value.tables].sort()) !== JSON.stringify([...old, STOR_TABLE].sort())) fail();
    if (JSON.stringify([...value.views].sort()) !== JSON.stringify([...base.EXPECTED.VIEW, LAB_VIEW].sort())) fail();
    if (JSON.stringify(value.uniqueIndexes) !== JSON.stringify(base.EXPECTED.UNIQUE_INDEX)) fail();
    const pk = value.primaryKeys;
    if (!pk || JSON.stringify(Object.keys(pk).sort()) !== JSON.stringify([...value.tables].sort())) fail();
    for (const table of value.tables) if (!names(pk[table]) || pk[table].length < 1) fail();
    for (const table of old) if (JSON.stringify(pk[table]) !== JSON.stringify(base.PRIMARY_KEYS[table])) fail();
    if (JSON.stringify(pk[STOR_TABLE]) !== JSON.stringify(['ENVIRONMENT', 'CHANNEL', 'OWNERSEGMENT', 'SHA256'])) fail();
    const columns = value.storageColumns;
    const storageNames = ['CHANNEL', 'CONTENT', 'ENVIRONMENT', 'MIMETYPE', 'OWNERSEGMENT', 'SHA256', 'SIZEBYTES', 'STOREDAT'];
    if (!columns || JSON.stringify(Object.keys(columns).sort()) !== JSON.stringify(storageNames)) fail();
    for (const [name, spec] of Object.entries(columns)) {
        if (!/^[A-Z0-9_]{1,128}$/.test(name) || !Array.isArray(spec) || spec.length !== 3
            || !['NVARCHAR', 'BIGINT', 'TIMESTAMP', 'BLOB'].includes(spec[0]) || spec[2] !== false
            || (spec[0] === 'NVARCHAR' ? !(Number.isInteger(spec[1]) && spec[1] > 0) : spec[1] !== null)) fail();
    }
    for (const key of pk[STOR_TABLE]) if (!columns[key]) fail();
    return value;
}

const CONTRACT = loadContract();
const EXPECTED = Object.freeze({ TABLE: Object.freeze([...CONTRACT.tables]), VIEW: Object.freeze([...CONTRACT.views]),
    UNIQUE_INDEX: Object.freeze([...CONTRACT.uniqueIndexes]) });
const PRIMARY_KEYS = Object.freeze(Object.fromEntries(Object.entries(CONTRACT.primaryKeys).map(([t, c]) => [t, Object.freeze([...c])])));
// Klucz kolumny = `<TABELA>.<KOLUMNA>` (nazwy katalogowe); nullable w zapisie katalogu SYS ('TRUE'/'FALSE').
const COLUMNS = Object.freeze(Object.fromEntries([
    ...Object.entries(base.LAB_COLUMNS).map(([c, [type, length, nullable]]) => [LAB_TABLE + '.' + c, [type, length, nullable]]),
    ...Object.entries(CONTRACT.storageColumns).map(([c, [type, length]]) => [STOR_TABLE + '.' + c, [type, length, 'FALSE']]),
]));
const CONTRACT_ID = CONTRACT.id;

// Jedyne polecenie SQL; nadal pięć parametrów schematu (wymóg niezmienionego `run`). Sekcja COLUMN jest
// kwalifikowana nazwą tabeli, bo LAB i storage mają wspólne nazwy kolumn (MIMETYPE, CONTENT, SHA256).
const CATALOG_SQL = base.CATALOG_SQL.replace(
    "SELECT 'COLUMN', COLUMN_NAME, ",
    "SELECT 'COLUMN', TABLE_NAME || '.' || COLUMN_NAME, ",
).replace("AND TABLE_NAME = '" + LAB_TABLE + "'", "AND TABLE_NAME IN ('" + LAB_TABLE + "', '" + STOR_TABLE + "')");
if (CATALOG_SQL === base.CATALOG_SQL || (CATALOG_SQL.match(/\?/g) || []).length !== 5
    || !CATALOG_SQL.includes("TABLE_NAME || '.' || COLUMN_NAME") || !CATALOG_SQL.includes(STOR_TABLE)) {
    throw new Error('HDI_CATALOG_SQL_DERIVATION_FAILED');
}

/**
 * Porównanie katalogu z kontraktem STOR-04 jako multizbiorów kanonicznych wpisów: każdy wiersz katalogu
 * daje dokładnie określone wpisy, a kontrakt — oczekiwaną listę. Brak = `missing`, nadmiar/duplikat/zła
 * definicja = `unexpected`; dowolna rozbieżność = MISMATCH. Te same kontrole co G1 15/5/6/15 (nazwy, schemat,
 * duplikaty, jeden PK na tabelę, kolejność kolumn PK, docelowa tabela UNIQUE, definicja widoku LAB, typ/
 * długość NVARCHAR/nullability kolumn) plus 8 kolumn storage. Trzeci parametr (historyczny coreOnly z `run`)
 * jest niedozwolony: ten kontrakt nie ma trybów częściowych.
 */
function evaluate(rows, schema, partial) {
    const got = [], counts = { TABLE: 0, VIEW: 0, UNIQUE: 0, PK: 0 }, tables = EXPECTED.TABLE;
    if (partial || !Array.isArray(rows) || !schema || typeof schema !== 'string') got.push('INVALID_CATALOG');
    for (const row of Array.isArray(rows) ? rows : []) {
        if (!row || typeof row.NAME !== 'string' || !/^[A-Za-z0-9_$#.-]{1,256}$/.test(row.NAME) || typeof row.DETAIL !== 'string') { got.push('INVALID_ROW'); continue; }
        if (row.SCHEMA_NAME !== schema) { got.push('SCHEMA_MISMATCH'); continue; }
        const name = row.NAME, detail = row.DETAIL, kind = row.KIND;
        if (kind === 'TABLE' || kind === 'VIEW') {
            counts[kind]++;
            got.push(kind + ':' + name);
            if (kind === 'VIEW' && name === LAB_VIEW ? !validLabView(detail, schema) : detail !== '-') got.push('DEFINITION:' + name);
        } else if (kind === 'INDEX') {
            const [constraint, table, extra] = detail.split('@'), type = constraint.replaceAll('_', ' ');
            if (extra !== undefined || !tables.includes(table)) got.push('INDEX_TARGET');
            else if (type === 'PRIMARY KEY') { counts.PK++; got.push('PRIMARY_KEY:' + table); }
            else if (type === 'UNIQUE' || type === 'NOT NULL UNIQUE') {
                counts.UNIQUE++;
                got.push('UNIQUE_INDEX:' + name);
                if (name.slice(0, name.lastIndexOf('_')) !== table) got.push('INDEX_TARGET');
            } else got.push('INDEX:' + name);
        } else if (kind === 'PK_COLUMN') got.push('PK_COLUMN:' + name + ':' + detail);
        else if (kind === 'COLUMN') {
            const spec = COLUMNS[name], [type, length, nullable, extra] = detail.split(':');
            got.push(spec && extra === undefined && type === spec[0] && nullable === spec[2] && /^(0|[1-9][0-9]*|-)$/.test(length)
                && (spec[1] === null || length === String(spec[1])) ? 'COLUMN:' + name : 'COLUMN_DEFINITION:' + name);
        } else got.push('INVALID_ROW');
    }
    const expected = [...tables.map(t => 'TABLE:' + t), ...EXPECTED.VIEW.map(v => 'VIEW:' + v),
        ...EXPECTED.UNIQUE_INDEX.map(i => 'UNIQUE_INDEX:' + i), ...tables.map(t => 'PRIMARY_KEY:' + t),
        ...tables.flatMap(t => PRIMARY_KEYS[t].map((c, i) => 'PK_COLUMN:' + t + ':' + (i + 1) + ':' + c)),
        ...Object.keys(COLUMNS).map(c => 'COLUMN:' + c)];
    const left = new Map();
    for (const item of expected) left.set(item, (left.get(item) || 0) + 1);
    const unexpected = got.filter(item => left.get(item) > 0 ? !left.set(item, left.get(item) - 1) : true);
    const missing = expected.filter(item => left.get(item) > 0 && left.set(item, left.get(item) - 1));
    // Przypisanie do zakresu po nazwie obiektu (drugi segment); wpisy bez obiektu są globalne (psują wszystko).
    const owner = item => (item.split(':')[1] || '').split('.')[0], issues = [...missing, ...unexpected];
    const status = (...names) => issues.some(i => !owner(i) || names.includes(owner(i))) ? 'FAIL' : 'PASS';
    return {
        verdict: issues.length ? 'MISMATCH' : 'MATCH',
        core: issues.some(i => ![LAB_TABLE, STOR_TABLE, LAB_VIEW].includes(owner(i))) ? 'FAIL' : 'PASS',
        ownerLab: status(LAB_TABLE), ownerLabOData: status(LAB_TABLE, LAB_VIEW), storage: status(STOR_TABLE),
        tables: counts.TABLE, views: counts.VIEW, uniqueIndexes: counts.UNIQUE, primaryKeys: counts.PK,
        missing: missing.sort(), unexpected: unexpected.sort(),
    };
}

function summaryLine(result) {
    const tables = EXPECTED.TABLE.length;
    return `HDI_CATALOG contract=${CONTRACT_ID} verdict=${result.verdict} tables=${result.tables}/${tables}`
        + ` views=${result.views}/${EXPECTED.VIEW.length} uniqueIndexes=${result.uniqueIndexes}/${EXPECTED.UNIQUE_INDEX.length}`
        + ` primaryKeys=${result.primaryKeys}/${tables} storage=${result.storage}`
        + ` missing=[${result.missing.join(',')}] unexpected=[${result.unexpected.join(',')}]`;
}

// Wspólne, niezmienione funkcje starego G1 (te same bajty źródła co w jego zrecenzowanym runtime).
const { selectBinding, clientOptions, validLabView, safeError, run: baseRun } = base;

/** Ten sam `run` co w G1 15/5/6/15, ale z tym modułem jako źródłem `evaluate`/`summaryLine`/SQL. */
function run(env = process.env, hdbModule, log = console.log, timeoutMs = 60000) {
    return runtimeModule().run(env, hdbModule, log, timeoutMs, false);
}

/** Część programu taska ze stałymi; osobno, aby test mógł ją wykonać i porównać z kontraktem. */
function constantsSource() {
    const compositeKeys = Object.fromEntries(Object.entries(PRIMARY_KEYS).filter(([, c]) => c.length !== 1 || c[0] !== 'ID'));
    return [
        `const HDI_NAME=${JSON.stringify(HDI_NAME)},CONTRACT_ID=${JSON.stringify(CONTRACT_ID)};`,
        `const EXPECTED=${JSON.stringify(EXPECTED)};`,
        `const CATALOG_SQL=${JSON.stringify(CATALOG_SQL)};`,
        `const PRIMARY_KEYS=Object.fromEntries(EXPECTED.TABLE.map(t=>[t,(${JSON.stringify(compositeKeys)})[t]||["ID"]])),COLUMNS=${JSON.stringify(COLUMNS)};`,
        `const LAB_TABLE=${JSON.stringify(LAB_TABLE)},STOR_TABLE=${JSON.stringify(STOR_TABLE)},LAB_VIEW=${JSON.stringify(LAB_VIEW)},LAB_VIEW_COLUMNS=${JSON.stringify(LAB_VIEW_COLUMNS)};`,
    ].join('\n');
}

/**
 * Program wysyłany do taska: stałe kontraktu + funkcje (Function#toString). Bez odczytu plików, bez modułów
 * innych niż `hdb`. Terser (tylko na laptopie) minifikuje AST; runtime zależy wyłącznie od Node + `hdb`.
 */
function rawRuntimeSource() {
    return [
        constantsSource(),
        ...[selectBinding, clientOptions, validLabView, evaluate, summaryLine, safeError, baseRun].map(String),
        `module.exports={run,main:()=>run(process.env,undefined,console.log,60000,false).then(code=>{process.exitCode=code;})};`,
    ].join('\n').replace(/\r\n/g, '\n');
}

function runtimeSource() {
    return require('terser').minify_sync(rawRuntimeSource(), { compress: { passes: 2 }, mangle: { toplevel: true },
        format: { comments: false } }).code;
}

/** Wykonuje w pamięci dokładnie wysyłany program (testy i `run` offline z atrapą `hdb`). */
function runtimeModule(source = runtimeSource()) {
    const module = { exports: {} };
    new Function('module', 'require', source)(module, name => {
        if (name === 'hdb') return require('hdb');
        throw new Error('HDI_CATALOG_UNEXPECTED_REQUIRE');
    });
    return module.exports;
}

/** Treść POST /v3/apps/<deployer-guid>/tasks — ten sam format i zasoby co G1 15/5/6/15, inna nazwa taska. */
function taskRequest() {
    const encoded = zlib.brotliCompressSync(Buffer.from(runtimeSource(), 'utf8'),
        { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11 } }).toString('base64');
    return {
        name: TASK_NAME,
        command: `node -e 'eval(require("zlib").brotliDecompressSync(Buffer.from("${encoded}","base64")).toString());module.exports.main()'`,
        memory_in_mb: 256,
        disk_in_mb: 512,
    };
}

/** Fail-closed granica długości: generator odmawia zamiast obcinać program lub pomijać kontrole. */
function checkedTaskRequest(max = MAX_TASK_COMMAND_LENGTH) {
    const request = taskRequest();
    if (request.command.length > max) throw new Error('HDI_CATALOG_TASK_COMMAND_TOO_LONG');
    return request;
}

/** Import offline surowych wierszy SQL Console; zawsze OWNER-REPORTED, nigdy dowód połączenia skryptu. */
function fromConsole(value, schema) {
    const fail = () => { throw new Error('HDI_CATALOG_CONSOLE_INVALID'); };
    if (!value || Object.keys(value).sort().join(',') !== 'rows,version' || value.version !== 1
        || !Array.isArray(value.rows) || !schema || typeof schema !== 'string') fail();
    for (const row of value.rows) {
        if (!row || Object.keys(row).sort().join(',') !== 'DETAIL,KIND,NAME,SCHEMA_NAME'
            || Object.values(row).some(v => typeof v !== 'string')) fail();
    }
    return evaluate(value.rows, schema);
}

module.exports = { CONTRACT, CONTRACT_ID, CONTRACT_FILE, loadContract, EXPECTED, PRIMARY_KEYS, COLUMNS, STOR_TABLE,
    CATALOG_SQL, TASK_NAME, MAX_TASK_COMMAND_LENGTH, evaluate, summaryLine, run, constantsSource, rawRuntimeSource,
    runtimeSource, runtimeModule, taskRequest, checkedTaskRequest, fromConsole, sha256 };

if (require.main === module) {
    try {
        const args = process.argv.slice(2);
        if (args.length === 1 && args[0] === '--task-json') {
            const request = checkedTaskRequest();
            process.stdout.write(JSON.stringify(request) + '\n');
            process.stderr.write(`contract=${CONTRACT_ID} runtimeSourceSha256=${sha256(runtimeSource())} commandLength=${request.command.length}\n`);
        } else if (args.length === 1 && args[0] === '--print-runtime-source') {
            process.stdout.write(runtimeSource() + '\n');
        } else if (args.length === 1 && args[0] === '--print-console-sql') {
            process.stdout.write(CATALOG_SQL.replaceAll('?', 'CURRENT_SCHEMA') + ';\n');
        } else if (args.length === 4 && args[0] === '--from-console' && args[2] === '--schema') {
            if (fs.statSync(args[1]).size > 5 * 1024 * 1024) throw new Error('HDI_CATALOG_CONSOLE_INVALID');
            const result = fromConsole(JSON.parse(fs.readFileSync(args[1], 'utf8').replace(/^\uFEFF/, '')), args[3]);
            console.log(summaryLine(result));
            console.log(`HDI_CATALOG_CORE=${result.core} HDI_CATALOG_OWNER_LAB=${result.ownerLab} HDI_CATALOG_OWNER_LAB_ODATA=${result.ownerLabOData} evidence=OWNER-REPORTED`);
            process.exitCode = result.verdict === 'MATCH' ? 0 : 2;
        } else {
            process.stderr.write('Usage: node deploy/scripts/hdi-catalog-stor04.cjs --task-json | --print-runtime-source | --print-console-sql | --from-console <json> --schema <expected-schema>\n');
            process.exitCode = 1;
        }
    } catch (error) {
        console.error(base.safeError('OFFLINE', error));
        process.exitCode = 1;
    }
}
