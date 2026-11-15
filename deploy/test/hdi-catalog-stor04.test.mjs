// KSEF-STOR-04R: walidator katalogu 16/5/6/16 po zatwierdzonym release. Testy offline, bez CF/HANA.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { brotliDecompressSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const stor = require('../scripts/hdi-catalog-stor04.cjs');
const old = require('../scripts/hdi-catalog-readonly.cjs');
const scriptPath = fileURLToPath(new URL('../scripts/hdi-catalog-stor04.cjs', import.meta.url));
const contractJson = JSON.parse(readFileSync(new URL('../contracts/hdi-catalog-stor04.v1.json', import.meta.url), 'utf8'));
const TABLE = 'CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS';
const LAB = 'CUSTOMER_CAP_JAVA_KSEFDOCUMENTS';

const SECRET = { host: 'synthetic-host.invalid', port: '443', user: 'SYNTH_RT_USER', password: 'synthetic-password-only',
    schema: 'SYNTH_SCHEMA_9F', certificate: '-----BEGIN CERTIFICATE-----synthetic', encrypt: true };
const vcap = (overrides = {}) => JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db', plan: 'hdi-shared',
    credentials: { ...SECRET, ...overrides } }] });
const assertNoSecrets = out => {
    for (const value of [SECRET.host, SECRET.user, SECRET.password, SECRET.schema, 'BEGIN CERTIFICATE']) assert.ok(!out.includes(value), value);
};

// Ten sam niezależny zapis widoku LAB co w teście G1 15/5/6/15 (compile.to.hana, main=7a47ac9).
const labViewDDL = `VIEW BlobService_Documents AS SELECT
  KsefDocuments_0.ID,
  KsefDocuments_0.fileName,
  KsefDocuments_0."MIMETYPE",
  KsefDocuments_0.content,
  KsefDocuments_0.sha256,
  KsefDocuments_0.createdBy,
  KsefDocuments_0.createdAt
FROM customer_cap_java_KsefDocuments AS KsefDocuments_0`;

// Niezależne, ręcznie zapisane oczekiwanie (nie ze stałych walidatora): kolumny katalogowe po release.
const STORAGE_COLUMNS = { ENVIRONMENT: 'NVARCHAR:4:FALSE', CHANNEL: 'NVARCHAR:16:FALSE', OWNERSEGMENT: 'NVARCHAR:32:FALSE',
    SHA256: 'NVARCHAR:64:FALSE', MIMETYPE: 'NVARCHAR:100:FALSE', SIZEBYTES: 'BIGINT:19:FALSE', STOREDAT: 'TIMESTAMP:27:FALSE',
    CONTENT: 'BLOB:0:FALSE' };
const LAB_COLUMNS = { ID: 'NVARCHAR:36:FALSE', CREATEDAT: 'TIMESTAMP:27:TRUE', CREATEDBY: 'NVARCHAR:255:TRUE',
    MODIFIEDAT: 'TIMESTAMP:27:TRUE', MODIFIEDBY: 'NVARCHAR:255:TRUE', FILENAME: 'NVARCHAR:255:TRUE', MIMETYPE: 'NVARCHAR:100:TRUE',
    CONTENT: 'BLOB:0:TRUE', SHA256: 'NVARCHAR:64:TRUE' };

/** Katalog po zatwierdzonym release: 16 tabel, 5 widoków, 6 UNIQUE, 16 PK, kolumny LAB i storage kwalifikowane. */
function healthyRows() {
    const tables = [...old.EXPECTED.TABLE, LAB, TABLE];
    const pk = { ...old.PRIMARY_KEYS, [TABLE]: ['ENVIRONMENT', 'CHANNEL', 'OWNERSEGMENT', 'SHA256'] };
    return [
        ...tables.map(NAME => ({ KIND: 'TABLE', NAME, DETAIL: '-' })),
        ...old.EXPECTED.VIEW.map(NAME => ({ KIND: 'VIEW', NAME, DETAIL: '-' })),
        { KIND: 'VIEW', NAME: 'BLOBSERVICE_DOCUMENTS', DETAIL: 'TRUE:' + labViewDDL },
        ...old.EXPECTED.UNIQUE_INDEX.map(NAME => ({ KIND: 'INDEX', NAME, DETAIL: `UNIQUE@${NAME.replace(/_[A-Z]+$/, '')}` })),
        ...tables.map((table, i) => ({ KIND: 'INDEX', NAME: `_SYS_TREE_CS_#${i}_#0_#P0`, DETAIL: `PRIMARY KEY@${table}` })),
        ...Object.entries(pk).flatMap(([NAME, columns]) => columns.map((c, i) => ({ KIND: 'PK_COLUMN', NAME, DETAIL: `${i + 1}:${c}` }))),
        ...Object.entries(LAB_COLUMNS).map(([c, DETAIL]) => ({ KIND: 'COLUMN', NAME: `${LAB}.${c}`, DETAIL })),
        ...Object.entries(STORAGE_COLUMNS).map(([c, DETAIL]) => ({ KIND: 'COLUMN', NAME: `${TABLE}.${c}`, DETAIL })),
    ].map(r => ({ ...r, SCHEMA_NAME: SECRET.schema }));
}

