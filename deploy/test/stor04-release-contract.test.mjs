import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import test from 'node:test';

const require = createRequire(import.meta.url);
const current = require('../scripts/hdi-catalog-readonly.cjs');
const future = JSON.parse(readFileSync(new URL('../contracts/hdi-catalog-stor04.v1.json', import.meta.url)));
const table = 'CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS';

test('future STOR-04 contract names exact catalog while active G1 stays on deployed catalog', () => {
    // STOR-04R: kontrakt wykonuje wyłącznie osobny walidator release; status nadal = niewdrożony.
    assert.equal(future.executable, true);
    assert.equal(future.validator, 'deploy/scripts/hdi-catalog-stor04.cjs');
    assert.equal(future.status, 'UNDEPLOYED_RELEASE_CONTRACT');
    assert.deepEqual(future.tables, [...current.EXPECTED.TABLE, current.LAB_TABLE, table].sort());
    assert.deepEqual(future.views, [...current.EXPECTED.VIEW, current.LAB_VIEW].sort());
    assert.deepEqual(future.uniqueIndexes, current.EXPECTED.UNIQUE_INDEX);
    assert.deepEqual(future.primaryKeys, {...current.PRIMARY_KEYS,
        [table]: ['ENVIRONMENT', 'CHANNEL', 'OWNERSEGMENT', 'SHA256']});
    assert.deepEqual([future.tables.length, future.views.length, future.uniqueIndexes.length,
        Object.keys(future.primaryKeys).length], [16, 5, 6, 16]);
    assert.equal(current.EXPECTED.TABLE.includes(table), false);
    assert.equal(table in current.PRIMARY_KEYS, false);
});

test('future storage DDL hash and complete column contract match independent CDS HANA compilation', async () => {
    const cds = require('../../cap-java/node_modules/@sap/cds-dk/node_modules/@sap/cds');
    const root = fileURLToPath(new URL('../../cap-java/', import.meta.url));
    const model = await cds.load([join(root, 'db'), join(root, 'srv'),
        join(root, 'node_modules/@sap/cds-dk/node_modules/@sap/cds/srv/outbox.cds')]);
    const artifacts = [...cds.compile.to.hana(model, {sqlMapping: 'plain'})];
    const [ddl] = artifacts.find(([, f]) => f.file === 'customer.cap_java.KsefStoredDocuments.hdbtable');
    assert.equal(createHash('sha256').update(ddl).digest('hex'), future.storageDdlSha256);
    const columns = Object.fromEntries([...ddl.matchAll(/^\s+(?:"(\w+)"|(\w+)) (NVARCHAR|BIGINT|TIMESTAMP|BLOB)(?:\((\d+)\))? NOT NULL[,\n]/gm)]
        .map(([, quoted, plain, type, length]) => [quoted ?? plain.toUpperCase(),
            [type, length ? Number(length) : null, false]]));
    assert.deepEqual(columns, future.storageColumns);
});
