import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { brotliDecompressSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const catalog = require('../scripts/hdi-catalog-readonly.cjs');
const scriptPath = fileURLToPath(new URL('../scripts/hdi-catalog-readonly.cjs', import.meta.url));

const SECRET = { host: 'synthetic-host.invalid', port: '443', user: 'SYNTH_RT_USER', password: 'synthetic-password-only',
    schema: 'SYNTH_SCHEMA_9F', certificate: '-----BEGIN CERTIFICATE-----synthetic', encrypt: true };
const vcap = (overrides = {}, extra = []) => JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db', plan: 'hdi-shared',
    credentials: { ...SECRET, ...overrides } }, ...extra] });

/** Pełny, zgodny katalog: tabele, widoki, unikalne indeksy i PK dla każdej tabeli. */
function healthyRows(lab = true) {
    return [
        ...[...catalog.EXPECTED.TABLE, ...(lab ? [catalog.LAB_TABLE] : [])].map(NAME => ({ KIND: 'TABLE', NAME, DETAIL: '-' })),
        ...catalog.EXPECTED.VIEW.map(NAME => ({ KIND: 'VIEW', NAME, DETAIL: '-' })),
        ...catalog.EXPECTED.UNIQUE_INDEX.map(NAME => ({ KIND: 'INDEX', NAME, DETAIL: `UNIQUE@${NAME.replace(/_[A-Z]+$/, '')}` })),
        ...[...catalog.EXPECTED.TABLE, ...(lab ? [catalog.LAB_TABLE] : [])].map((table, i) => ({ KIND: 'INDEX', NAME: `_SYS_TREE_CS_#${i}_#0_#P0`, DETAIL: `PRIMARY KEY@${table}` })),
        ...Object.entries(catalog.PRIMARY_KEYS).filter(([t]) => lab || t !== catalog.LAB_TABLE)
            .flatMap(([NAME, columns]) => columns.map((c, i) => ({ KIND: 'PK_COLUMN', NAME, DETAIL: `${i + 1}:${c}` }))),
        ...(lab ? Object.entries(catalog.LAB_COLUMNS).map(([NAME, [type, length, nullable]]) =>
            ({ KIND: 'COLUMN', NAME, DETAIL: `${type}:${length ?? '-'}:${nullable}` })) : []),
    ].map(r => ({ ...r, SCHEMA_NAME: SECRET.schema }));
}

/** Atrapa sterownika `hdb`: zapisuje wszystkie polecenia i parametry, nie otwiera sieci. */
function fakeHdb(rows, failures = {}) {
    const calls = { options: null, sql: [], params: [], ended: 0 };
    return { calls, createClient(options) {
        calls.options = options;
        return {
            on() {},
            connect(cb) { cb(failures.connect || null); },
            prepare(sql, cb) { calls.sql.push(sql); cb(failures.prepare || null, { exec(params, done) { calls.params.push(params); done(failures.exec || null, rows); } }); },
            end() { calls.ended += 1; },
        };
    } };
}

async function execute(env, hdb, timeoutMs) {
    const lines = [];
    const code = await catalog.run(env, hdb, line => lines.push(line), timeoutMs);
    return { code, out: lines.join('\n') };
}

const assertNoSecrets = out => {
    for (const value of [SECRET.host, SECRET.user, SECRET.password, SECRET.schema, 'BEGIN CERTIFICATE']) assert.ok(!out.includes(value), value);
};

test('approved CORE contract: 14 tables, 4 views, 6 unique indexes', () => {
    assert.equal(catalog.EXPECTED.TABLE.length, 14);
    assert.equal(catalog.EXPECTED.VIEW.length, 4);
    assert.equal(catalog.EXPECTED.UNIQUE_INDEX.length, 6);
    assert.ok(catalog.EXPECTED.TABLE.includes('CDS_OUTBOX_MESSAGES'), 'harness seeds cds.outbox.Messages');
    assert.ok(catalog.EXPECTED.UNIQUE_INDEX.includes('CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY'));
});

