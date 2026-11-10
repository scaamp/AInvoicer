sap.ui.define([
    "sap/ui/core/format/NumberFormat",
    "ainvoicer/model/formatter",
    "ainvoicer/model/ksef/inbound/InboundContract",
    "ainvoicer/model/ksef/inbound/InboundInvoiceMapper"
], function (NumberFormat, formatter, InboundContract, InboundInvoiceMapper) {
    "use strict";

    // NumberFormat formatuje tekst dziesiętny bez konwersji na double; preserveDecimals nie zaokrągla
    // ukrytych miejsc (np. 4 miejsca z backendu nie znikną po cichu przy walucie 2-miejscowej).
    var oAmountFormat = NumberFormat.getCurrencyInstance({ showMeasure: false, preserveDecimals: true });

    function bundleText(oContext, sKey, aArgs) {
        var oComponent = oContext && oContext.getOwnerComponent && oContext.getOwnerComponent();
        var oBundle = oComponent && oComponent.getModel("i18n") && oComponent.getModel("i18n").getResourceBundle();
        if (!oBundle) {
            return null;
        }
        return oBundle.hasText && !oBundle.hasText(sKey) ? null : oBundle.getText(sKey, aArgs);
    }

    /** Fabryka formatterów tekst/stan/ikona dla pola statusu (np. "integrityStatus"). */
    function statusFormatters(sField) {
        var mDictionary = InboundContract.statusDictionary(sField);
        return {
            text: function (sValue) {
                if (!sValue) {
                    return bundleText(this, "inboxStatusUnknown") || "—";
                }
                // Nieznana wartość = tekst dosłowny (UI nie interpretuje statusów spoza kontraktu).
                return bundleText(this, "inboxStatus_" + sField + "_" + sValue) || sValue;
            },
            state: function (sValue) {
                var oInfo = InboundContract.has(mDictionary, sValue) && mDictionary[sValue];
                return oInfo ? oInfo.state : "None";
            },
            icon: function (sValue) {
                var oInfo = InboundContract.has(mDictionary, sValue) && mDictionary[sValue];
                return oInfo ? oInfo.icon : "";
            }
        };
    }

    var oIngestion = statusFormatters("ingestionStatus");
    var oParse = statusFormatters("parseStatus");
    var oIntegrity = statusFormatters("integrityStatus");
    var oSap = statusFormatters("sapPostingStatus");

    var InboundFormatter = {
        ingestionText: oIngestion.text,
        ingestionState: oIngestion.state,
        ingestionIcon: oIngestion.icon,
        parseText: oParse.text,
        parseState: oParse.state,
        parseIcon: oParse.icon,
        integrityText: oIntegrity.text,
        integrityState: oIntegrity.state,
        integrityIcon: oIntegrity.icon,
        sapText: oSap.text,
        sapState: oSap.state,
        sapIcon: oSap.icon,

        date: formatter.date,
        dateTime: formatter.dateTime,
        nip: formatter.nip,

        /** Wartość albo myślnik (brak danych jest pokazywany, nie ukrywany). */
        orDash: function (vValue) {
            return vValue === null || vValue === undefined || vValue === "" ? "—" : String(vValue);
        },

        /**
         * Kwota z tekstu dziesiętnego wg locale i waluty (np. JPY bez miejsc). Niepoprawna wartość jest
         * pokazana dosłownie (bez „naprawy”), brak = myślnik.
         */
        amount: function (sAmount, sCurrency, bValid) {
            if (sAmount === null || sAmount === undefined || sAmount === "") {
                return "—";
            }
            if (!bValid || !InboundInvoiceMapper.DECIMAL_PATTERN.test(String(sAmount))) {
                return String(sAmount);
            }
            var sCode = typeof sCurrency === "string" && /^[A-Z]{3}$/.test(sCurrency) ? sCurrency : undefined;
            return oAmountFormat.format(String(sAmount), sCode);
        },

        ksefShort: function (sKsefNumber) {
            return sKsefNumber ? InboundInvoiceMapper.shortenKsefNumber(sKsefNumber) : "—";
        },

        /** Identyfikator nabywcy z typem; brak NIP-u nabywcy jest normalnym przypadkiem (VatUe/Other/None). */
        buyerIdentifier: function (sType, sValue) {
            if (sType === InboundContract.BuyerIdentifierType.Nip && sValue) {
                return "NIP " + formatter.nip(sValue);
            }
            if (sType === InboundContract.BuyerIdentifierType.None || !sValue) {
                return bundleText(this, "inboxBuyerNoIdentifier") || "—";
            }
            var sLabel = sType === InboundContract.BuyerIdentifierType.VatUe ? "VAT UE" : (sType || "ID");
            return sLabel + " " + sValue;
        },

        /** Rodzaj faktury i kod formularza, np. "VAT · FA (3)"; brak = myślnik. */
        typeAndForm: function (sType, sFormCode) {
            return (sType || "—") + " · " + (sFormCode || "—");
        },

        sellerNip: function (sNip) {
            return sNip ? "NIP " + formatter.nip(sNip) : (bundleText(this, "inboxSellerNoNip") || "—");
        },

        rowHighlight: function (sIngestion, sParse, sIntegrity, bIncomplete) {
            return InboundInvoiceMapper.rowHighlight({
                ingestionStatus: sIngestion, parseStatus: sParse, integrityStatus: sIntegrity, incomplete: !!bIncomplete
            });
        },

        environmentState: formatter.environmentState,

        yesNo: function (bValue) {
            return bundleText(this, bValue ? "inboxYes" : "inboxNo") || (bValue ? "Yes" : "No");
        },

        /** Lista problemów danych → teksty i18n (kody bez tłumaczenia pokazane dosłownie). */
        issuesText: function (aIssues) {
            if (!Array.isArray(aIssues) || !aIssues.length) {
                return "";
            }
            return aIssues.map(function (sIssue) {
                return bundleText(this, "inboxIssue_" + sIssue) || sIssue;
            }, this).join("\n");
        }
    };

    return InboundFormatter;
});
