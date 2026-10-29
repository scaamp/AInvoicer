/*global QUnit*/
sap.ui.define([
    "sap/ui/model/json/JSONModel",
    "sap/ui/model/Filter",
    "ainvoicer/model/ksef/KsefFilterBuilder",
    "ainvoicer/model/ksef/KsefAiFilterAdapter"
], function (JSONModel, Filter, KsefFilterBuilder, KsefAiFilterAdapter) {
    "use strict";

    var ROWS = [
        { InvoiceUUID: "1", InvoiceNumber: "AINV-TEST-SEED-2026-FI-AP", Direction: "INBOUND", OriginProcess: "FI_AP", CompanyCode: "1000",
            IssueDate: "2026-10-01", PostingDate: "2026-10-02", GrossAmount: 1845, CurrencyCode: "PLN", SellerName: "Test Dostawca Alfa",
            SellerNip: "5250001009", BuyerName: "AInvoicer Test Buyer FI", BuyerNip: "5260002008", KsefStatus: "NO_OPERATION", KsefNumber: "" },
        { InvoiceUUID: "2", InvoiceNumber: "AINV-TEST-SEED-2026-FI-AR", Direction: "OUTBOUND", OriginProcess: "FI_AR", CompanyCode: "1000",
            IssueDate: "2026-10-03", PostingDate: "2026-10-03", GrossAmount: 2706, CurrencyCode: "PLN", SellerName: "AInvoicer Test Seller FI",
            SellerNip: "5260002008", BuyerName: "Test Odbiorca Beta", BuyerNip: "5210003008", KsefStatus: "NO_OPERATION", KsefNumber: "" },
        { InvoiceUUID: "3", InvoiceNumber: "AINV-DEMO-2026-SD-BILL-0002", Direction: "OUTBOUND", OriginProcess: "SD_BILL", CompanyCode: "2000",
            IssueDate: "2026-10-14", PostingDate: "2026-10-14", GrossAmount: 12054, CurrencyCode: "PLN", SellerName: "AInvoicer Test Seller SD",
            SellerNip: "5260002008", BuyerName: "Test Odbiorca Beta", BuyerNip: "5210003008", KsefStatus: "PROCESSING", KsefNumber: "" },
        { InvoiceUUID: "4", InvoiceNumber: "AINV-DEMO-2026-MM-IV-0002", Direction: "INBOUND", OriginProcess: "MM_IV", CompanyCode: "1000",
            IssueDate: "2026-10-18", PostingDate: "2026-10-19", GrossAmount: 1119.3, CurrencyCode: "PLN", SellerName: "Test Dostawca Alfa",
            SellerNip: "5250001009", BuyerName: "AInvoicer Test Buyer MM", BuyerNip: "5260002008", KsefStatus: "RECEIVED", KsefNumber: "5250001009-20261018-X" }
    ];

    function apply(aFilters) {
        var oBinding = new JSONModel({ rows: ROWS }).bindList("/rows");
        oBinding.filter(aFilters.length ? new Filter({ filters: aFilters, and: true }) : []);
        return oBinding.getContexts(0, 100).map(function (oContext) {
            return oContext.getProperty("InvoiceUUID");
        });
    }

    QUnit.module("KsefFilterBuilder");

    QUnit.test("view switch filters by direction", function (assert) {
        assert.deepEqual(apply(KsefFilterBuilder.build(null, "ALL")), ["1", "2", "3", "4"]);
        assert.deepEqual(apply(KsefFilterBuilder.build(null, "PURCHASE")), ["1", "4"]);
        assert.deepEqual(apply(KsefFilterBuilder.build(null, "SALES")), ["2", "3"]);
    });

    QUnit.test("search is case-insensitive across number, parties, NIP and KSeF number", function (assert) {
        var oState = KsefFilterBuilder.createEmptyState();
        oState.search = "demo";
        assert.deepEqual(apply(KsefFilterBuilder.build(oState, "ALL")), ["3", "4"]);
        oState.search = "5210003008";
        assert.deepEqual(apply(KsefFilterBuilder.build(oState, "SALES")), ["2", "3"]);
    });

    QUnit.test("multi-select, date range and amount range combine with AND", function (assert) {
        var oState = KsefFilterBuilder.createEmptyState();
        oState.originProcesses = ["FI_AR", "SD_BILL", "MM_IV"];
        oState.companyCodes = ["1000"];
        oState.issueDateFrom = "2026-10-02";
        oState.issueDateTo = "2026-10-31";
        oState.amountFrom = "1000";
        oState.amountTo = "3000";
        assert.deepEqual(apply(KsefFilterBuilder.build(oState, "ALL")), ["2", "4"]);
        oState.ksefStatuses = ["RECEIVED"];
        assert.deepEqual(apply(KsefFilterBuilder.build(oState, "ALL")), ["4"]);
    });

    QUnit.test("empty/invalid amount inputs are ignored", function (assert) {
        var oState = KsefFilterBuilder.createEmptyState();
        oState.amountFrom = "abc";
        assert.strictEqual(KsefFilterBuilder.build(oState, "ALL").length, 0);
    });

    QUnit.test("counts and distinct values", function (assert) {
        assert.deepEqual(KsefFilterBuilder.countByView(ROWS), { ALL: 4, PURCHASE: 2, SALES: 2 });
        assert.deepEqual(KsefFilterBuilder.distinctValues(ROWS, "CompanyCode"), ["1000", "2000"]);
    });

    QUnit.module("KsefAiFilterAdapter");

    QUnit.test("legacy /filter paths are mapped; approximate mappings are flagged", function (assert) {
        var oResult = KsefAiFilterAdapter.translate({
            filters: [
                { path: "AmountDocument", operator: "GT", value1: "2000" },
                { path: "CompanyCode", operator: "EQ", value1: "1000" }
            ]
        });
        assert.strictEqual(oResult.applied.length, 2);
        assert.deepEqual(oResult.applied[0], { path: "GrossAmount", mappedFrom: "AmountDocument", approximate: true, operator: "GT", value1: 2000, value2: undefined });
        assert.strictEqual(oResult.applied[1].approximate, false);
        assert.deepEqual(apply(oResult.filters), ["2"]);
    });

    QUnit.test("native KSeF fields work, enum values are upper-cased", function (assert) {
        var oResult = KsefAiFilterAdapter.translate({
            filters: [{ path: "Direction", operator: "EQ", value1: "inbound" }, { path: "SellerNip", operator: "Contains", value1: "52500" }]
        });
        assert.deepEqual(apply(oResult.filters), ["1", "4"]);
        assert.strictEqual(oResult.applied[0].value1, "INBOUND");
    });

    QUnit.test("FiscalYear EQ maps to an IssueDate range; BT dates are validated", function (assert) {
        var oResult = KsefAiFilterAdapter.translate({
            filters: [
                { path: "FiscalYear", operator: "EQ", value1: "2026" },
                { path: "PostingDate", operator: "BT", value1: "2026-10-01", value2: "2026-10-05" }
            ]
        });
        assert.deepEqual(oResult.applied[0], { path: "IssueDate", mappedFrom: "FiscalYear", approximate: true, operator: "BT", value1: "2026-01-01", value2: "2026-12-31" });
        assert.deepEqual(apply(oResult.filters), ["1", "2"]);
    });

    QUnit.test("unsupported fields, operators and values are ignored and reported — never executed", function (assert) {
        var oResult = KsefAiFilterAdapter.translate({
            filters: [
                { path: "Vendor", operator: "EQ", value1: "888888" },
                { path: "CompanyCode", operator: "LIKE", value1: "1%" },
                { path: "GrossAmount", operator: "GT", value1: "1; DROP TABLE x" },
                { path: "IssueDate", operator: "EQ", value1: "yesterday" },
                { path: "GrossAmount", operator: "Contains", value1: "1" },
                { path: "__proto__", operator: "EQ", value1: "x" },
                null
            ],
            sql: "DELETE FROM ZFI_ACDOCA"
        });
        assert.strictEqual(oResult.filters.length, 0);
        assert.deepEqual(oResult.ignored.map(function (o) { return o.reason; }),
            ["unsupportedField", "unsupportedOperator", "invalidValue", "invalidValue", "unsupportedOperator", "unsupportedField", "invalid"]);
    });

    QUnit.test("sort and limit are translated with bounds", function (assert) {
        var oResult = KsefAiFilterAdapter.translate({ sortBy: { path: "AmountDocument", descending: true }, limit: 5000 });
        assert.strictEqual(oResult.sorter.sPath, "GrossAmount");
        assert.strictEqual(oResult.sorter.bDescending, true);
        assert.strictEqual(oResult.limit, 1000);
        assert.strictEqual(KsefAiFilterAdapter.translate({ limit: -1 }).limit, null);
        assert.strictEqual(KsefAiFilterAdapter.translate(null).filters.length, 0);
    });
});