/** Katalog obecnego wdrożenia w formacie STARego SQL (kolumny LAB bez kwalifikacji): 15/5/6/15. */
function deployedOldRows() {
    return healthyRows().filter(r => r.NAME !== TABLE && !r.DETAIL.endsWith('@' + TABLE)
        && !(r.KIND === 'PK_COLUMN' && r.NAME === TABLE) && !r.NAME.startsWith(TABLE + '.'))
        .map(r => r.KIND === 'COLUMN' ? { ...r, NAME: r.NAME.slice(LAB.length + 1) } : r);
}

/** Ten sam katalog sprzed deployu, ale odczytany NOWYM SQL (kolumny LAB kwalifikowane). */
function deployedNewSqlRows() {
    return healthyRows().filter(r => r.NAME !== TABLE && !r.DETAIL.endsWith('@' + TABLE)
        && !(r.KIND === 'PK_COLUMN' && r.NAME === TABLE) && !r.NAME.startsWith(TABLE + '.'));
}

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

const shipped = stor.runtimeModule();
async function execute(rows, env = { VCAP_SERVICES: vcap() }, module = shipped, failures) {
    const lines = [];
    const hdb = fakeHdb(rows, failures);
    const code = await module.run(env, hdb, line => lines.push(line));
    return { code, out: lines.join('\n'), hdb };
}