test('only one parameterised SELECT on SYS catalog views; no DML/DDL/CALL', () => {
    const sql = catalog.CATALOG_SQL.toUpperCase();
    assert.match(sql, /^SELECT /);
    assert.doesNotMatch(sql, /\b(INSERT|UPDATE|DELETE|UPSERT|MERGE|REPLACE|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|CALL|EXEC|SET SCHEMA)\b/);
    assert.doesNotMatch(sql, /;/);
    assert.equal((sql.match(/\?/g) || []).length, 5);
    for (const from of sql.match(/FROM\s+[A-Z_.]+/g)) assert.match(from, /FROM SYS\.(TABLES|VIEWS|INDEXES|INDEX_COLUMNS|TABLE_COLUMNS)$/);
});

test('healthy catalog -> MATCH, exit 0, schema passed only as bind parameter, no secrets printed', async () => {
    const hdb = fakeHdb(healthyRows());
    const { code, out } = await execute({ VCAP_SERVICES: vcap() }, hdb);
    assert.equal(code, 0);
    assert.match(out, /^HDI_CATALOG verdict=MATCH tables=15\/15 views=4\/4 uniqueIndexes=6\/6 primaryKeys=15\/15 missing=\[\] unexpected=\[\]\nHDI_CATALOG_CORE=PASS HDI_CATALOG_OWNER_LAB=PASS evidence=SCRIPT_MEASURED$/);
    assert.deepEqual(hdb.calls.sql, [catalog.CATALOG_SQL]);
    assert.deepEqual(hdb.calls.params, [Array(5).fill(SECRET.schema)]);
    assert.equal(hdb.calls.ended, 1);
    assert.equal(hdb.calls.options.useTLS, true);
    assert.deepEqual(hdb.calls.options.ca, [SECRET.certificate]);
    assert.equal(hdb.calls.options.sslValidateCertificate, undefined, 'certificate validation never disabled');
    assertNoSecrets(out);
});

test('missing, extra or non-unique objects -> MISMATCH, exit 2', async () => {
    const rows = healthyRows().filter(r => r.NAME !== 'CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY'
        && r.NAME !== 'KSEFSERVICE_KSEFOPERATIONS' && r.DETAIL !== 'PRIMARY KEY@CDS_OUTBOX_MESSAGES');
    rows.push({ KIND: 'TABLE', NAME: 'FOREIGN_TABLE', DETAIL: '-', SCHEMA_NAME: SECRET.schema });
    rows.push({ KIND: 'INDEX', NAME: 'CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY', DETAIL: '-@CUSTOMER_CAP_JAVA_KSEFOPERATIONS', SCHEMA_NAME: SECRET.schema });
    const { code, out } = await execute({ VCAP_SERVICES: vcap() }, fakeHdb(rows));
    assert.equal(code, 2);
    assert.match(out, /verdict=MISMATCH/);
    for (const item of ['VIEW:KSEFSERVICE_KSEFOPERATIONS', 'UNIQUE_INDEX:CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY',
        'PRIMARY_KEY:CDS_OUTBOX_MESSAGES']) assert.match(out, new RegExp(`missing=\\[[^\\]]*${item}`));
    assert.match(out, /unexpected=\[[^\]]*TABLE:FOREIGN_TABLE/);
    assert.equal((await execute({ VCAP_SERVICES: vcap() }, fakeHdb([]))).code, 2, 'empty container is not a pass');
});

