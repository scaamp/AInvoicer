import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { gunzipSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const catalog = require('../scripts/hdi-catalog-readonly.cjs');
const scriptPath = fileURLToPath(new URL('../scripts/hdi-catalog-readonly.cjs', import.meta.url));

const SECRET = { host: 'synthetic-host.invalid', port: '443', user: 'SYNTH_RT_USER', password: 'synthetic-password-only',
    schema: 'SYNTH_SCHEMA_9F', certificate: '-----BEGIN CERTIFICATE-----synthetic', encrypt: true };
const vcap = (overrides = {}, extra = []) => JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db', plan: 'hdi-shared',
    credentials: { ...SECRET, ...overrides } }, ...extra] });

/** Pełny, zgodny katalog: tabele, widoki, unikalne indeksy i PK dla każdej tabeli. */
function healthyRows() {
    return [
        ...catalog.EXPECTED.TABLE.map(NAME => ({ KIND: 'TABLE', NAME, DETAIL: '-' })),
        ...catalog.EXPECTED.VIEW.map(NAME => ({ KIND: 'VIEW', NAME, DETAIL: '-' })),
        ...catalog.EXPECTED.UNIQUE_INDEX.map(NAME => ({ KIND: 'INDEX', NAME, DETAIL: `UNIQUE@${NAME.replace(/_[A-Z]+$/, '')}` })),
        ...catalog.EXPECTED.TABLE.map((table, i) => ({ KIND: 'INDEX', NAME: `_SYS_TREE_CS_#${i}_#0_#P0`, DETAIL: `PRIMARY KEY@${table}` })),
    ];
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

test('expected catalog mirrors deployed CDS model: 14 tables, 4 views, 6 unique indexes', () => {
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
    assert.equal((sql.match(/\?/g) || []).length, 3);
    for (const from of sql.match(/FROM\s+[A-Z_.]+/g)) assert.match(from, /FROM SYS\.(TABLES|VIEWS|INDEXES)$/);
});

test('healthy catalog -> MATCH, exit 0, schema passed only as bind parameter, no secrets printed', async () => {
    const hdb = fakeHdb(healthyRows());
    const { code, out } = await execute({ VCAP_SERVICES: vcap() }, hdb);
    assert.equal(code, 0);
    assert.match(out, /^HDI_CATALOG verdict=MATCH tables=14\/14 views=4\/4 uniqueIndexes=6\/6 primaryKeys=14\/14 missing=\[\] unexpected=\[\]$/);
    assert.deepEqual(hdb.calls.sql, [catalog.CATALOG_SQL]);
    assert.deepEqual(hdb.calls.params, [[SECRET.schema, SECRET.schema, SECRET.schema]]);
    assert.equal(hdb.calls.ended, 1);
    assert.equal(hdb.calls.options.useTLS, true);
    assert.deepEqual(hdb.calls.options.ca, [SECRET.certificate]);
    assert.equal(hdb.calls.options.sslValidateCertificate, undefined, 'certificate validation never disabled');
    assertNoSecrets(out);
});

test('missing, extra or non-unique objects -> MISMATCH, exit 2', async () => {
    const rows = healthyRows().filter(r => r.NAME !== 'CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY'
        && r.NAME !== 'KSEFSERVICE_KSEFOPERATIONS' && r.DETAIL !== 'PRIMARY KEY@CDS_OUTBOX_MESSAGES');
    rows.push({ KIND: 'TABLE', NAME: 'FOREIGN_TABLE', DETAIL: '-' });
    rows.push({ KIND: 'INDEX', NAME: 'CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY', DETAIL: '-@CUSTOMER_CAP_JAVA_KSEFOPERATIONS' });
    const { code, out } = await execute({ VCAP_SERVICES: vcap() }, fakeHdb(rows));
    assert.equal(code, 2);
    assert.match(out, /verdict=MISMATCH/);
    for (const item of ['VIEW:KSEFSERVICE_KSEFOPERATIONS', 'UNIQUE_INDEX:CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY',
        'PRIMARY_KEY:CDS_OUTBOX_MESSAGES']) assert.match(out, new RegExp(`missing=\\[[^\\]]*${item}`));
    assert.match(out, /unexpected=\[TABLE:FOREIGN_TABLE\]/);
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
    assert.match(request.command, /^node -e 'eval\(require\("zlib"\)\.gunzipSync\(Buffer\.from\("[A-Za-z0-9+/=]+","base64"\)\)\.toString\(\)\);module\.exports\.main\(\)'$/);
    assert.deepEqual(Object.keys(request).sort(), ['command', 'disk_in_mb', 'memory_in_mb', 'name']);
    const encoded = request.command.match(/Buffer\.from\("([A-Za-z0-9+/=]+)"/)[1];
    const shipped = gunzipSync(Buffer.from(encoded, 'base64')).toString('utf8');
    assert.equal(shipped, catalog.runtimeSource());
    assert.doesNotMatch(shipped, /readFileSync|writeFile|child_process|spawn|exec\(\s*['"`]|fetch\(|https?:\/\//);
    // Wykonanie dokładnie wysyłanego programu z atrapą `hdb`: ten sam wynik co moduł.
    const shippedModule = { exports: {} };
    new Function('module', 'require', shipped)(shippedModule, name => { throw new Error(`unexpected require ${name}`); });
    const lines = [];
    const code = await shippedModule.exports.run({ VCAP_SERVICES: vcap() }, fakeHdb(healthyRows()), line => lines.push(line));
    assert.equal(code, 0);
    assert.match(lines.join('\n'), /verdict=MATCH/);
});

test('CLI prints only the task request and hashes, never connects', () => {
    const result = spawnSync(process.execPath, [scriptPath, '--task-json'], { encoding: 'utf8', env: { PATH: process.env.PATH } });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), catalog.taskRequest());
    assert.match(result.stderr, /^runtimeSourceSha256=[0-9a-f]{64} commandLength=\d+\n$/);
    const usage = spawnSync(process.execPath, [scriptPath], { encoding: 'utf8', env: { PATH: process.env.PATH } });
    assert.equal(usage.status, 1);
});
