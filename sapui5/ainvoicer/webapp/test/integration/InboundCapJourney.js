/* global QUnit */
sap.ui.define([
    "sap/ui/test/Opa5", "sap/ui/test/opaQunit", "./arrangements/Startup",
    "ainvoicer/model/ksef/inbound/InboundInboxAdapter", "./pages/InboundInbox"
], function (Opa5, opaTest, Startup, Adapter) {
    "use strict";
    var config = { dataMode: "cap", pageSize: 2, cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview" } };
    Opa5.extendConfig({ arrangements: new Startup(), viewNamespace: "ainvoicer.view.", autoWait: true });
    QUnit.module("INT-02 actual CAP HTTP (synthetic staging, local principal)");
    QUnit.test("Adapter reads exact decimal, filters and key through real HTTP", async function (assert) {
        var adapter = new Adapter(config);
        var result = await adapter.loadPage({ top: 1, sort: { field: "grossAmount", descending: true } });
        assert.strictEqual(result.count, 5);
        assert.strictEqual(result.rows[0].grossAmount, "9999999999999999.99");
        var detail = await adapter.loadInvoice(result.rows[0].id);
        assert.strictEqual(detail.invoiceNumber, "FV/O'Brien/INT-02");
        assert.strictEqual(detail.buyerName, null);
        var filtered = await adapter.loadPage({ filters: { invoiceNumber: "o'brien", sellerNip: "123" } });
        assert.strictEqual(filtered.count, 1);
        assert.strictEqual(filtered.rows[0].id, detail.id);
        var unsupported = await adapter.loadPage({ filters: { parseStatuses: ["UNSUPPORTED_SCHEMA"] } });
        assert.strictEqual(unsupported.count, 1);
        assert.strictEqual(unsupported.rows[0].grossAmount, null);
        try {
            await adapter.loadInvoice("10000000-0000-4000-8000-000000000003");
            assert.ok(false, "foreign UUID must fail");
        } catch (e) { assert.strictEqual(e.status, 404); }
    });
    opaTest("UI loads real CAP list with paging and no mock badge", function (Given, When, Then) {
        Given.iStartMyApp({ hash: "ksef-inbox" });
        Then.onTheInboxPage.iShouldSeeRows(2, 5);
        Then.onTheInboxPage.iShouldSeeControlVisible("inboxDemoStrip", false);
        When.onTheInboxPage.iPressButton("inboxLoadMoreButton");
        Then.onTheInboxPage.iShouldSeeRows(4, 5);
        When.onTheInboxPage.iPressButton("inboxLoadMoreButton");
        Then.onTheInboxPage.iShouldSeeRows(5, 5);
    });
    opaTest("UI filters, sorts and opens details from CAP", function (Given, When, Then) {
        When.onTheInboxPage.iEnterFilter("inboxInvoiceNumberFilter", "o'brien");
        Then.onTheInboxPage.iShouldSeeRows(1, 1);
        When.onTheInboxPage.iOpenInvoice("FV/O'Brien/INT-02");
        Then.onTheInboxPage.iShouldSeeDetails("FV/O'Brien/INT-02");
        Then.onTheInboxPage.iShouldSeeAmountDigits("inboxDetailsGrossAmount", "999999999999999999");
        When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");
        When.onTheInboxPage.iClearFilters();
        When.onTheInboxPage.iCallController(function (c) { c._applySort("grossAmount", true); });
        Then.onTheInboxPage.iShouldSeeFirstInvoice("FV/O'Brien/INT-02");
        When.onTheInboxPage.iEnterFilter("inboxInvoiceNumberFilter", "DOES-NOT-EXIST");
        Then.onTheInboxPage.iShouldSeeRows(0, 0);
        Then.onTheInboxPage.iShouldSeeEmptyState("noMatch");
        When.onTheInboxPage.iClearFilters();
        When.onTheInboxPage.iEnterFilter("inboxSellerNipFilter", "invalid");
        Then.onTheInboxPage.iShouldSeeRows(0);
        Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "Seller NIP may contain only digits");
        Then.iTeardownMyApp();
    });
});