test('binding gate refuses CI, DBADMIN, wrong/duplicate/incomplete bindings before any connection', async () => {
    const cases = [
        { VCAP_SERVICES: vcap(), GITHUB_ACTIONS: 'true' },
        { VCAP_SERVICES: vcap({ user: 'dbadmin' }) },
        { VCAP_SERVICES: vcap({ password: '' }) },
        { VCAP_SERVICES: vcap({}, [{ label: 'hana', name: 'other', plan: 'hdi-shared', credentials: SECRET }]) },
        { VCAP_SERVICES: JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db', plan: 'schema', credentials: SECRET }] }) },
        { VCAP_SERVICES: JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db-test', plan: 'hdi-shared', credentials: SECRET }] }) },
        { VCAP_SERVICES: '{not json' },
        {},
    ];
    for (const env of cases) {
        const hdb = fakeHdb(healthyRows());
        const { code, out } = await execute(env, hdb);
        assert.equal(code, 1, JSON.stringify(Object.keys(env)));
        assert.equal(hdb.calls.options, null, 'no client created');
        assert.match(out, /^HDI_CATALOG_ERROR stage=BINDING code=n\/a sqlState=n\/a reason=HDI_CATALOG_[A-Z_]+$/);
        assertNoSecrets(out);
    }
});

test('driver errors report only stage/code/SQLState, never server message (may contain host)', async () => {
    const error = Object.assign(new Error(`connect to ${SECRET.host} failed for ${SECRET.user}`), { code: 10, sqlState: '28000' });
    for (const stage of ['connect', 'prepare', 'exec']) {
        const hdb = fakeHdb(healthyRows(), { [stage]: error });
        const { code, out } = await execute({ VCAP_SERVICES: vcap() }, hdb);
        assert.equal(code, 1);
        assert.match(out, /^HDI_CATALOG_ERROR stage=(CONNECT|PREPARE|SELECT) code=10 sqlState=28000$/);
        assert.equal(hdb.calls.ended, 1);
        assertNoSecrets(out);
    }
});

test('hanging connection ends with TIMEOUT and exit 1', async () => {
    const calls = { ended: 0 };
    const hdb = { createClient: () => ({ on() {}, connect() { /* nigdy nie odpowiada */ }, end() { calls.ended += 1; } }) };
    const { code, out } = await execute({ VCAP_SERVICES: vcap() }, hdb, 20);
    assert.equal(code, 1);
    assert.match(out, /^HDI_CATALOG_ERROR stage=TIMEOUT/);
    assert.equal(calls.ended, 1);
});

test('task request: fits command limit, contains no binding data, decodes 1:1 to the reviewed runtime program', async () => {
    const request = catalog.taskRequest();
    assert.equal(request.name, 'cap-hana-02-hdi-catalog-readonly');
    assert.ok(request.command.length <= catalog.MAX_TASK_COMMAND_LENGTH, `command length ${request.command.length}`);
    assert.ok(request.memory_in_mb <= 256 && request.disk_in_mb <= 512);
    assert.match(request.command, /^node -e 'eval\(require\("zlib"\)\.brotliDecompressSync\(Buffer\.from\("[A-Za-z0-9+/=]+","base64"\)\)\.toString\(\)\);module\.exports\.main\(\)'$/);
    assert.deepEqual(Object.keys(request).sort(), ['command', 'disk_in_mb', 'memory_in_mb', 'name']);
    const encoded = request.command.match(/Buffer\.from\("([A-Za-z0-9+/=]+)"/)[1];
    const shipped = brotliDecompressSync(Buffer.from(encoded, 'base64')).toString('utf8');
    assert.equal(shipped, catalog.runtimeSource());
    assert.doesNotMatch(shipped, /readFileSync|writeFile|child_process|spawn|exec\(\s*['"`]|fetch\(|https?:\/\//);
    // Wykonanie dokładnie wysyłanego programu z atrapą `hdb`: ten sam wynik co moduł.
    const shippedModule = { exports: {} };
    new Function('module', 'require', shipped)(shippedModule, name => { throw new Error(`unexpected require ${name}`); });
    const lines = [];
    const code = await shippedModule.exports.run({ VCAP_SERVICES: vcap() }, fakeHdb(healthyRows()), line => lines.push(line));
    assert.equal(code, 0);
    assert.match(lines.join('\n'), /verdict=MATCH/);
    for (const [name, change] of Object.entries(negativeCases)) {
        const rows = change(healthyRows());
        const shippedLines = [];
        assert.equal(await shippedModule.exports.run({ VCAP_SERVICES: vcap() }, fakeHdb(rows), line => shippedLines.push(line)), 2, name);
        assert.equal(shippedLines[0], catalog.summaryLine(catalog.evaluate(rows, SECRET.schema)), name);
    }
    assert.ok(catalog.taskRequest(true).command.length <= catalog.MAX_TASK_COMMAND_LENGTH);
});

test('CLI prints only the task request and hashes, never connects', () => {
    const result = spawnSync(process.execPath, [scriptPath, '--task-json'], { encoding: 'utf8', env: { PATH: process.env.PATH } });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), catalog.taskRequest());
    assert.match(result.stderr, /^runtimeSourceSha256=[0-9a-f]{64} commandLength=\d+\n$/);
    const usage = spawnSync(process.execPath, [scriptPath], { encoding: 'utf8', env: { PATH: process.env.PATH } });
    assert.equal(usage.status, 1);
});

test('historical core-only is explicit: 14/4/6 MATCH; default still requires LAB', async () => {
    const rows = healthyRows(false);
    const result = catalog.evaluate(rows, SECRET.schema, true);
    assert.equal(result.verdict, 'MATCH');
    assert.equal(result.core, 'PASS');
    assert.equal(result.ownerLab, 'NOT_REQUESTED');
    assert.match(catalog.summaryLine(result), /tables=14\/14.*primaryKeys=14\/14/);
    assert.equal(catalog.evaluate(rows, SECRET.schema).verdict, 'MISMATCH');
    assert.equal(catalog.evaluate(healthyRows(), SECRET.schema, true).verdict, 'MISMATCH');
    const hdb = fakeHdb(rows);
    assert.equal(await catalog.run({ VCAP_SERVICES: vcap() }, hdb, () => {}, 1000, true), 0);
});

// Niezależny od stałych walidatora zapis hdbtable odczytanego z istniejącego src/gen właściciela.
const labDDL = `COLUMN TABLE customer_cap_java_KsefDocuments (
  ID NVARCHAR(36) NOT NULL,
  createdAt TIMESTAMP,
  createdBy NVARCHAR(255),
  modifiedAt TIMESTAMP,
  modifiedBy NVARCHAR(255),
  fileName NVARCHAR(255),
  "MIMETYPE" NVARCHAR(100),
  content BLOB,
  sha256 NVARCHAR(64),
  PRIMARY KEY(ID)
)`;
test('LAB contract matches observed hdbtable including quoted MIMETYPE, managed and nullable columns', () => {
    const physical = id => id.startsWith('"') ? id.slice(1, -1).replaceAll('""', '"') : id.toUpperCase();
    assert.equal(physical(labDDL.match(/^COLUMN TABLE (\S+)/)[1]), catalog.LAB_TABLE);
    const columns = Object.fromEntries([...labDDL.matchAll(/^  ("[^"]+"|\w+) (NVARCHAR|TIMESTAMP|BLOB)(?:\((\d+)\))?( NOT NULL)?/gm)]
        .map(([, name, type, size, required]) => [physical(name), [type, size ? Number(size) : null, required ? 'FALSE' : 'TRUE']]));
    assert.deepEqual(columns, catalog.LAB_COLUMNS);
    assert.deepEqual(catalog.PRIMARY_KEYS[catalog.LAB_TABLE], ['ID']);
});

const negativeCases = {
    'missing CORE table': r => r.filter(x => x.NAME !== 'CDS_OUTBOX_MESSAGES'),
    'unknown additional table': r => [...r, { KIND: 'TABLE', NAME: 'UNAPPROVED', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'wrong LAB PK': r => r.map(x => x.KIND === 'PK_COLUMN' && x.NAME === catalog.LAB_TABLE ? { ...x, DETAIL: '1:SHA256' } : x),
    'wrong composite CORE PK order': r => r.map(x => x.KIND === 'PK_COLUMN' && x.NAME.endsWith('KSEFSENDRESERVATIONS') ? { ...x, DETAIL: x.DETAIL === '1:INVOICEUUID' ? '1:ENVIRONMENT' : '2:INVOICEUUID' } : x),
    'missing CONTENT': r => r.filter(x => !(x.KIND === 'COLUMN' && x.NAME === 'CONTENT')),
    'CONTENT NVARCHAR': r => r.map(x => x.KIND === 'COLUMN' && x.NAME === 'CONTENT' ? { ...x, DETAIL: 'NVARCHAR:500:TRUE' } : x),
    'wrong MIMETYPE type': r => r.map(x => x.KIND === 'COLUMN' && x.NAME === 'MIMETYPE' ? { ...x, DETAIL: 'VARCHAR:100:TRUE' } : x),
    'wrong MIMETYPE size': r => r.map(x => x.KIND === 'COLUMN' && x.NAME === 'MIMETYPE' ? { ...x, DETAIL: 'NVARCHAR:99:TRUE' } : x),
    'wrong FILENAME size': r => r.map(x => x.KIND === 'COLUMN' && x.NAME === 'FILENAME' ? { ...x, DETAIL: 'NVARCHAR:254:TRUE' } : x),
    'nullable ID': r => r.map(x => x.KIND === 'COLUMN' && x.NAME === 'ID' ? { ...x, DETAIL: 'NVARCHAR:36:TRUE' } : x),
    'non-null managed timestamp': r => r.map(x => x.KIND === 'COLUMN' && x.NAME === 'CREATEDAT' ? { ...x, DETAIL: 'TIMESTAMP:27:FALSE' } : x),
    'unexpected LAB column': r => [...r, { KIND: 'COLUMN', NAME: 'EXTRA', DETAIL: 'BLOB:0:TRUE', SCHEMA_NAME: SECRET.schema }],
    'wrong schema': r => r.map(x => ({ ...x, SCHEMA_NAME: 'OTHER_SCHEMA' })),
    'one row in wrong schema': r => [{ ...r[0], SCHEMA_NAME: 'OTHER_SCHEMA' }, ...r.slice(1)],
    'missing schema': r => r.map(({ SCHEMA_NAME, ...x }) => x),
    'duplicate table': r => [...r, { ...r[0] }],
    'duplicate view': r => [...r, { ...r.find(x => x.KIND === 'VIEW') }],
    'duplicate LAB column': r => [...r, { ...r.find(x => x.KIND === 'COLUMN') }],
    'duplicate index': r => [...r, { ...r.find(x => x.KIND === 'INDEX') }],
    'duplicate PK column': r => [...r, { ...r.find(x => x.KIND === 'PK_COLUMN') }],
    'duplicate PK with different index name': r => [...r, { ...r.find(x => x.KIND === 'INDEX' && x.DETAIL.startsWith('PRIMARY KEY')), NAME: 'SECOND_PK' }],
    'missing PK column metadata': r => r.filter(x => x.KIND !== 'PK_COLUMN'),
    'unknown row kind': r => [...r, { KIND: 'MATCH', NAME: 'MATCH', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'empty response': () => [],
    'undefined response': () => undefined,
    'null row': r => [...r, null],
    'malformed row': r => [...r, {}],
    'non-string NAME': r => [{ ...r[0], NAME: 17 }, ...r.slice(1)],
    'non-unique additional index': r => [...r, { KIND: 'INDEX', NAME: 'EXTRA', DETAIL: '-@CDS_OUTBOX_MESSAGES', SCHEMA_NAME: SECRET.schema }],
    'unique index on wrong table': r => r.map(x => x.NAME.endsWith('_IDEMPOTENCY') ? { ...x, DETAIL: 'UNIQUE@CDS_OUTBOX_MESSAGES' } : x),
    'case-sensitive quoted name': r => r.map(x => x.NAME === 'MIMETYPE' ? { ...x, NAME: 'mimeType' } : x),
    'quoted spelling is not catalog name': r => r.map(x => x.NAME === 'MIMETYPE' ? { ...x, NAME: '"MIMETYPE"' } : x),
    'identifier with control characters': r => [...r, { KIND: 'TABLE', NAME: 'BAD\nverdict=MATCH', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
};
for (const [name, change] of Object.entries(negativeCases)) {
    test(`${name} -> MISMATCH / exit 2`, async () => {
        const rows = change(healthyRows());
        assert.equal(catalog.evaluate(rows, SECRET.schema).verdict, 'MISMATCH');
        const { code, out } = await execute({ VCAP_SERVICES: vcap() }, fakeHdb(rows));
        assert.equal(code, 2);
        assert.match(out, /verdict=MISMATCH/);
        assertNoSecrets(out);
    });
}

test('CORE and LAB results are separate; names/order deterministic and HANA constraint spellings supported', () => {
    const rows = healthyRows().map(r => ({ ...r, DETAIL: r.DETAIL.replace('PRIMARY KEY@', 'PRIMARY_KEY@').replace('UNIQUE@', 'NOT_NULL_UNIQUE@') }));
    assert.equal(catalog.evaluate(rows, SECRET.schema).verdict, 'MATCH');
    assert.deepEqual(catalog.evaluate([...rows].reverse(), SECRET.schema), catalog.evaluate(rows, SECRET.schema));
    const missingLab = catalog.evaluate(healthyRows(false), SECRET.schema);
    assert.equal(missingLab.core, 'PASS');
    assert.equal(missingLab.ownerLab, 'FAIL');
    const missingCore = catalog.evaluate(negativeCases['missing CORE table'](healthyRows()), SECRET.schema);
    assert.equal(missingCore.core, 'FAIL');
    assert.equal(missingCore.ownerLab, 'PASS');
});

test('insufficient privilege at PREPARE or SELECT -> exit 1; no retry, no GRANT, no server text', async () => {
    const err = Object.assign(new Error(`insufficient privilege for ${SECRET.user} at ${SECRET.host}`), { code: 258, sqlState: 'HY000' });
    for (const stage of ['prepare', 'exec']) {
        const hdb = fakeHdb(healthyRows(), { [stage]: err });
        const result = await execute({ VCAP_SERVICES: vcap() }, hdb);
        assert.equal(result.code, 1);
        assert.match(result.out, /code=258 sqlState=HY000/);
        assert.doesNotMatch(result.out, /MATCH|insufficient privilege/);
        assert.equal(hdb.calls.sql.length, 1);
        assertNoSecrets(result.out);
    }
});

test('console import accepts only raw, complete versioned rows; counters/verdict are rejected', () => {
    assert.equal(catalog.fromConsole({ version: 1, rows: healthyRows() }, SECRET.schema).verdict, 'MATCH');
    const invalid = [null, [], {}, { version: 2, rows: [] }, { version: 1, rows: 'MATCH' },
        { version: 1, rows: healthyRows(), verdict: 'MATCH' }, { version: 1, tables: 15 },
        { version: 1, rows: [{}] }, { version: 1, rows: [null] },
        { version: 1, rows: [{ KIND: 'TABLE', NAME: 'X', DETAIL: '-' }] },
        { version: 1, rows: [{ ...healthyRows()[0], NAME: 17 }] }];
    for (const value of invalid) assert.throws(() => catalog.fromConsole(value, SECRET.schema), /HDI_CATALOG_CONSOLE_INVALID/);
    assert.throws(() => catalog.fromConsole({ version: 1, rows: healthyRows() }, ''), /HDI_CATALOG_CONSOLE_INVALID/);
    assert.equal(catalog.fromConsole({ version: 1, rows: healthyRows() }, 'WRONG').verdict, 'MISMATCH');
    assert.equal(catalog.fromConsole({ version: 1, rows: [] }, SECRET.schema).verdict, 'MISMATCH');
});

test('console CLI is offline in CI, labels evidence OWNER-REPORTED; invalid input is safe', () => {
    const scratch = mkdtempSync(join(tmpdir(), 'ainv-catalog-'));
    const path = join(scratch, 'catalog.json');
    const cli = args => spawnSync(process.execPath, [scriptPath, ...args], { encoding: 'utf8', env: { PATH: process.env.PATH, GITHUB_ACTIONS: 'true' } });
    try {
        writeFileSync(path, '\uFEFF' + JSON.stringify({ version: 1, rows: healthyRows() }));
        const result = cli(['--from-console', path, '--schema', SECRET.schema]);
        assert.equal(result.status, 0, result.stderr);
        assert.match(result.stdout, /evidence=OWNER-REPORTED/);
        assert.doesNotMatch(result.stdout, /SCRIPT_MEASURED/);
        assertNoSecrets(result.stdout + result.stderr);
        const mismatch = cli(['--from-console', path, '--schema', 'WRONG']);
        assert.equal(mismatch.status, 2);
        writeFileSync(path, `{invalid ${SECRET.password}`);
        const invalid = cli(['--from-console', path, '--schema', SECRET.schema]);
        assert.equal(invalid.status, 1);
        assertNoSecrets(invalid.stdout + invalid.stderr);
        assert.equal(cli(['--from-console', path]).status, 1);
        assert.equal(cli(['--task-json', '--from-console', path]).status, 1);
        const sql = cli(['--print-console-sql']);
        assert.equal(sql.status, 0);
        assert.equal(sql.stdout, catalog.CATALOG_SQL.replaceAll('?', 'CURRENT_SCHEMA') + ';\n');
        assert.equal((sql.stdout.match(/CURRENT_SCHEMA/g) || []).length, 5);
        const historical = cli(['--core-only', '--print-runtime-source']);
        assert.equal(historical.stdout, catalog.runtimeSource(true) + '\n');
    } finally { rmSync(scratch, { recursive: true, force: true }); }
});
