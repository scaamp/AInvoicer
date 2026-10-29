/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/formatter"
], function (formatter) {
    "use strict";

    QUnit.module("formatter");

    QUnit.test("dates: Edm.Date without timezone shift, invalid passthrough, empty", function (assert) {
        var sFormatted = formatter.date("2026-10-01");
        assert.ok(sFormatted.indexOf("2026") !== -1, "contains year: " + sFormatted);
        assert.ok(/\b1\b|01/.test(sFormatted), "keeps day 1 regardless of browser timezone: " + sFormatted);
        assert.strictEqual(formatter.date("not-a-date"), "not-a-date");
        assert.strictEqual(formatter.date(null), "");
        assert.strictEqual(formatter.dateTime(null), "");
        assert.ok(formatter.dateTime("2026-10-12T09:15:21.1234567Z").indexOf("2026") !== -1);
    });

    QUnit.test("amounts: two decimals; null stays empty (never shown as 0)", function (assert) {
        assert.ok(/2[\s .,]?706[.,]00/.test(formatter.amount(2706)), formatter.amount(2706));
        assert.ok(/[.,]30$/.test(formatter.amount("1119.3")), formatter.amount("1119.3"));
        assert.strictEqual(formatter.amount(null), "");
        assert.strictEqual(formatter.amount(""), "");
    });

    QUnit.test("NIP and party formatting", function (assert) {
        assert.strictEqual(formatter.nip("5260002008"), "526-000-20-08");
        assert.strictEqual(formatter.nip("12345"), "12345", "non-NIP values are not altered");
        assert.strictEqual(formatter.party("Seller", "5260002008"), "Seller\nNIP 526-000-20-08");
    });

    QUnit.test("KSeF status state/icon; unknown status stays neutral", function (assert) {
        assert.strictEqual(formatter.ksefStatusState("ACCEPTED"), "Success");
        assert.strictEqual(formatter.ksefStatusState("REJECTED"), "Error");
        assert.strictEqual(formatter.ksefStatusState("PROCESSING"), "Warning");
        assert.strictEqual(formatter.ksefStatusState("NEW"), "Information");
        assert.strictEqual(formatter.ksefStatusState("RECORDED_IN_SAP"), "Success");
        assert.strictEqual(formatter.ksefStatusState("SOMETHING_ELSE"), "None");
        assert.strictEqual(formatter.ksefStatusIcon("SOMETHING_ELSE"), "");
        assert.strictEqual(formatter.ksefStatusText.call({}, "SOMETHING_ELSE"), "SOMETHING_ELSE", "no i18n → raw status");
    });

    QUnit.test("environment and direction", function (assert) {
        assert.strictEqual(formatter.environmentState("PROD"), "Error");
        assert.strictEqual(formatter.environmentState("TEST"), "Information");
        assert.strictEqual(formatter.environmentState(""), "None");
        assert.strictEqual(formatter.directionIcon("INBOUND"), "sap-icon://download");
        assert.strictEqual(formatter.directionIcon("OUTBOUND"), "sap-icon://upload");
    });
});
