sap.ui.define([
    "ainvoicer/model/ksef/KsefContract"
], function (KsefContract) {
    "use strict";

    var Direction = KsefContract.Direction;
    var DerivedStatus = KsefContract.DerivedStatus;

    /**
     * Identyfikatory luk kontraktu backendu (docs/guides/SAPUI5_KSEF_COCKPIT.md, sekcja "Backend contract gaps").
     * Akcja bez kontraktu jest wyłączona z jawną przyczyną — UI nigdy nie symuluje jej wykonania.
     */
    var Gap = Object.freeze({
        SEND: "G-02",
        CHECK_STATUS: "G-03",
        SYNC: "G-04",
        XML: "G-05",
        UPO: "G-06",
        HISTORY: "G-07",
        DUPLICATE: "G-08"
    });

    /** Środowiska KSeF, dla których UI może w ogóle udostępnić akcje zmieniające stan. */
    var NON_PRODUCTIVE = ["TEST", "DEMO"];

    function result(bEnabled, sReasonKey, aReasonArgs) {
        return { enabled: bEnabled, reasonKey: sReasonKey || null, reasonArgs: aReasonArgs || [] };
    }

    function missingBackend(sGap) {
        return result(false, "ksefActionReasonBackendMissing", [sGap]);
    }

    function latestOperationOfType(oInvoice, sType) {
        var oLatest = oInvoice && oInvoice.LatestOperation;
        return oLatest && oLatest.operationType === sType ? oLatest : null;
    }

    var KsefActionRules = {

        Gap: Gap,

        /** Wysyłka: tylko OUTBOUND, bez numeru KSeF, bez operacji w toku, wyłącznie TEST/DEMO i przez CAP. */
        evaluateSend: function (oInvoice, oCaps, sTargetEnvironment) {
            if (!oInvoice) {
                return result(false, "ksefActionReasonNoSelection");
            }
            if (oInvoice.Direction !== Direction.OUTBOUND) {
                return result(false, "ksefActionReasonOutboundOnly");
            }
            if (oInvoice.SapKsefNumber || oInvoice.KsefNumber) {
                return result(false, "ksefActionReasonAlreadyInKsef", [oInvoice.KsefNumber || oInvoice.SapKsefNumber]);
            }
            var oSend = latestOperationOfType(oInvoice, KsefContract.OperationType.SEND_INVOICE);
            if (oSend) {
                if (!KsefContract.OperationStatus[oSend.status]) {
                    return result(false, "ksefActionReasonUnknownStatus", [oSend.status]);
                }
                if (oSend.status === "ACCEPTED") {
                    return result(false, "ksefActionReasonAlreadyInKsef", [oSend.ksefNumber || ""]);
                }
                if (oSend.status === "FAILED") {
                    // Błąd techniczny: ponowienie i rekoncyliacja należą do CAP, nie do użytkownika UI.
                    return result(false, "ksefActionReasonReconciliation");
                }
                if (!KsefContract.isTerminalStatus(oSend.status)) {
                    return result(false, "ksefActionReasonInProgress", [oSend.status]);
                }
                // REJECTED: ponowna wysyłka po korekcie jest dozwolona, ale wymaga wyraźnego potwierdzenia.
            }
            if (NON_PRODUCTIVE.indexOf(sTargetEnvironment) === -1) {
                return result(false, "ksefActionReasonProdBlocked", [sTargetEnvironment || "?"]);
            }
            if (!oCaps || !oCaps.sendInvoice) {
                return missingBackend(Gap.SEND);
            }
            return result(true);
        },

        /** Kontrola statusu: odczyt statusu z CAP dla istniejącej operacji (bez bezpośredniego KSeF). */
        evaluateCheckStatus: function (oInvoice, oCaps) {
            if (!oInvoice) {
                return result(false, "ksefActionReasonNoSelection");
            }
            if (!oInvoice.LatestOperation) {
                return result(false, "ksefActionReasonNoOperation");
            }
            if (!oCaps || !oCaps.readOperations) {
                return missingBackend(Gap.CHECK_STATUS);
            }
            return result(true);
        },

        /** Synchronizacja faktur zakupowych z KSeF — akcja globalna, niezależna od zaznaczenia. */
        evaluateSync: function (oCaps, sViewKey) {
            if (sViewKey === KsefContract.ViewKey.SALES) {
                return result(false, "ksefActionReasonSyncInboundOnly");
            }
            if (!oCaps || !oCaps.syncInbound) {
                return missingBackend(Gap.SYNC);
            }
            return result(true);
        },

        evaluateViewXml: function (oInvoice, oCaps) {
            if (!oInvoice) {
                return result(false, "ksefActionReasonNoSelection");
            }
            var bHasDocument = oInvoice.Direction === Direction.INBOUND
                ? !!oInvoice.KsefNumber
                : !!latestOperationOfType(oInvoice, KsefContract.OperationType.SEND_INVOICE) || !!oInvoice.KsefNumber;
            if (!bHasDocument) {
                return result(false, "ksefActionReasonNoXml");
            }
            if (!oCaps || !oCaps.viewXml) {
                return missingBackend(Gap.XML);
            }
            return result(true);
        },

        evaluateDownloadUpo: function (oInvoice, oCaps) {
            if (!oInvoice) {
                return result(false, "ksefActionReasonNoSelection");
            }
            if (oInvoice.Direction !== Direction.OUTBOUND) {
                return result(false, "ksefActionReasonOutboundOnly");
            }
            if (oInvoice.KsefStatus !== "ACCEPTED" && oInvoice.KsefStatus !== DerivedStatus.RECORDED_IN_SAP) {
                return result(false, "ksefActionReasonNotAccepted");
            }
            if (!oCaps || !oCaps.downloadUpo) {
                return missingBackend(Gap.UPO);
            }
            return result(true);
        },

        evaluateViewDetails: function (oInvoice) {
            return oInvoice ? result(true) : result(false, "ksefActionReasonNoSelection");
        },

        evaluateHistory: function (oInvoice, oCaps) {
            if (!oInvoice) {
                return result(false, "ksefActionReasonNoSelection");
            }
            if (!oCaps || !oCaps.readOperations) {
                return missingBackend(Gap.HISTORY);
            }
            return result(true);
        },

        evaluateCheckDuplicate: function (oInvoice, oCaps) {
            if (!oInvoice) {
                return result(false, "ksefActionReasonNoSelection");
            }
            if (oInvoice.Direction !== Direction.INBOUND) {
                return result(false, "ksefActionReasonInboundOnly");
            }
            if (!oCaps || !oCaps.checkDuplicate) {
                return missingBackend(Gap.DUPLICATE);
            }
            return result(true);
        },

        /**
         * @param {object|null} oInvoice zaznaczony wiersz modelu widoku
         * @param {object} oCaps flagi dostępności backendu (manifest → sap.ui5/config/ksefCockpit/capabilities)
         * @param {string} sTargetEnvironment środowisko KSeF skonfigurowane dla CAP (TEST/DEMO/PROD)
         * @param {string} sViewKey aktywny widok
         * @returns {Object<string, {enabled: boolean, reasonKey: ?string, reasonArgs: string[]}>}
         */
        evaluateAll: function (oInvoice, oCaps, sTargetEnvironment, sViewKey) {
            return {
                refresh: result(true),
                sync: KsefActionRules.evaluateSync(oCaps, sViewKey),
                send: KsefActionRules.evaluateSend(oInvoice, oCaps, sTargetEnvironment),
                checkStatus: KsefActionRules.evaluateCheckStatus(oInvoice, oCaps),
                viewXml: KsefActionRules.evaluateViewXml(oInvoice, oCaps),
                downloadUpo: KsefActionRules.evaluateDownloadUpo(oInvoice, oCaps),
                viewDetails: KsefActionRules.evaluateViewDetails(oInvoice),
                history: KsefActionRules.evaluateHistory(oInvoice, oCaps),
                checkDuplicate: KsefActionRules.evaluateCheckDuplicate(oInvoice, oCaps)
            };
        }
    };

    return KsefActionRules;
});
