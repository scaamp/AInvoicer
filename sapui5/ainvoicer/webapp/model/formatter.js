sap.ui.define([
    "sap/ui/core/format/DateFormat",
    "sap/ui/core/format/NumberFormat",
    "ainvoicer/model/ksef/KsefContract"
], function (DateFormat, NumberFormat, KsefContract) {
    "use strict";

    var oDateFormat = DateFormat.getDateInstance({ style: "medium", UTC: true });
    var oDateTimeFormat = DateFormat.getDateTimeInstance({ style: "medium" });
    var oCurrencyFormat = NumberFormat.getCurrencyInstance({ showMeasure: false, minFractionDigits: 2, maxFractionDigits: 2 });

    /**
     * Formattery kokpitu KSeF. Wywoływane z XML jako ".formatter.xxx" (this = kontroler);
     * nie korzystają z this poza tekstami i18n, więc są testowalne bez widoku.
     */
    var formatter = {

        /** "2026-10-01" (Edm.Date) → data lokalna; bez przesunięcia strefy czasowej. */
        date: function (sDate) {
            if (!sDate) {
                return "";
            }
            var aMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(sDate));
            if (!aMatch) {
                return String(sDate);
            }
            return oDateFormat.format(new Date(Date.UTC(+aMatch[1], +aMatch[2] - 1, +aMatch[3])));
        },

        /** Edm.DateTimeOffset (ISO) → data i czas lokalny. */
        dateTime: function (sTimestamp) {
            if (!sTimestamp) {
                return "";
            }
            var oDate = new Date(sTimestamp);
            return isNaN(oDate.getTime()) ? String(sTimestamp) : oDateTimeFormat.format(oDate);
        },

        /** Kwota z 2 miejscami po przecinku wg locale; null → pusty tekst (nie 0). */
        amount: function (vAmount) {
            if (vAmount === null || vAmount === undefined || vAmount === "") {
                return "";
            }
            var fAmount = Number(vAmount);
            return isFinite(fAmount) ? oCurrencyFormat.format(fAmount) : String(vAmount);
        },

        /** NIP 10 cyfr → XXX-XXX-XX-XX (wyłącznie prezentacja; model przechowuje cyfry). */
        nip: function (sNip) {
            var sValue = sNip ? String(sNip) : "";
            return /^\d{10}$/.test(sValue)
                ? sValue.substring(0, 3) + "-" + sValue.substring(3, 6) + "-" + sValue.substring(6, 8) + "-" + sValue.substring(8)
                : sValue;
        },

        /** Nazwa + NIP w jednej kolumnie. */
        party: function (sName, sNip) {
            var sFormattedNip = formatter.nip(sNip);
            return [sName, sFormattedNip ? "NIP " + sFormattedNip : ""].filter(Boolean).join("\n");
        },

        ksefStatusState: function (sStatus) {
            var oInfo = KsefContract.OperationStatus[sStatus];
            if (oInfo) {
                return oInfo.state;
            }
            return sStatus === KsefContract.DerivedStatus.RECORDED_IN_SAP ? "Success" : "None";
        },

        ksefStatusIcon: function (sStatus) {
            switch (formatter.ksefStatusState(sStatus)) {
                case "Success": return "sap-icon://status-positive";
                case "Error": return "sap-icon://status-negative";
                case "Warning": return "sap-icon://pending";
                case "Information": return "sap-icon://information";
                default: return "";
            }
        },

        /** Tekst statusu z i18n; nieznany status pokazany dosłownie (bez zgadywania). */
        ksefStatusText: function (sStatus) {
            if (!sStatus) {
                return "";
            }
            var oBundle = this && this.getOwnerComponent && this.getOwnerComponent().getModel("i18n").getResourceBundle();
            var sKey = "ksefStatus_" + sStatus;
            return oBundle && oBundle.hasText && oBundle.hasText(sKey) ? oBundle.getText(sKey) : sStatus;
        },

        directionIcon: function (sDirection) {
            return sDirection === KsefContract.Direction.INBOUND ? "sap-icon://download"
                : sDirection === KsefContract.Direction.OUTBOUND ? "sap-icon://upload" : "";
        },

        directionState: function (sDirection) {
            return sDirection === KsefContract.Direction.INBOUND ? "Information"
                : "None";
        },

        /** PROD wyróżniony ostrzegawczo; UI tylko prezentuje dane, nie wykonuje akcji PROD. */
        environmentState: function (sEnvironment) {
            if (sEnvironment === "PROD") {
                return "Error";
            }
            return sEnvironment === "TEST" || sEnvironment === "DEMO" ? "Information" : "None";
        }
    };

    return formatter;
});
