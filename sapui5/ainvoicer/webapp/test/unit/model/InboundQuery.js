/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundQuery",
    "ainvoicer/model/ksef/inbound/InboundInvoiceMapper"
], function (InboundQuery, Mapper) {
    "use strict";

    function row(sId, mValues) {
        return Mapper.toRow(Object.assign({
            id: "1b0e5c7a-4f2d-4c11-9a3e-00000000000" + sId,
            environment: "TEST",
            ksefNumber: "2222222222-20261101-00000000001" + sId + "-AA",
            invoiceNumber: "FV/" + sId + "/2026",
            issueDate: "2026-11-0" + sId,
            acquisitionDate: "2026-11-0" + sId + "T10:00:00Z",
            sellerNip: "2222222222",
            currency: "PLN",
            grossAmount: "100.00",
            ingestionStatus: "STORED",
            parseStatus: "PARSED",
            integrityStatus: "VERIFIED",
            sapPostingStatus: "NOT_POSTED"
        }, mValues || {}));
    }

    function filters(mValues) {
        var oResult = InboundQuery.normalize(Object.assign(InboundQuery.createEmptyFilters(), mValues));
        return oResult;
    }

    QUnit.module("InboundQuery — validation before HTTP");

    QUnit.test("valid filters are normalized (NIP separators, KSeF upper case, trimming)", function (assert) {
        var oResult = filters({ sellerNip: " 222-222-22-22 ", ksefNumber: " 2222222222-20261101-0000 ", invoiceNumber: "  FV/1 " });
        assert.deepEqual(oResult.errors, []);
        assert.strictEqual(oResult.filters.sellerNip, "2222222222");
        assert.strictEqual(oResult.filters.ksefNumber, "2222222222-20261101-0000");
        assert.strictEqual(oResult.filters.invoiceNumber, "FV/1");
    });

    QUnit.test("invalid input is rejected with i18n keys (no silent repair)", function (assert) {
        var oResult = filters({
            sellerNip: "22a", ksefNumber: "abc/../x", invoiceNumber: new Array(258).join("x"),
            issueDateFrom: "2026-11-09", issueDateTo: "2026-11-01", ingestionStatuses: ["STORED' or 1 eq 1"],
            environment: "QA"
        });
        var aKeys = oResult.errors.map(function (oError) { return oError.key; });
        ["inboxFilterErrorNip", "inboxFilterErrorKsefNumber", "inboxFilterErrorTooLong", "inboxFilterErrorDateRange",
            "inboxFilterErrorStatus", "inboxFilterErrorEnvironment"].forEach(function (sKey) {
            assert.ok(aKeys.indexOf(sKey) !== -1, sKey);
        });
        assert.ok(filters({ acquisitionDateFrom: "2026-13-01" }).errors.length, "impossible date");
        assert.ok(filters({ environment: "constructor" }).errors.length, "prototype keys are not environments");
    });

    QUnit.module("InboundQuery — mock semantics");

    QUnit.test("filters by invoice number (case-insensitive), KSeF number, NIP prefix/exact, statuses and environment", function (assert) {
        var aRows = [row("1"), row("2", { sellerNip: "3333333333", integrityStatus: "CONFLICT" }),
            row("3", { environment: "DEMO", sapPostingStatus: null })];
        function count(mFilters) {
            var oFilters = filters(mFilters).filters;
            return aRows.filter(function (oRow) { return InboundQuery.matches(oRow, oFilters); }).length;
        }
        assert.strictEqual(count({}), 3);
        assert.strictEqual(count({ invoiceNumber: "fv/2" }), 1);
        assert.strictEqual(count({ ksefNumber: "000000000013" }), 1);
        assert.strictEqual(count({ sellerNip: "333" }), 1, "prefix");
        assert.strictEqual(count({ sellerNip: "3333333333" }), 1, "exact");
        assert.strictEqual(count({ sellerNip: "33333333" }), 1);
        assert.strictEqual(count({ integrityStatuses: ["CONFLICT", "MISMATCH"] }), 1);
        assert.strictEqual(count({ sapPostingStatuses: ["NOT_POSTED"] }), 2, "null SAP status is not NOT_POSTED");
        assert.strictEqual(count({ environment: "DEMO" }), 1);
    });

    QUnit.test("date ranges are inclusive and rows without dates do not match", function (assert) {
        var aRows = [row("1"), row("2"), row("3", { issueDate: null })];
        var oFilters = filters({ issueDateFrom: "2026-11-02", issueDateTo: "2026-11-03" }).filters;
        assert.deepEqual(aRows.filter(function (oRow) { return InboundQuery.matches(oRow, oFilters); })
            .map(function (oRow) { return oRow.invoiceNumber; }), ["FV/2/2026"]);
        var oAcq = filters({ acquisitionDateFrom: "2026-11-01", acquisitionDateTo: "2026-11-01" }).filters;
        assert.strictEqual(aRows.filter(function (oRow) { return InboundQuery.matches(oRow, oAcq); }).length, 1);
    });

    QUnit.test("sorting: decimals as text, empty values last in both directions, stable by id", function (assert) {
        var aRows = [
            row("1", { grossAmount: "98765432109876.53" }),
            row("2", { grossAmount: "98765432109876.54" }),
            row("3", { grossAmount: null }),
            row("4", { grossAmount: "-5.00" })
        ];
        function order(oSort) {
            return InboundQuery.sortRows(aRows, oSort).map(function (oRow) { return oRow.invoiceNumber; });
        }
        assert.deepEqual(order({ field: "grossAmount", descending: true }), ["FV/2/2026", "FV/1/2026", "FV/4/2026", "FV/3/2026"]);
        assert.deepEqual(order({ field: "grossAmount", descending: false }), ["FV/4/2026", "FV/1/2026", "FV/2/2026", "FV/3/2026"]);
        assert.deepEqual(order({ field: "acquisitionDate", descending: true }), ["FV/4/2026", "FV/3/2026", "FV/2/2026", "FV/1/2026"]);
        assert.deepEqual(InboundQuery.normalizeSort({ field: "constructor" }), { field: "acquisitionDate", descending: true },
            "unknown sort field falls back to the default");
    });

    QUnit.module("InboundQuery — future CAP OData V4 query");

    QUnit.test("builds $filter/$orderby/$skip/$top/$count/$select from the whitelist only", function (assert) {
        var oQuery = InboundQuery.toODataQuery(filters({
            invoiceNumber: "O'Brien FV", ksefNumber: "ab12", sellerNip: "2222222222",
            issueDateFrom: "2026-11-01", issueDateTo: "2026-11-09",
            ingestionStatuses: ["STORED", "DISCOVERED"], integrityStatuses: ["CONFLICT"], environment: "TEST"
        }).filters, { field: "grossAmount", descending: true }, 40, 500);
        assert.strictEqual(oQuery.$filter, "contains(tolower(invoiceNumber),'o''brien fv') and contains(ksefNumber,'AB12')"
            + " and sellerNip eq '2222222222' and issueDate ge 2026-11-01 and issueDate le 2026-11-09"
            + " and (ingestionStatus eq 'STORED' or ingestionStatus eq 'DISCOVERED') and integrityStatus eq 'CONFLICT'"
            + " and environment eq 'TEST'");
        assert.strictEqual(oQuery.$orderby, "grossAmount desc,id asc", "stable tie-breaker");
        assert.strictEqual(oQuery.$skip, 40);
        assert.strictEqual(oQuery.$top, 100, "top is capped");
        assert.strictEqual(oQuery.$count, true);
        assert.ok(oQuery.$select.indexOf("grossAmount") !== -1 && oQuery.$select.indexOf("id") === 0);
    });

    QUnit.test("NIP prefix uses startswith; acquisition day range uses local-day instants; no filter when empty", function (assert) {
        var oQuery = InboundQuery.toODataQuery(filters({ sellerNip: "222", acquisitionDateFrom: "2026-11-09", acquisitionDateTo: "2026-11-09" }).filters);
        var sFrom = new Date(2026, 10, 9).toISOString();
        var sTo = new Date(2026, 10, 10).toISOString();
        assert.strictEqual(oQuery.$filter, "startswith(sellerNip,'222') and acquisitionDate ge " + sFrom + " and acquisitionDate lt " + sTo);
        assert.strictEqual(InboundQuery.toODataQuery(filters({}).filters).$filter, undefined);
        var sQs = InboundQuery.toQueryString(InboundQuery.toODataQuery(filters({ invoiceNumber: "a&b" }).filters, null, 0, 20));
        assert.ok(sQs.indexOf("a%26b") !== -1, "values are URL-encoded");
        assert.ok(/^\$select=.*&\$filter=.*&\$orderby=acquisitionDate%20desc%2Cid%20asc&\$skip=0&\$top=20&\$count=true$/.test(sQs), sQs);
    });
});
