/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/KsefActionRules"
], function (KsefActionRules) {
    "use strict";

    var NO_CAPS = { readOperations: true, sendInvoice: false, syncInbound: false, viewXml: false, downloadUpo: false, checkDuplicate: false };
    var ALL_CAPS = { readOperations: true, sendInvoice: true, syncInbound: true, viewXml: true, downloadUpo: true, checkDuplicate: true };

    function invoice(mOverrides) {
        return Object.assign({
            InvoiceUUID: "f7ac01d0-ac15-5b56-a77b-ec4e718ced7d",
            Direction: "OUTBOUND",
            KsefNumber: "",
            SapKsefNumber: "",
            KsefStatus: "NO_OPERATION",
            LatestOperation: null
        }, mOverrides);
    }

    function op(sStatus, sType) {
        return { operationType: sType || "SEND_INVOICE", status: sStatus, ksefNumber: sStatus === "ACCEPTED" ? "K-1" : "" };
    }

    QUnit.module("KsefActionRules — send");

    QUnit.test("no selection disables every row action", function (assert) {
        var mRules = KsefActionRules.evaluateAll(null, ALL_CAPS, "TEST", "ALL");
        ["send", "checkStatus", "viewXml", "downloadUpo", "viewDetails", "history", "checkDuplicate"].forEach(function (sAction) {
            assert.strictEqual(mRules[sAction].enabled, false, sAction + " disabled");
            assert.strictEqual(mRules[sAction].reasonKey, "ksefActionReasonNoSelection");
        });
        assert.strictEqual(mRules.refresh.enabled, true);
    });

    QUnit.test("eligible OUTBOUND invoice is still disabled while CAP send contract is missing (G-02)", function (assert) {
        var oRule = KsefActionRules.evaluateSend(invoice(), NO_CAPS, "TEST");
        assert.strictEqual(oRule.enabled, false);
        assert.strictEqual(oRule.reasonKey, "ksefActionReasonBackendMissing");
        assert.deepEqual(oRule.reasonArgs, ["G-02"]);
    });

    QUnit.test("eligible OUTBOUND invoice is enabled only with backend capability and TEST/DEMO target", function (assert) {
        assert.strictEqual(KsefActionRules.evaluateSend(invoice(), ALL_CAPS, "TEST").enabled, true);
        assert.strictEqual(KsefActionRules.evaluateSend(invoice(), ALL_CAPS, "DEMO").enabled, true);
        var oProd = KsefActionRules.evaluateSend(invoice(), ALL_CAPS, "PROD");
        assert.strictEqual(oProd.enabled, false, "PROD is never enabled from the UI");
        assert.strictEqual(oProd.reasonKey, "ksefActionReasonProdBlocked");
    });

    QUnit.test("INBOUND invoices are never sent", function (assert) {
        var oRule = KsefActionRules.evaluateSend(invoice({ Direction: "INBOUND" }), ALL_CAPS, "TEST");
        assert.strictEqual(oRule.reasonKey, "ksefActionReasonOutboundOnly");
    });

    QUnit.test("existing KSeF number or ACCEPTED operation blocks resend", function (assert) {
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ SapKsefNumber: "K-SAP" }), ALL_CAPS, "TEST").reasonKey,
            "ksefActionReasonAlreadyInKsef");
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ LatestOperation: op("ACCEPTED") }), ALL_CAPS, "TEST").reasonKey,
            "ksefActionReasonAlreadyInKsef");
    });

    QUnit.test("in-progress, failed and unknown operations block send; REJECTED allows a confirmed resend", function (assert) {
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ LatestOperation: op("NEW") }), ALL_CAPS, "TEST").reasonKey,
            "ksefActionReasonInProgress");
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ LatestOperation: op("PROCESSING") }), ALL_CAPS, "TEST").reasonKey,
            "ksefActionReasonInProgress");
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ LatestOperation: op("FAILED") }), ALL_CAPS, "TEST").reasonKey,
            "ksefActionReasonReconciliation");
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ LatestOperation: op("WEIRD") }), ALL_CAPS, "TEST").reasonKey,
            "ksefActionReasonUnknownStatus");
        assert.strictEqual(KsefActionRules.evaluateSend(invoice({ LatestOperation: op("REJECTED") }), ALL_CAPS, "TEST").enabled, true);
    });

    QUnit.module("KsefActionRules — other actions");

    QUnit.test("check status requires an operation and CAP read access", function (assert) {
        assert.strictEqual(KsefActionRules.evaluateCheckStatus(invoice(), NO_CAPS).reasonKey, "ksefActionReasonNoOperation");
        assert.strictEqual(KsefActionRules.evaluateCheckStatus(invoice({ LatestOperation: op("NEW") }), NO_CAPS).enabled, true);
        assert.strictEqual(KsefActionRules.evaluateCheckStatus(invoice({ LatestOperation: op("NEW") }), {}).reasonArgs[0], "G-03");
    });

    QUnit.test("UPO is OUTBOUND + accepted + backend; XML needs a document + backend", function (assert) {
        var oAccepted = invoice({ KsefStatus: "ACCEPTED", KsefNumber: "K-1", LatestOperation: op("ACCEPTED") });
        assert.deepEqual(KsefActionRules.evaluateDownloadUpo(oAccepted, NO_CAPS).reasonArgs, ["G-06"]);
        assert.strictEqual(KsefActionRules.evaluateDownloadUpo(oAccepted, ALL_CAPS).enabled, true);
        assert.strictEqual(KsefActionRules.evaluateDownloadUpo(invoice(), ALL_CAPS).reasonKey, "ksefActionReasonNotAccepted");
        assert.strictEqual(KsefActionRules.evaluateDownloadUpo(invoice({ Direction: "INBOUND", KsefStatus: "ACCEPTED" }), ALL_CAPS).reasonKey,
            "ksefActionReasonOutboundOnly");
        assert.strictEqual(KsefActionRules.evaluateViewXml(invoice(), ALL_CAPS).reasonKey, "ksefActionReasonNoXml");
        assert.deepEqual(KsefActionRules.evaluateViewXml(oAccepted, NO_CAPS).reasonArgs, ["G-05"]);
        assert.strictEqual(KsefActionRules.evaluateViewXml(invoice({ Direction: "INBOUND", KsefNumber: "K-IN" }), ALL_CAPS).enabled, true);
    });

    QUnit.test("sync is global, inbound-only and gated by backend (G-04)", function (assert) {
        assert.deepEqual(KsefActionRules.evaluateSync(NO_CAPS, "ALL").reasonArgs, ["G-04"]);
        assert.strictEqual(KsefActionRules.evaluateSync(ALL_CAPS, "SALES").reasonKey, "ksefActionReasonSyncInboundOnly");
        assert.strictEqual(KsefActionRules.evaluateSync(ALL_CAPS, "PURCHASE").enabled, true);
    });

    QUnit.test("details always available for a selection; duplicate check is INBOUND-only (G-08)", function (assert) {
        assert.strictEqual(KsefActionRules.evaluateViewDetails(invoice()).enabled, true);
        assert.strictEqual(KsefActionRules.evaluateHistory(invoice(), NO_CAPS).enabled, true);
        assert.strictEqual(KsefActionRules.evaluateCheckDuplicate(invoice(), ALL_CAPS).reasonKey, "ksefActionReasonInboundOnly");
        assert.deepEqual(KsefActionRules.evaluateCheckDuplicate(invoice({ Direction: "INBOUND" }), NO_CAPS).reasonArgs, ["G-08"]);
    });
});
