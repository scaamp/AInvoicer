/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/KsefInvoiceMapper"
], function (KsefInvoiceMapper) {
    "use strict";

    var UUID = "f7ac01d0-ac15-5b56-a77b-ec4e718ced7d";

    function header(mOverrides) {
        return Object.assign({
            InvoiceUUID: UUID,
            InvoiceNumber: "AINV-TEST-SEED-2026-FI-AR",
            OriginProcess: "FI_AR",
            Direction: "OUTBOUND",
            InvoiceKind: "STANDARD",
            InvoiceStatus: "NEW",
            KsefNumber: "",
            KsefEnvironment: "",
            KsefRecordedAt: null,
            KsefWriteRequestId: null,
            CompanyCode: "1000",
            IssueDate: "2026-10-03",
            SellerNip: "5260002008",
            SellerName: "AInvoicer Test Seller FI",
            BuyerNip: "5210003008",
            BuyerName: "Test Odbiorca Beta Sp z o o",
            NetAmount: "2200.00",
            TaxAmount: "506.00",
            GrossAmount: "2706.00",
            CurrencyCode: "PLN",
            LocalLastChangedAt: "2026-10-26T08:00:00.0000000Z"
        }, mOverrides);
    }

    function operation(mOverrides) {
        return Object.assign({
            ID: "11111111-1111-4111-8111-111111111111",
            sapDocumentRef: UUID,
            operationType: "SEND_INVOICE",
            status: "NEW",
            environment: "TEST",
            referenceNumber: "",
            ksefNumber: "",
            createdAt: "2026-10-28T10:00:00Z",
            modifiedAt: "2026-10-28T10:00:00Z"
        }, mOverrides);
    }

    QUnit.module("KsefInvoiceMapper");

    QUnit.test("maps RAP header fields and parses Edm.Decimal strings", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header(), []);
        assert.strictEqual(oRow.InvoiceNumber, "AINV-TEST-SEED-2026-FI-AR");
        assert.strictEqual(oRow.Direction, "OUTBOUND");
        assert.strictEqual(oRow.GrossAmount, 2706, "decimal string → number for sorting/filtering");
        assert.strictEqual(oRow.NetAmount, 2200);
        assert.strictEqual(oRow.KsefStatus, "NO_OPERATION");
        assert.strictEqual(oRow.KsefStatusSource, "UI");
        assert.strictEqual(oRow.LatestOperation, null);
        assert.deepEqual(oRow.Items, []);
    });

    QUnit.test("latest CAP operation (by modifiedAt) defines KSeF status and reference", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header(), [
            operation({ ID: "a", status: "REJECTED", modifiedAt: "2026-10-16T11:02:30Z", referenceNumber: "OLD" }),
            operation({ ID: "b", status: "PROCESSING", modifiedAt: "2026-10-29T07:46:10Z", referenceNumber: "NEW-REF" })
        ]);
        assert.strictEqual(oRow.KsefStatus, "PROCESSING");
        assert.strictEqual(oRow.KsefStatusSource, "CAP");
        assert.strictEqual(oRow.OperationReference, "NEW-REF");
        assert.strictEqual(oRow.OperationCount, 2);
        assert.strictEqual(oRow.LastUpdate, "2026-10-29T07:46:10Z", "LastUpdate = newest of RAP and CAP timestamps");
    });

    QUnit.test("KSeF number recorded in SAP wins and yields RECORDED_IN_SAP without CAP operation", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header({ KsefNumber: "5260002008-20261012-X-0A", KsefEnvironment: "TEST" }), []);
        assert.strictEqual(oRow.KsefStatus, "RECORDED_IN_SAP");
        assert.strictEqual(oRow.KsefStatusSource, "RAP");
        assert.strictEqual(oRow.KsefNumber, "5260002008-20261012-X-0A");
        assert.strictEqual(oRow.KsefEnvironment, "TEST");
    });

    QUnit.test("groups CAP operations by sapDocumentRef case-insensitively", function (assert) {
        var aRows = KsefInvoiceMapper.toInvoiceRows(
            [header(), header({ InvoiceUUID: "55ac244b-67e8-596f-a22f-7f20c57197cb", InvoiceNumber: "OTHER" })],
            [operation({ sapDocumentRef: UUID.toUpperCase(), status: "ACCEPTED", ksefNumber: "K-1" }), operation({ sapDocumentRef: null })]);
        assert.strictEqual(aRows.length, 2);
        assert.strictEqual(aRows[0].KsefStatus, "ACCEPTED_SAP_SYNC", "CAP ACCEPTED without RAP number = SAP synchronization");
        assert.strictEqual(aRows[0].KsefNumber, "K-1", "CAP KSeF number used when SAP has none yet");
        assert.strictEqual(aRows[1].KsefStatus, "NO_OPERATION", "operation without sapDocumentRef is not attached");
    });

    QUnit.test("maps expanded _Items and _References", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header({
            _Items: [{ ItemNo: "000010", Quantity: "3.000", UnitPrice: "400.00", NetAmount: "1200.00", TaxRate: "23.00", TaxAmount: "276.00", GrossAmount: "1476.00" }],
            _References: [{ ReferenceRole: "ACCOUNTING_DOC", SourceDocNo: "SIM-FI-AR-2026-001" }]
        }), []);
        assert.strictEqual(oRow.Items.length, 1);
        assert.strictEqual(oRow.Items[0].Quantity, 3);
        assert.strictEqual(oRow.Items[0].GrossAmount, 1476);
        assert.strictEqual(oRow.References[0].SourceDocNo, "SIM-FI-AR-2026-001");
    });
});