const set = (name, column, detail) => r => r.map(x => x.KIND === 'COLUMN' && x.NAME === `${name}.${column}` ? { ...x, DETAIL: detail } : x);
const negativeCases = {
    // Storage (nowe kontrole)
    'missing storage table': r => r.filter(x => !(x.KIND === 'TABLE' && x.NAME === TABLE)),
    'missing storage PK index': r => r.filter(x => x.DETAIL !== 'PRIMARY KEY@' + TABLE),
    'storage PK wrong order': r => r.map(x => x.KIND === 'PK_COLUMN' && x.NAME === TABLE && x.DETAIL === '1:ENVIRONMENT' ? { ...x, DETAIL: '1:CHANNEL' }
        : x.KIND === 'PK_COLUMN' && x.NAME === TABLE && x.DETAIL === '2:CHANNEL' ? { ...x, DETAIL: '2:ENVIRONMENT' } : x),
    'storage PK missing column': r => r.filter(x => !(x.KIND === 'PK_COLUMN' && x.NAME === TABLE && x.DETAIL === '4:SHA256')),
    'storage PK extra column': r => [...r, { KIND: 'PK_COLUMN', NAME: TABLE, DETAIL: '5:MIMETYPE', SCHEMA_NAME: SECRET.schema }],
    'storage PK = ID only': r => [...r.filter(x => !(x.KIND === 'PK_COLUMN' && x.NAME === TABLE)), { KIND: 'PK_COLUMN', NAME: TABLE, DETAIL: '1:ID', SCHEMA_NAME: SECRET.schema }],
    'nullable CONTENT': set(TABLE, 'CONTENT', 'BLOB:0:TRUE'),
    'nullable SIZEBYTES': set(TABLE, 'SIZEBYTES', 'BIGINT:19:TRUE'),
    'nullable STOREDAT': set(TABLE, 'STOREDAT', 'TIMESTAMP:27:TRUE'),
    'nullable ENVIRONMENT': set(TABLE, 'ENVIRONMENT', 'NVARCHAR:4:TRUE'),
    'nullable MIMETYPE': set(TABLE, 'MIMETYPE', 'NVARCHAR:100:TRUE'),
    'CONTENT NCLOB': set(TABLE, 'CONTENT', 'NCLOB:0:FALSE'),
    'SIZEBYTES INTEGER': set(TABLE, 'SIZEBYTES', 'INTEGER:10:FALSE'),
    'STOREDAT SECONDDATE': set(TABLE, 'STOREDAT', 'SECONDDATE:19:FALSE'),
    'ENVIRONMENT length 5': set(TABLE, 'ENVIRONMENT', 'NVARCHAR:5:FALSE'),
    'CHANNEL length 15': set(TABLE, 'CHANNEL', 'NVARCHAR:15:FALSE'),
    'OWNERSEGMENT length 64': set(TABLE, 'OWNERSEGMENT', 'NVARCHAR:64:FALSE'),
    'SHA256 length 63': set(TABLE, 'SHA256', 'NVARCHAR:63:FALSE'),
    'MIMETYPE VARCHAR': set(TABLE, 'MIMETYPE', 'VARCHAR:100:FALSE'),
    'storage missing column': r => r.filter(x => x.NAME !== `${TABLE}.STOREDAT`),
    'storage extra column': r => [...r, { KIND: 'COLUMN', NAME: `${TABLE}.CREATEDBY`, DETAIL: 'NVARCHAR:255:TRUE', SCHEMA_NAME: SECRET.schema }],
    'storage duplicate column': r => [...r, { ...r.find(x => x.NAME === `${TABLE}.CONTENT`) }],
    'unqualified storage column': r => [...r.filter(x => x.NAME !== `${TABLE}.CONTENT`), { KIND: 'COLUMN', NAME: 'CONTENT', DETAIL: 'BLOB:0:FALSE', SCHEMA_NAME: SECRET.schema }],
    'storage column of foreign table': r => [...r, { KIND: 'COLUMN', NAME: 'FOREIGN.CONTENT', DETAIL: 'BLOB:0:FALSE', SCHEMA_NAME: SECRET.schema }],
    'malformed column detail': set(TABLE, 'SHA256', 'NVARCHAR:64:FALSE:EXTRA'),
    'non-numeric length': set(TABLE, 'SIZEBYTES', 'BIGINT:x:FALSE'),
    'view over storage table': r => [...r, { KIND: 'VIEW', NAME: 'KSEFSERVICE_STOREDDOCUMENTS', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'unique index on storage': r => [...r, { KIND: 'INDEX', NAME: `${TABLE}_SHA`, DETAIL: `UNIQUE@${TABLE}`, SCHEMA_NAME: SECRET.schema }],
    'non-unique index on storage': r => [...r, { KIND: 'INDEX', NAME: `${TABLE}_IDX`, DETAIL: `-@${TABLE}`, SCHEMA_NAME: SECRET.schema }],
    'case-changed storage table': r => r.map(x => x.KIND === 'TABLE' && x.NAME === TABLE ? { ...x, NAME: 'customer_cap_java_KsefStoredDocuments' } : x),
    'storage table in wrong schema': r => r.map(x => x.NAME === TABLE ? { ...x, SCHEMA_NAME: 'OTHER_SCHEMA' } : x),
    // Zachowane kontrole G1 15/5/6/15 (CORE, LAB, BlobService)
    'missing CORE table': r => r.filter(x => x.NAME !== 'CDS_OUTBOX_MESSAGES'),
    'unknown additional table': r => [...r, { KIND: 'TABLE', NAME: 'UNAPPROVED', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'seventeenth table': r => [...r, { KIND: 'TABLE', NAME: 'CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS2', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'missing fifth view': r => r.filter(x => x.NAME !== 'BLOBSERVICE_DOCUMENTS'),
    'unknown sixth view': r => [...r, { KIND: 'VIEW', NAME: 'FOREIGN_VIEW', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'invalid view': r => r.map(x => x.NAME === 'BLOBSERVICE_DOCUMENTS' ? { ...x, DETAIL: x.DETAIL.replace('TRUE:', 'FALSE:') } : x),
    'wrong projection': r => r.map(x => x.NAME === 'BLOBSERVICE_DOCUMENTS' ? { ...x, DETAIL: x.DETAIL.replace('.sha256', '.modifiedBy') } : x),
    'view reads storage table': r => r.map(x => x.NAME === 'BLOBSERVICE_DOCUMENTS' ? { ...x, DETAIL: x.DETAIL.replace('FROM customer_cap_java_KsefDocuments', 'FROM customer_cap_java_KsefStoredDocuments') } : x),
    'added filter': r => r.map(x => x.NAME === 'BLOBSERVICE_DOCUMENTS' ? { ...x, DETAIL: x.DETAIL + ' WHERE 1=0' } : x),
    'CORE view with definition': r => r.map(x => x.NAME === 'KSEFSERVICE_KSEFOPERATIONS' ? { ...x, DETAIL: 'TRUE:SELECT 1' } : x),
    'table detail not dash': r => r.map(x => x.KIND === 'TABLE' && x.NAME === LAB ? { ...x, DETAIL: 'X' } : x),
    'wrong LAB PK': r => r.map(x => x.KIND === 'PK_COLUMN' && x.NAME === LAB ? { ...x, DETAIL: '1:SHA256' } : x),
    'wrong composite CORE PK order': r => r.map(x => x.KIND === 'PK_COLUMN' && x.NAME.endsWith('KSEFSENDRESERVATIONS') ? { ...x, DETAIL: x.DETAIL === '1:INVOICEUUID' ? '1:ENVIRONMENT' : '2:INVOICEUUID' } : x),
    'missing LAB CONTENT': r => r.filter(x => x.NAME !== `${LAB}.CONTENT`),
    'LAB CONTENT NVARCHAR': set(LAB, 'CONTENT', 'NVARCHAR:500:TRUE'),
    'LAB nullable ID': set(LAB, 'ID', 'NVARCHAR:36:TRUE'),
    'LAB non-null managed timestamp': set(LAB, 'CREATEDAT', 'TIMESTAMP:27:FALSE'),
    'LAB FILENAME size': set(LAB, 'FILENAME', 'NVARCHAR:254:TRUE'),
    'LAB columns in old unqualified format': r => r.map(x => x.KIND === 'COLUMN' && x.NAME.startsWith(LAB + '.') ? { ...x, NAME: x.NAME.slice(LAB.length + 1) } : x),
    'missing unique index': r => r.filter(x => x.NAME !== 'CUSTOMER_CAP_JAVA_KSEFOPERATIONS_IDEMPOTENCY'),
    'unique index on wrong table': r => r.map(x => x.NAME.endsWith('_IDEMPOTENCY') ? { ...x, DETAIL: 'UNIQUE@CDS_OUTBOX_MESSAGES' } : x),
    'index on unknown table': r => [...r, { KIND: 'INDEX', NAME: 'X', DETAIL: 'UNIQUE@UNKNOWN', SCHEMA_NAME: SECRET.schema }],
    'index detail with extra @': r => r.map(x => x.DETAIL === 'PRIMARY KEY@' + TABLE ? { ...x, DETAIL: x.DETAIL + '@X' } : x),
    'second PK index': r => [...r, { KIND: 'INDEX', NAME: 'SECOND_PK', DETAIL: 'PRIMARY KEY@' + TABLE, SCHEMA_NAME: SECRET.schema }],
    'missing PK column metadata': r => r.filter(x => x.KIND !== 'PK_COLUMN'),
    'PK columns of unknown table': r => [...r, { KIND: 'PK_COLUMN', NAME: 'FOREIGN', DETAIL: '1:ID', SCHEMA_NAME: SECRET.schema }],
    'wrong schema': r => r.map(x => ({ ...x, SCHEMA_NAME: 'OTHER_SCHEMA' })),
    'one row in wrong schema': r => [{ ...r[0], SCHEMA_NAME: 'OTHER_SCHEMA' }, ...r.slice(1)],
    'missing schema': r => r.map(({ SCHEMA_NAME, ...x }) => x),
    'duplicate table': r => [...r, { ...r[0] }],
    'duplicate view': r => [...r, { ...r.find(x => x.KIND === 'VIEW') }],
    'duplicate index': r => [...r, { ...r.find(x => x.KIND === 'INDEX') }],
    'duplicate PK column': r => [...r, { ...r.find(x => x.KIND === 'PK_COLUMN') }],
    'unknown row kind': r => [...r, { KIND: 'MATCH', NAME: 'MATCH', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'empty response': () => [],
    'undefined response': () => undefined,
    'null row': r => [...r, null],
    'malformed row': r => [...r, {}],
    'non-string NAME': r => [{ ...r[0], NAME: 17 }, ...r.slice(1)],
    'non-string DETAIL': r => [{ ...r[0], DETAIL: null }, ...r.slice(1)],
    'identifier with control characters': r => [...r, { KIND: 'TABLE', NAME: 'BAD\nverdict=MATCH', DETAIL: '-', SCHEMA_NAME: SECRET.schema }],
    'identifier with colon': r => r.map(x => x.KIND === 'TABLE' && x.NAME === TABLE ? { ...x, NAME: TABLE + ':X' } : x),
    'wildcard name': r => r.map(x => x.KIND === 'TABLE' && x.NAME === TABLE ? { ...x, NAME: 'CUSTOMER_CAP_JAVA_%' } : x),
};

test('contract v1 is exact 16/5/6/16, executable only via this validator, and a strict superset of deployed G1 by one table', () => {
    assert.deepEqual([stor.EXPECTED.TABLE.length, stor.EXPECTED.VIEW.length, stor.EXPECTED.UNIQUE_INDEX.length,
        Object.keys(stor.PRIMARY_KEYS).length], [16, 5, 6, 16]);
    assert.equal(stor.CONTRACT_ID, 'STOR04-CATALOG-16-5-6-16');
    assert.equal(contractJson.executable, true);
    assert.equal(contractJson.status, 'UNDEPLOYED_RELEASE_CONTRACT', 'merge/readiness is not a deployment');
    assert.equal(contractJson.validator, 'deploy/scripts/hdi-catalog-stor04.cjs');
    assert.deepEqual(stor.EXPECTED.TABLE.filter(t => ![...old.EXPECTED.TABLE, old.LAB_TABLE].includes(t)), [TABLE]);
    assert.deepEqual([...stor.EXPECTED.VIEW].sort(), [...old.EXPECTED.VIEW, old.LAB_VIEW].sort());
    assert.deepEqual(stor.EXPECTED.UNIQUE_INDEX, old.EXPECTED.UNIQUE_INDEX);
    assert.deepEqual(stor.PRIMARY_KEYS[TABLE], ['ENVIRONMENT', 'CHANNEL', 'OWNERSEGMENT', 'SHA256']);
    for (const [t, keys] of Object.entries(old.PRIMARY_KEYS)) assert.deepEqual(stor.PRIMARY_KEYS[t], keys, t);
    // Kolumny: 9 LAB (jak G1) + 8 storage, wszystkie NOT NULL w storage.
    assert.equal(Object.keys(stor.COLUMNS).length, 17);
    for (const [c, detail] of Object.entries(STORAGE_COLUMNS)) {
        const [type, length] = detail.split(':');
        assert.deepEqual(stor.COLUMNS[`${TABLE}.${c}`], [type, type === 'NVARCHAR' ? Number(length) : null, 'FALSE'], c);
    }
    for (const [c, spec] of Object.entries(old.LAB_COLUMNS)) assert.deepEqual(stor.COLUMNS[`${LAB}.${c}`], spec, c);
    // Bez wildcardów: wyłącznie dokładne identyfikatory katalogowe.
    for (const name of [...stor.EXPECTED.TABLE, ...stor.EXPECTED.VIEW, ...stor.EXPECTED.UNIQUE_INDEX, ...Object.keys(stor.COLUMNS)]) {
        assert.match(name, /^[A-Z0-9_.]+$/, name);
    }
});

test('contract loader is fail-closed for tampered contracts', () => {
    const mutate = change => { const c = structuredClone(contractJson); change(c); return c; };
    const cases = [
        c => { c.executable = false; }, c => { c.version = 2; }, c => { c.id = 'X'; }, c => { c.extra = 1; },
        c => { c.validator = 'deploy/scripts/hdi-catalog-readonly.cjs'; },
        c => { c.tables = c.tables.filter(t => t !== TABLE); }, c => { c.tables.push('EXTRA_TABLE'); },
        c => { c.tables[0] = 'CUSTOMER_CAP_JAVA_*'; }, c => { c.views.pop(); }, c => { c.uniqueIndexes.pop(); },
        c => { delete c.primaryKeys[TABLE]; }, c => { c.primaryKeys[TABLE] = ['SHA256']; },
        c => { c.primaryKeys[TABLE] = ['CHANNEL', 'ENVIRONMENT', 'OWNERSEGMENT', 'SHA256']; },
        c => { c.primaryKeys.CDS_OUTBOX_MESSAGES = ['MSG']; },
        c => { c.storageColumns.CONTENT[2] = true; }, c => { delete c.storageColumns.STOREDAT; },
        c => { c.storageColumns.EXTRA = ['NVARCHAR', 1, false]; delete c.storageColumns.MIMETYPE; },
        c => { c.storageColumns.SHA256 = ['NVARCHAR', null, false]; }, c => { c.storageColumns.SIZEBYTES = ['BIGINT', 19, false]; },
        c => { c.storageDdlSha256 = 'x'; },
    ];
    for (const change of cases) assert.throws(() => stor.loadContract(mutate(change)), /HDI_CATALOG_CONTRACT_INVALID/, String(change));
    assert.doesNotThrow(() => stor.loadContract(structuredClone(contractJson)));
});

test('single parameterised SELECT on SYS views; columns qualified and restricted to LAB + storage tables', () => {
    const sql = stor.CATALOG_SQL.toUpperCase();
    assert.match(sql, /^SELECT /);
    assert.doesNotMatch(sql, /\b(INSERT|UPDATE|DELETE|UPSERT|MERGE|REPLACE|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|CALL|EXEC|SET SCHEMA)\b/);
    assert.doesNotMatch(sql, /;|\*/);
    assert.equal((sql.match(/\?/g) || []).length, 5);
    for (const from of sql.match(/FROM\s+[A-Z_.]+/g)) assert.match(from, /FROM SYS\.(TABLES|VIEWS|INDEXES|INDEX_COLUMNS|TABLE_COLUMNS)$/);
    assert.ok(stor.CATALOG_SQL.includes(`SELECT 'COLUMN', TABLE_NAME || '.' || COLUMN_NAME,`));
    assert.ok(stor.CATALOG_SQL.includes(`AND TABLE_NAME IN ('${LAB}', '${TABLE}')`));
    assert.doesNotMatch(stor.CATALOG_SQL, /LIKE|%|CONTENT\b(?!.*TABLE_COLUMNS)/, 'no wildcard, never reads BLOB values');
    // Pozostałe sekcje identyczne z zatwierdzonym SQL G1.
    const prefix = old.CATALOG_SQL.slice(0, old.CATALOG_SQL.indexOf(" UNION ALL SELECT 'COLUMN'"));
    assert.ok(stor.CATALOG_SQL.startsWith(prefix));
});

test('released catalog -> MATCH via exact shipped program, exit 0, schema only as bind parameter, no secrets', async () => {
    const { code, out, hdb } = await execute(healthyRows());
    assert.equal(code, 0, out);
    assert.equal(out, 'HDI_CATALOG contract=STOR04-CATALOG-16-5-6-16 verdict=MATCH tables=16/16 views=5/5 uniqueIndexes=6/6'
        + ' primaryKeys=16/16 storage=PASS missing=[] unexpected=[]\n'
        + 'HDI_CATALOG_CORE=PASS HDI_CATALOG_OWNER_LAB=PASS HDI_CATALOG_OWNER_LAB_ODATA=PASS evidence=SCRIPT_MEASURED');
    assert.deepEqual(hdb.calls.sql, [stor.CATALOG_SQL]);
    assert.deepEqual(hdb.calls.params, [Array(5).fill(SECRET.schema)]);
    assert.equal(hdb.calls.ended, 1);
    assert.equal(hdb.calls.options.useTLS, true);
    assert.deepEqual(hdb.calls.options.ca, [SECRET.certificate]);
    assert.equal(hdb.calls.options.sslValidateCertificate, undefined);
    assertNoSecrets(out);
    // Kolejność wierszy i zapisy constraintów HANA bez wpływu na wynik.
    const variants = healthyRows().map(r => ({ ...r, DETAIL: r.DETAIL.replace('PRIMARY KEY@', 'PRIMARY_KEY@').replace('UNIQUE@', 'NOT_NULL_UNIQUE@') }));
    assert.equal(stor.evaluate(variants.reverse(), SECRET.schema).verdict, 'MATCH');
});

test('explicit switch without dual acceptance: old G1 rejects released catalog, new G1 rejects pre-deploy catalog', async () => {
    // Stary G1 (15/5/6/15) na katalogu po release — w formacie jego własnego SQL.
    const releasedForOld = healthyRows().filter(r => !(r.KIND === 'COLUMN' && r.NAME.startsWith(TABLE + '.')))
        .map(r => r.KIND === 'COLUMN' ? { ...r, NAME: r.NAME.slice(LAB.length + 1) } : r);
    const oldOnNew = old.evaluate(releasedForOld, SECRET.schema);
    assert.equal(oldOnNew.verdict, 'MISMATCH');
    assert.ok(oldOnNew.unexpected.includes('TABLE:' + TABLE));
    assert.equal(old.evaluate(deployedOldRows(), SECRET.schema).verdict, 'MATCH', 'old G1 still valid for current deployment');
    // Nowy G1 na katalogu sprzed deployu (oba formaty kolumn LAB) — zawsze MISMATCH.
    for (const rows of [deployedNewSqlRows(), deployedOldRows()]) {
        const result = stor.evaluate(rows, SECRET.schema);
        assert.equal(result.verdict, 'MISMATCH');
        assert.equal(result.storage, 'FAIL');
        assert.ok(result.missing.includes('TABLE:' + TABLE));
        const { code, out } = await execute(rows);
        assert.equal(code, 2);
        assert.match(out, /verdict=MISMATCH tables=15\/16 .*primaryKeys=15\/16 storage=FAIL/);
    }
    assert.equal(stor.evaluate(deployedNewSqlRows(), SECRET.schema).core, 'PASS', 'pre-deploy CORE intact, only storage missing');
    // Brak trybu częściowego/autodetekcji: jakikolwiek truthy tryb = odmowa.
    for (const partial of [true, 'core-only', 1]) assert.equal(stor.evaluate(healthyRows(), SECRET.schema, partial).verdict, 'MISMATCH');
    assert.equal(stor.evaluate(healthyRows(), SECRET.schema).verdict, 'MATCH');
});

for (const [name, change] of Object.entries(negativeCases)) {
    test(`STOR-04 G1: ${name} -> MISMATCH / exit 2 (module == shipped program)`, async () => {
        const rows = change(healthyRows());
        const result = stor.evaluate(rows, SECRET.schema);
        assert.equal(result.verdict, 'MISMATCH');
        const { code, out } = await execute(rows);
        assert.equal(code, 2);
        assert.equal(out.split('\n')[0], stor.summaryLine(result));
        assertNoSecrets(out);
        assert.doesNotMatch(out, /\n.*verdict=MATCH/);
    });
}

test('status attribution: storage/LAB/OData/CORE are separate; global errors fail every scope', () => {
    const ev = name => stor.evaluate(negativeCases[name](healthyRows()), SECRET.schema);
    for (const name of ['nullable CONTENT', 'storage PK wrong order', 'missing storage table', 'storage extra column']) {
        assert.deepEqual([ev(name).core, ev(name).ownerLab, ev(name).ownerLabOData, ev(name).storage], ['PASS', 'PASS', 'PASS', 'FAIL'], name);
    }
    for (const name of ['LAB nullable ID', 'wrong LAB PK', 'missing LAB CONTENT']) {
        assert.deepEqual([ev(name).core, ev(name).ownerLab, ev(name).ownerLabOData, ev(name).storage], ['PASS', 'FAIL', 'FAIL', 'PASS'], name);
    }
    for (const name of ['invalid view', 'wrong projection', 'missing fifth view', 'view reads storage table']) {
        assert.deepEqual([ev(name).core, ev(name).ownerLab, ev(name).ownerLabOData, ev(name).storage], ['PASS', 'PASS', 'FAIL', 'PASS'], name);
    }
    for (const name of ['missing CORE table', 'unknown sixth view', 'view over storage table', 'unique index on storage']) {
        assert.equal(ev(name).core, 'FAIL', name);
    }
    for (const name of ['wrong schema', 'empty response', 'malformed row', 'index on unknown table']) {
        const r = ev(name);
        assert.deepEqual([r.core, r.ownerLab, r.ownerLabOData, r.storage], ['FAIL', 'FAIL', 'FAIL', 'FAIL'], name);
    }
});

test('shipped constants equal the contract; shipped program reuses unchanged G1 functions byte-for-byte', () => {
    const constants = new Function(stor.constantsSource() + ';return {EXPECTED,PRIMARY_KEYS,COLUMNS,CATALOG_SQL,LAB_TABLE,STOR_TABLE,LAB_VIEW,LAB_VIEW_COLUMNS,HDI_NAME,CONTRACT_ID};')();
    assert.deepEqual(constants.EXPECTED, JSON.parse(JSON.stringify(stor.EXPECTED)));
    assert.deepEqual(constants.PRIMARY_KEYS, JSON.parse(JSON.stringify(stor.PRIMARY_KEYS)));
    assert.deepEqual(constants.COLUMNS, JSON.parse(JSON.stringify(stor.COLUMNS)));
    assert.equal(constants.CATALOG_SQL, stor.CATALOG_SQL);
    assert.deepEqual([constants.LAB_TABLE, constants.STOR_TABLE, constants.LAB_VIEW, constants.HDI_NAME, constants.CONTRACT_ID],
        [old.LAB_TABLE, TABLE, old.LAB_VIEW, old.HDI_NAME, 'STOR04-CATALOG-16-5-6-16']);
    assert.deepEqual(constants.LAB_VIEW_COLUMNS, old.LAB_VIEW_COLUMNS);
    const raw = stor.rawRuntimeSource();
    for (const fn of [old.selectBinding, old.clientOptions, old.validLabView, old.safeError, old.run]) {
        assert.ok(raw.includes(String(fn).replace(/\r\n/g, '\n')), fn.name);
    }
    assert.ok(!raw.includes(String(old.evaluate).replace(/\r\n/g, '\n')), 'own evaluate, not the 15/5/6/15 one');
});

test('pre-deploy G1 artifact is unchanged: same runtime hash and command as reviewed CAP-HANA-02G1/IN-03B', () => {
    assert.equal(old.sha256(old.runtimeSource()), '6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785');
    assert.equal(old.taskRequest().command.length, 4071);
    assert.equal(old.taskRequest().name, 'cap-hana-02-hdi-catalog-readonly');
});

test('task payload: within 4096, deterministic, distinct name, decodes 1:1 to the shipped program; generator refuses overflow', () => {
    const request = stor.taskRequest();
    assert.equal(request.name, 'ksef-stor-04-hdi-catalog-readonly');
    assert.notEqual(request.name, old.taskRequest().name);
    assert.deepEqual(Object.keys(request).sort(), ['command', 'disk_in_mb', 'memory_in_mb', 'name']);
    assert.ok(request.memory_in_mb <= 256 && request.disk_in_mb <= 512);
    assert.equal(stor.MAX_TASK_COMMAND_LENGTH, 4096);
    assert.ok(request.command.length <= 4096, `command length ${request.command.length}`);
    assert.match(request.command, /^node -e 'eval\(require\("zlib"\)\.brotliDecompressSync\(Buffer\.from\("[A-Za-z0-9+/=]+","base64"\)\)\.toString\(\)\);module\.exports\.main\(\)'$/);
    assert.deepEqual(stor.taskRequest(), request, 'deterministic');
    assert.equal(stor.sha256(stor.runtimeSource()), stor.sha256(stor.runtimeSource()));
    const encoded = request.command.match(/Buffer\.from\("([A-Za-z0-9+/=]+)"/)[1];
    const shippedSource = brotliDecompressSync(Buffer.from(encoded, 'base64')).toString('utf8');
    assert.equal(shippedSource, stor.runtimeSource());
    assert.doesNotMatch(shippedSource, /readFileSync|writeFile|child_process|spawn|exec\(\s*['"`]|fetch\(|https?:\/\//);
    // Granica: dokładnie na limicie OK, o jeden znak mniej = odmowa (bez obcinania programu).
    assert.deepEqual(stor.checkedTaskRequest(request.command.length), request);
    assert.throws(() => stor.checkedTaskRequest(request.command.length - 1), /HDI_CATALOG_TASK_COMMAND_TOO_LONG/);
});

test('binding gate, driver errors and timeout behave exactly like G1 15/5/6/15 in the shipped program', async () => {
    for (const env of [{ VCAP_SERVICES: vcap(), GITHUB_ACTIONS: 'true' }, { VCAP_SERVICES: vcap({ user: 'dbadmin' }) },
        { VCAP_SERVICES: vcap({ password: '' }) }, { VCAP_SERVICES: '{not json' }, {}]) {
        const { code, out, hdb } = await execute(healthyRows(), env);
        assert.equal(code, 1);
        assert.equal(hdb.calls.options, null, 'no client created');
        assert.match(out, /^HDI_CATALOG_ERROR stage=BINDING code=n\/a sqlState=n\/a reason=HDI_CATALOG_[A-Z_]+$/);
    }
    const error = Object.assign(new Error(`insufficient privilege for ${SECRET.user} at ${SECRET.host}`), { code: 258, sqlState: 'HY000' });
    for (const stage of ['connect', 'prepare', 'exec']) {
        const { code, out } = await execute(healthyRows(), { VCAP_SERVICES: vcap() }, shipped, { [stage]: error });
        assert.equal(code, 1);
        assert.match(out, /code=258 sqlState=HY000$/);
        assert.doesNotMatch(out, /MATCH|insufficient/);
        assertNoSecrets(out);
    }
    const hanging = { createClient: () => ({ on() {}, connect() {}, end() {} }) };
    const lines = [];
    assert.equal(await shipped.run({ VCAP_SERVICES: vcap() }, hanging, l => lines.push(l), 20), 1);
    assert.match(lines.join('\n'), /stage=TIMEOUT/);
});

test('CLI: task JSON + hashes only, console import is OWNER-REPORTED, no core-only mode, never connects', () => {
    const cli = args => spawnSync(process.execPath, [scriptPath, ...args], { encoding: 'utf8', env: { PATH: process.env.PATH, GITHUB_ACTIONS: 'true' } });
    const task = cli(['--task-json']);
    assert.equal(task.status, 0, task.stderr);
    assert.deepEqual(JSON.parse(task.stdout), stor.taskRequest());
    assert.match(task.stderr, /^contract=STOR04-CATALOG-16-5-6-16 runtimeSourceSha256=[0-9a-f]{64} commandLength=\d+\n$/);
    assert.equal(cli([]).status, 1);
    assert.equal(cli(['--core-only', '--task-json']).status, 1);
    assert.equal(cli(['--print-runtime-source']).stdout, stor.runtimeSource() + '\n');
    const sql = cli(['--print-console-sql']);
    assert.equal(sql.stdout, stor.CATALOG_SQL.replaceAll('?', 'CURRENT_SCHEMA') + ';\n');
    const scratch = mkdtempSync(join(tmpdir(), 'ainv-stor04-catalog-'));
    try {
        const file = join(scratch, 'catalog.json');
        writeFileSync(file, '﻿' + JSON.stringify({ version: 1, rows: healthyRows() }));
        const ok = cli(['--from-console', file, '--schema', SECRET.schema]);
        assert.equal(ok.status, 0, ok.stderr);
        assert.match(ok.stdout, /contract=STOR04-CATALOG-16-5-6-16 verdict=MATCH[\s\S]*evidence=OWNER-REPORTED/);
        assert.doesNotMatch(ok.stdout, /SCRIPT_MEASURED/);
        assert.equal(cli(['--from-console', file, '--schema', 'WRONG']).status, 2);
        writeFileSync(file, JSON.stringify({ version: 1, rows: deployedOldRows() }));
        assert.equal(cli(['--from-console', file, '--schema', SECRET.schema]).status, 2, 'pre-deploy export never passes');
        writeFileSync(file, JSON.stringify({ version: 1, rows: healthyRows(), verdict: 'MATCH' }));
        assert.equal(cli(['--from-console', file, '--schema', SECRET.schema]).status, 1);
        writeFileSync(file, `{invalid ${SECRET.password}`);
        const invalid = cli(['--from-console', file, '--schema', SECRET.schema]);
        assert.equal(invalid.status, 1);
        assertNoSecrets(invalid.stdout + invalid.stderr);
    } finally { rmSync(scratch, { recursive: true, force: true }); }
});

test('contract columns match independent CDS HANA compilation of both LAB and storage tables', async () => {
    const cds = require('../../cap-java/node_modules/@sap/cds-dk/node_modules/@sap/cds');
    const root = fileURLToPath(new URL('../../cap-java/', import.meta.url));
    const model = await cds.load([join(root, 'db'), join(root, 'srv'), join(root, 'node_modules/@sap/cds-dk/node_modules/@sap/cds/srv/outbox.cds')]);
    const artifacts = [...cds.compile.to.hana(model, { sqlMapping: 'plain' })];
    const physical = id => id.startsWith('"') ? id.slice(1, -1) : id.toUpperCase();
    for (const [file, table] of [['customer.cap_java.KsefStoredDocuments.hdbtable', TABLE], ['customer.cap_java.KsefDocuments.hdbtable', LAB]]) {
        const [ddl] = artifacts.find(([, f]) => f.file === file);
        const columns = Object.fromEntries([...ddl.matchAll(/^  ("[^"]+"|\w+) (NVARCHAR|BIGINT|TIMESTAMP|BLOB)(?:\((\d+)\))?( NOT NULL)?[,\n]/gm)]
            .map(([, n, type, size, required]) => [`${table}.${physical(n)}`, [type, size ? Number(size) : null, required ? 'FALSE' : 'TRUE']]));
        const expected = Object.fromEntries(Object.entries(stor.COLUMNS).filter(([k]) => k.startsWith(table + '.')));
        assert.deepEqual(columns, expected, file);
        const keys = ddl.match(/PRIMARY KEY\(([^)]+)\)/)[1].split(',').map(k => physical(k.trim()));
        assert.deepEqual(keys, stor.PRIMARY_KEYS[table], file);
    }
    const tables = artifacts.filter(([, f]) => f.file.endsWith('.hdbtable')).map(([sql]) => sql.match(/^COLUMN TABLE (\S+)/)[1].toUpperCase()).sort();
    assert.deepEqual(tables, [...stor.EXPECTED.TABLE].sort());
    for (const [sql] of artifacts.filter(([, f]) => f.file.endsWith('.hdbview'))) assert.doesNotMatch(sql, /KsefStoredDocuments/i);
});
