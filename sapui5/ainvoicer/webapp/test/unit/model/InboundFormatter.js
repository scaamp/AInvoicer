/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundFormatter"
], function (InboundFormatter) {
    "use strict";

    QUnit.module("InboundFormatter");

    QUnit.test("amount: decimal string keeps full precision, null shows a dash, invalid is shown verbatim", function (assert) {
        var sBig = InboundFormatter.amount("98765432109876.54", "PLN", true);
        // double dałby 98765432109876.55 / .5x — tekst dziesiętny zachowuje każdą cyfrę
        assert.strictEqual(sBig.replace(/\D/g, ""), "9876543210987654", "every digit preserved: " + sBig);
        assert.strictEqual(InboundFormatter.amount(null, "PLN", false), "—");
        assert.strictEqual(InboundFormatter.amount("12,34", "pln", false), "12,34");
        assert.ok(InboundFormatter.amount("-1230.00", "PLN", true).indexOf("1") !== -1);
        assert.ok(/\.\d{4}$|,\d{4}$/.test(InboundFormatter.amount("10.1234", "PLN", true)), "extra decimals are not silently dropped");
    });

    QUnit.test("status formatters: known codes get state and icon, unknown code is neutral", function (assert) {
        assert.strictEqual(InboundFormatter.integrityState("CONFLICT"), "Error");
        assert.strictEqual(InboundFormatter.integrityState("VERIFIED"), "Success");
        assert.strictEqual(InboundFormatter.parseState("UNSUPPORTED_SCHEMA"), "Warning");
        assert.strictEqual(InboundFormatter.ingestionState("QUARANTINED"), "Error");
        assert.strictEqual(InboundFormatter.ingestionIcon("QUARANTINED"), "sap-icon://error");
        assert.strictEqual(InboundFormatter.sapState("NOT_POSTED"), "None");
        assert.strictEqual(InboundFormatter.integrityState("constructor"), "None", "no prototype lookup");
        assert.strictEqual(InboundFormatter.parseIcon("__proto__"), "");
        // bez kontrolera (this) brak i18n → kod dosłownie / myślnik
        assert.strictEqual(InboundFormatter.ingestionText.call(null, "QUARANTINED"), "QUARANTINED");
        assert.strictEqual(InboundFormatter.sapText.call(null, null), "—");
    });

    QUnit.test("parties and KSeF number helpers", function (assert) {
        assert.strictEqual(InboundFormatter.buyerIdentifier.call(null, "Nip", "1111111111"), "NIP 111-111-11-11");
        assert.strictEqual(InboundFormatter.buyerIdentifier.call(null, "VatUe", "DE000000001"), "VAT UE DE000000001");
        assert.strictEqual(InboundFormatter.buyerIdentifier.call(null, "None", null), "—");
        assert.strictEqual(InboundFormatter.sellerNip.call(null, "2222222222"), "NIP 222-222-22-22");
        assert.strictEqual(InboundFormatter.ksefShort("2222222222-20261101-000000000010-2E"), "2222222222-…000010-2E");
        assert.strictEqual(InboundFormatter.ksefShort(null), "—");
        assert.strictEqual(InboundFormatter.orDash(""), "—");
        assert.strictEqual(InboundFormatter.typeAndForm("VAT", null), "VAT · —");
        assert.strictEqual(InboundFormatter.rowHighlight("STORED", "PARSED", "CONFLICT", false), "Error");
    });
});
