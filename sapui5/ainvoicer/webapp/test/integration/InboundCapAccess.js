/* global QUnit */
sap.ui.define(["sap/ui/test/Opa5", "sap/ui/test/opaQunit", "./arrangements/Startup",
    "ainvoicer/model/ksef/inbound/InboundInboxAdapter", "./pages/InboundInbox"],
function (Opa5, opaTest, Startup, Adapter) {
    "use strict";
    var scenario = new URLSearchParams(window.location.search).get("scenario");
    var count = scenario === "b" ? 2 : 0;
    var expectedStatus = scenario === "anonymous" ? 401 : scenario === "no-role" ? 403 : null;
    Opa5.extendConfig({ arrangements: new Startup(), viewNamespace: "ainvoicer.view.", autoWait: true });
    QUnit.module("INT-02 real HTTP access and recovery: " + scenario);
    QUnit.test("Adapter enforces server response without mock fallback", async function (assert) {
        var adapter = new Adapter({ dataMode: "cap", cap: {
            serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview" } });
        try {
            var result = await adapter.loadPage();
            assert.strictEqual(expectedStatus, null);
            assert.notStrictEqual(scenario, "outage");
            assert.strictEqual(result.count, count);
            if (scenario === "b") {
                assert.deepEqual(result.rows.map(function (r) { return r.id; }).sort(),
                    ["10000000-0000-4000-8000-000000000002", "10000000-0000-4000-8000-000000000003"]);
            }
        } catch (error) {
            if (scenario === "outage") { assert.ok(error.code === "HTTP" || error.code === "NETWORK"); }
            else { assert.strictEqual(error.status, expectedStatus); }
        }
    });
    opaTest("UI clears rows and presents the actual access/network state", function (Given, When, Then) {
        Given.iStartMyApp({ hash: "ksef-inbox" });
        Then.onTheInboxPage.iShouldSeeRows(count, count);
        Then.onTheInboxPage.iShouldSeeControlVisible("inboxDemoStrip", false);
        if (expectedStatus || scenario === "outage") {
            Then.onTheInboxPage.iShouldSeeControlVisible("inboxErrorStrip", true);
            Then.onTheInboxPage.iShouldSeeEmptyState("error");
        } else {
            Then.onTheInboxPage.iShouldSeeControlVisible("inboxErrorStrip", false);
            if (!count) { Then.onTheInboxPage.iShouldSeeEmptyState("noData"); }
        }
        Then.iTeardownMyApp();
    });
});
