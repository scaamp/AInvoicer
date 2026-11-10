/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundInvoiceMapper"
], function (Mapper) {
    "use strict";

    var ID = "1b0e5c7a-4f2d-4c11-9a3e-000000000001";

    function dto(mOverrides) {
        return Object.assign({
            id: ID,
            environment: "TEST",
            ksefNumber: "2222222222-20261101-000000000010-2E",
            invoiceNumber: "FV/100/2026",
            issueDate: "2026-11-09",
            acquisitionDate: "2026-11-09T10:00:00Z",
            sellerNip: "2222222222",
            sellerName: null,
            buyerIdentifierType: "Nip",
            buyerIdentifierValue: "1111111111",
            currency: "PLN",
            grossAmount: "1234.56",
            ingestionStatus: "STORED",
            parseStatus: "PARSED",
            integrityStatus: "VERIFIED",
            sapPostingStatus: "NOT_POSTED",
            xmlAvailable: true
        }, mOverrides || {});
    }

    QUnit.module("InboundInvoiceMapper — DTO contract");

    QUnit.test("maps the working DTO 1:1 and keeps money as a decimal string", function (assert) {
        var oRow = Mapper.toRow(dto());
        assert.strictEqual(oRow.id, ID);
        assert.strictEqual(oRow.grossAmount, "1234.56", "string, not Number");
        assert.strictEqual(typeof oRow.grossAmount, "string");
        assert.ok(oRow.grossAmountValid);
        assert.strictEqual(oRow.sellerName, null, "null stays null (no invented name)");
        assert.strictEqual(oRow.xmlAvailable, true);
        assert.deepEqual(oRow.issues, [], "complete metadata has no issues");
        assert.strictEqual(oRow.incomplete, false);
        assert.strictEqual(Mapper.rowHighlight(oRow), "None");
    });

    QUnit.test("records without a valid id are rejected and counted, others kept", function (assert) {
        var oResult = Mapper.toRows([dto(), dto({ id: null }), dto({ id: "x' or 1 eq 1" }), null, "text"]);
        assert.strictEqual(oResult.rows.length, 1);
        assert.strictEqual(oResult.rejected, 4);
        assert.deepEqual(Mapper.toRows(undefined), { rows: [], rejected: 0 });
    });

    QUnit.test("null and incomplete metadata are explicit issues, never defaults", function (assert) {
        var oRow = Mapper.toRow({ id: ID });
        assert.strictEqual(oRow.invoiceNumber, null);
        assert.strictEqual(oRow.grossAmount, null);
        assert.strictEqual(oRow.currency, null);
        assert.strictEqual(oRow.issueDate, null);
        assert.strictEqual(oRow.sapPostingStatus, null, "unknown SAP status is not assumed NOT_POSTED");
        assert.strictEqual(oRow.xmlAvailable, false, "missing flag is not 'available'");
        ["MISSING_INVOICE_NUMBER", "MISSING_KSEF_NUMBER", "MISSING_ISSUE_DATE", "MISSING_SELLER_NIP",
            "MISSING_AMOUNT", "MISSING_CURRENCY"].forEach(function (sIssue) {
            assert.ok(oRow.issues.indexOf(sIssue) !== -1, sIssue);
        });
        assert.strictEqual(Mapper.rowHighlight(oRow), "Warning");
    });

    QUnit.test("buyer without NIP is a normal case (VatUe / None)", function (assert) {
        var oVat = Mapper.toRow(dto({ buyerIdentifierType: "VatUe", buyerIdentifierValue: "DE000000001" }));
        assert.strictEqual(oVat.buyerIdentifierType, "VatUe");
        assert.deepEqual(oVat.issues, [], "missing buyer NIP is not a data issue");
        var oNone = Mapper.toRow(dto({ buyerIdentifierType: "None", buyerIdentifierValue: null }));
        assert.strictEqual(oNone.buyerIdentifierValue, null);
        assert.deepEqual(oNone.issues, []);
    });

    QUnit.test("invalid amount and currency are shown as received and flagged", function (assert) {
        var oRow = Mapper.toRow(dto({ grossAmount: "12,34", currency: "pln" }));
        assert.strictEqual(oRow.grossAmount, "12,34");
        assert.notOk(oRow.grossAmountValid);
        assert.ok(oRow.issues.indexOf("INVALID_AMOUNT") !== -1);
        assert.strictEqual(oRow.currency, "pln", "not silently upper-cased");
        assert.ok(oRow.issues.indexOf("INVALID_CURRENCY") !== -1);

        var oNumber = Mapper.toRow(dto({ grossAmount: 1234.5 }));
        assert.notOk(oNumber.grossAmountValid, "JSON number may have lost precision — not trusted as decimal");
        assert.ok(oNumber.issues.indexOf("INVALID_AMOUNT") !== -1);
        assert.notOk(Mapper.toRow(dto({ grossAmount: "1e5" })).grossAmountValid, "no exponent");
    });

    QUnit.test("impossible dates and malformed timestamps are rejected", function (assert) {
        var oRow = Mapper.toRow(dto({ issueDate: "2026-02-30", acquisitionDate: "yesterday" }));
        assert.strictEqual(oRow.issueDate, null);
        assert.strictEqual(oRow.acquisitionDate, null);
        assert.ok(oRow.issues.indexOf("INVALID_DATE") !== -1);
    });

    QUnit.test("status values outside the safe pattern are dropped; unknown valid codes are kept", function (assert) {
        var oRow = Mapper.toRow(dto({ ingestionStatus: "QUARANTINED", parseStatus: "<b>x</b>", integrityStatus: 5 }));
        assert.strictEqual(oRow.ingestionStatus, "QUARANTINED", "unknown code kept verbatim for display");
        assert.strictEqual(oRow.parseStatus, null);
        assert.strictEqual(oRow.integrityStatus, null);
    });

    QUnit.module("InboundInvoiceMapper — KSeF number and highlight");

    QUnit.test("long KSeF numbers are shortened visually, full value is kept", function (assert) {
        var sFull = "2222222222-20261101-000000000010-2E";
        var oRow = Mapper.toRow(dto({ ksefNumber: sFull }));
        assert.strictEqual(oRow.ksefNumber, sFull);
        assert.strictEqual(oRow.ksefNumberShort, "2222222222-…000010-2E");
        assert.ok(oRow.ksefNumberShort.length < sFull.length);

        var sLong = "7777777777-20261019-ABCDEF012345-ABCDEF0123456789-1";
        var oLong = Mapper.toRow(dto({ ksefNumber: sLong }));
        assert.strictEqual(oLong.ksefNumber, sLong, "non-standard value preserved (backend validates CRC)");
        assert.ok(oLong.issues.indexOf("NONSTANDARD_KSEF_NUMBER") !== -1);
        assert.strictEqual(Mapper.shortenKsefNumber("SHORT-1"), "SHORT-1");
        assert.strictEqual(Mapper.shortenKsefNumber(null), "");
    });

    QUnit.test("integrity conflict, hash mismatch, download failure and invalid XML are errors; unsupported schema is a warning", function (assert) {
        assert.strictEqual(Mapper.rowHighlight(Mapper.toRow(dto({ integrityStatus: "CONFLICT" }))), "Error");
        assert.strictEqual(Mapper.rowHighlight(Mapper.toRow(dto({ integrityStatus: "MISMATCH" }))), "Error");
        assert.strictEqual(Mapper.rowHighlight(Mapper.toRow(dto({ ingestionStatus: "DOWNLOAD_FAILED" }))), "Error");
        assert.strictEqual(Mapper.rowHighlight(Mapper.toRow(dto({ parseStatus: "INVALID" }))), "Error");
        assert.strictEqual(Mapper.rowHighlight(Mapper.toRow(dto({ parseStatus: "UNSUPPORTED_SCHEMA" }))), "Warning");
    });

    QUnit.module("InboundInvoiceMapper — decimal comparison without Number");

    QUnit.test("compareDecimal orders values beyond double precision", function (assert) {
        assert.ok(Mapper.compareDecimal("98765432109876.54", "98765432109876.53") > 0, "differs only in the last cent");
        assert.ok(Mapper.compareDecimal("9007199254740993.01", "9007199254740992.99") > 0, "above 2^53");
        assert.strictEqual(Mapper.compareDecimal("10.50", "10.5"), 0);
        assert.strictEqual(Mapper.compareDecimal("-0.00", "0"), 0);
        assert.ok(Mapper.compareDecimal("-1230.00", "5.00") < 0);
        assert.ok(Mapper.compareDecimal("-2", "-10") > 0);
        assert.ok(Mapper.compareDecimal("100", "99.99") > 0);
    });
});
