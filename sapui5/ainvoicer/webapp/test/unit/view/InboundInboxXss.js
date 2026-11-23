/*global QUnit*/
sap.ui.define([
    "sap/ui/core/Fragment",
    "sap/ui/model/json/JSONModel",
    "sap/ui/model/resource/ResourceModel",
    "sap/base/strings/formatMessage",
    "ainvoicer/model/ksef/inbound/InboundFormatter"
], function (Fragment, JSONModel, ResourceModel, formatMessage, InboundFormatter) {
    "use strict";

    /**
     * BETA-01: wartości pochodzące z XML faktury (sellerName, invoiceNumber, buyerName…) są niezaufane.
     * Widoki Inbox muszą renderować je wyłącznie jako tekst — bez FormattedText/HTML i bez interpretacji znaczników.
     */
    var INVOICE_NUMBER = "<img src=x onerror=\"window.__inboxXss=1\">FV/XSS/1";
    var SELLER_NAME = "<script>window.__inboxXss=2</script><b>Seller</b>";
    var BUYER_NAME = "\"><svg onload=\"window.__inboxXss=3\">";
    var VIEW_SOURCES = ["ainvoicer/view/InboundInbox.view.xml", "ainvoicer/view/InboundInvoiceDetails.fragment.xml",
        "ainvoicer/view/InboundInboxSort.fragment.xml"];

    function injected(oDomRef) {
        return oDomRef.querySelectorAll("img[src='x'], script, svg[onload], b").length;
    }

    QUnit.module("KSeF Inbox — XSS: XML values are rendered as text", {
        afterEach: function () {
            delete window.__inboxXss;
        }
    });

    QUnit.test("Inbox views contain no HTML-rendering controls or bindings", function (assert) {
        return Promise.all(VIEW_SOURCES.map(function (sModule) {
            return fetch(sap.ui.require.toUrl(sModule)).then(function (oResponse) {
                assert.ok(oResponse.ok, sModule + " loaded");
                return oResponse.text();
            }).then(function (sSource) {
                assert.notOk(/FormattedText|core:HTML|htmlText|sap\.ui\.core\.HTML/.test(sSource), sModule + ": no HTML rendering");
            });
        }));
    });

    QUnit.test("details dialog shows malicious invoice values literally, without creating elements", function (assert) {
        var done = assert.async();
        var oController = {
            formatter: InboundFormatter,
            formatMessage: function (sPattern) {
                return sPattern ? formatMessage(sPattern, Array.prototype.slice.call(arguments, 1)) : "";
            },
            xmlDownloadInfo: function () { return "XML download is not available in this version."; },
            onDetailsAfterClose: function () {},
            onDetailsClose: function () {},
            onCopyKsefNumber: function () {},
            onDownloadXml: function () {},
            onFutureAction: function () {}
        };
        Fragment.load({ id: "xssTest", name: "ainvoicer.view.InboundInvoiceDetails", controller: oController }).then(function (oDialog) {
            oDialog.setModel(new ResourceModel({ bundleName: "ainvoicer.i18n.i18n" }), "i18n");
            oDialog.setModel(new JSONModel({ demo: false, actions: { viewXml: { enabled: false, reason: "" } } }), "inbox");
            oDialog.setModel(new JSONModel({
                busy: false, error: "", downloading: false,
                invoice: {
                    id: "1b0e5c7a-4f2d-4c11-9a3e-000000000001", invoiceNumber: INVOICE_NUMBER, sellerName: SELLER_NAME,
                    buyerName: BUYER_NAME, sellerNip: "1234567802", ksefNumber: "1234567802-20261110-000000000001-AA",
                    issues: [], xmlAvailable: true
                }
            }), "inboxDetails");
            oDialog.attachAfterOpen(function () {
                var oDomRef = oDialog.getDomRef();
                var sText = oDomRef.textContent;
                assert.strictEqual(injected(oDomRef), 0, "no element created from invoice values");
                assert.ok(sText.indexOf(INVOICE_NUMBER) !== -1, "invoice number shown literally");
                assert.ok(sText.indexOf(SELLER_NAME) !== -1, "seller name shown literally");
                assert.ok(sText.indexOf(BUYER_NAME) !== -1, "buyer name shown literally");
                assert.ok(oDialog.getTitle().indexOf(INVOICE_NUMBER) !== -1, "title keeps the literal value");
                assert.strictEqual(window.__inboxXss, undefined, "no script executed");
                assert.strictEqual(sap.ui.getCore().byId("xssTest--inboxDownloadXmlButton").getVisible(), false,
                    "no download button with viewXml=false even when xmlAvailable=true");
                oDialog.close();
                oDialog.destroy();
                done();
            });
            oDialog.open();
        });
    });
});
