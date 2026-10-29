sap.ui.define([
    "ainvoicer/model/ksef/KsefContract"
], function (KsefContract) {
    "use strict";

    function toNumber(vValue) {
        if (vValue === null || vValue === undefined || vValue === "") {
            return null;
        }
        // OData V4 Edm.Decimal może przyjść jako string (IEEE754Compatible) albo liczba.
        var fValue = typeof vValue === "number" ? vValue : parseFloat(String(vValue));
        return isNaN(fValue) ? null : fValue;
    }

    function toTime(sTimestamp) {
        if (!sTimestamp) {
            return 0;
        }
        var iTime = Date.parse(sTimestamp);
        return isNaN(iTime) ? 0 : iTime;
    }

    function latestTimestamp(aTimestamps) {
        var sLatest = null;
        aTimestamps.forEach(function (sTimestamp) {
            if (sTimestamp && toTime(sTimestamp) > toTime(sLatest)) {
                sLatest = sTimestamp;
            }
        });
        return sLatest;
    }

    /**
     * Mapuje nagłówek RAP (ZC_AINV_INV_H) i operacje CAP (KsefService.KsefOperations)
     * na jeden wiersz modelu widoku. Funkcje są czyste — bez wywołań sieciowych.
     */
    var KsefInvoiceMapper = {

        /**
         * Grupuje operacje CAP po sapDocumentRef (= RAP InvoiceUUID, patrz SapKsefResultWriteback).
         * @param {object[]} aOperations wiersze KsefOperations
         * @returns {Object<string, object[]>} operacje posortowane malejąco po czasie
         */
        groupOperationsByInvoice: function (aOperations) {
            var mResult = {};
            (aOperations || []).forEach(function (oOperation) {
                var sKey = oOperation && oOperation.sapDocumentRef ? String(oOperation.sapDocumentRef).toLowerCase() : null;
                if (!sKey) {
                    return;
                }
                (mResult[sKey] = mResult[sKey] || []).push(oOperation);
            });
            Object.keys(mResult).forEach(function (sKey) {
                mResult[sKey].sort(KsefInvoiceMapper.compareOperationsDesc);
            });
            return mResult;
        },

        /** Sortuje operacje od najnowszej (modifiedAt, potem createdAt). */
        compareOperationsDesc: function (oA, oB) {
            var iA = toTime(oA.modifiedAt || oA.createdAt);
            var iB = toTime(oB.modifiedAt || oB.createdAt);
            return iB - iA;
        },

        /** @returns {object[]} wszystkie próby SEND_INVOICE (najnowsze pierwsze) — nie tylko najnowsza operacja */
        sendOperations: function (aOperations) {
            return (aOperations || []).filter(function (oOperation) {
                return oOperation && oOperation.operationType === KsefContract.OperationType.SEND_INVOICE;
            }).sort(KsefInvoiceMapper.compareOperationsDesc);
        },

        /**
         * Próba SEND rozstrzygająca o stanie integracji: ACCEPTED > RECONCILIATION_REQUIRED > w toku/nieznana
         * > najnowsza (np. REJECTED). Późniejsza operacja innego typu (CHECK_STATUS) niczego nie ukrywa.
         */
        decisiveSend: function (aSendOperations) {
            var aOps = aSendOperations || [];
            function first(fnTest) {
                return aOps.filter(fnTest)[0] || null;
            }
            return first(function (o) { return o.status === "ACCEPTED"; })
                || first(function (o) { return o.status === "RECONCILIATION_REQUIRED"; })
                || first(function (o) { return !KsefContract.isTerminalStatus(o.status); })
                || aOps[0] || null;
        },

        /**
         * Status prezentowany w tabeli (ADR-0005). Priorytet: (1) numer KSeF w RAP — wynik biznesowy;
         * (2) CAP ACCEPTED bez numeru w RAP — synchronizacja z SAP; (3–5) stan próby SEND w CAP;
         * (6) brak potwierdzenia. Stan CAP nigdy nie nadpisuje potwierdzonego wyniku RAP.
         * @param {object} oHeader nagłówek RAP
         * @param {object[]} aOperations operacje CAP faktury (dowolne typy)
         * @returns {{status: string, source: string}}
         */
        deriveKsefStatus: function (oHeader, aOperations) {
            if (oHeader && oHeader.KsefNumber) {
                return { status: KsefContract.DerivedStatus.RECORDED_IN_SAP, source: KsefContract.Source.RAP };
            }
            var oSend = KsefInvoiceMapper.decisiveSend(KsefInvoiceMapper.sendOperations(aOperations));
            if (oSend && oSend.status === "ACCEPTED") {
                return { status: KsefContract.DerivedStatus.ACCEPTED_SAP_SYNC, source: KsefContract.Source.CAP };
            }
            if (oSend && oSend.status) {
                return { status: oSend.status, source: KsefContract.Source.CAP };
            }
            return { status: KsefContract.DerivedStatus.NO_OPERATION, source: KsefContract.Source.UI };
        },

        /**
         * @param {object} oHeader nagłówek ZC_AINV_INV_H (opcjonalnie z _Items/_References)
         * @param {object[]} aOperations operacje CAP tej faktury, posortowane malejąco
         * @returns {object} wiersz modelu widoku kokpitu
         */
        toInvoiceRow: function (oHeader, aOperations, bIntegrationAvailable) {
            var aOps = (aOperations || []).slice().sort(KsefInvoiceMapper.compareOperationsDesc);
            var oLatest = aOps[0] || null;
            var aSendOps = KsefInvoiceMapper.sendOperations(aOps);
            var oSend = KsefInvoiceMapper.decisiveSend(aSendOps);
            var oStatus = KsefInvoiceMapper.deriveKsefStatus(oHeader, aOps);
            // SAP jest źródłem prawdy numeru KSeF po writeback; potwierdzona próba CAP może go znać wcześniej.
            var oAccepted = oSend && oSend.status === "ACCEPTED" ? oSend : null;
            var sKsefNumber = oHeader.KsefNumber || (oAccepted && oAccepted.ksefNumber) || "";
            var sEnvironment = oHeader.KsefEnvironment || (oSend && oSend.environment) || "";

            return {
                // --- RAP: ZC_AINV_INV_H ---
                InvoiceUUID: oHeader.InvoiceUUID,
                InvoiceNumber: oHeader.InvoiceNumber || "",
                OriginProcess: oHeader.OriginProcess || "",
                Direction: oHeader.Direction || "",
                InvoiceKind: oHeader.InvoiceKind || "",
                InvoiceStatus: oHeader.InvoiceStatus || "",
                CompanyCode: oHeader.CompanyCode || "",
                IssueDate: oHeader.IssueDate || null,
                PostingDate: oHeader.PostingDate || null,
                DueDate: oHeader.DueDate || null,
                SellerNip: oHeader.SellerNip || "",
                SellerName: oHeader.SellerName || "",
                BuyerNip: oHeader.BuyerNip || "",
                BuyerName: oHeader.BuyerName || "",
                NetAmount: toNumber(oHeader.NetAmount),
                TaxAmount: toNumber(oHeader.TaxAmount),
                GrossAmount: toNumber(oHeader.GrossAmount),
                CurrencyCode: oHeader.CurrencyCode || "",
                SapKsefNumber: oHeader.KsefNumber || "",
                SapKsefEnvironment: oHeader.KsefEnvironment || "",
                SapKsefRecordedAt: oHeader.KsefRecordedAt || null,
                SapKsefWriteRequestId: oHeader.KsefWriteRequestId || null,
                SapLastChangedAt: oHeader.LocalLastChangedAt || oHeader.LastChangedAt || null,
                Items: (oHeader._Items || []).map(function (oItem) {
                    return Object.assign({}, oItem, {
                        Quantity: toNumber(oItem.Quantity),
                        UnitPrice: toNumber(oItem.UnitPrice),
                        NetAmount: toNumber(oItem.NetAmount),
                        TaxRate: toNumber(oItem.TaxRate),
                        TaxAmount: toNumber(oItem.TaxAmount),
                        GrossAmount: toNumber(oItem.GrossAmount)
                    });
                }),
                References: (oHeader._References || []).slice(),

                // --- CAP: KsefOperations (najnowsza operacja) ---
                KsefStatus: oStatus.status,
                KsefStatusSource: oStatus.source,
                KsefNumber: sKsefNumber,
                KsefEnvironment: sEnvironment,
                OperationReference: (oLatest && oLatest.referenceNumber) || "",
                LatestOperation: oLatest,
                SendOperation: oSend,
                SendOperations: aSendOps,
                WritebackState: (oSend && oSend.writebackState) || "",
                OperationCount: aOps.length,
                // false = CAP niedostępny: stan integracji nieznany, więc Send musi być zablokowany.
                IntegrationAvailable: bIntegrationAvailable !== false,

                // --- UI ---
                LastUpdate: latestTimestamp([
                    oHeader.LocalLastChangedAt,
                    oHeader.LastChangedAt,
                    oHeader.KsefRecordedAt,
                    oLatest && (oLatest.modifiedAt || oLatest.createdAt)
                ])
            };
        },

        /**
         * Łączy listę nagłówków RAP z operacjami CAP.
         * @returns {object[]} wiersze modelu widoku
         */
        toInvoiceRows: function (aHeaders, aOperations, bIntegrationAvailable) {
            var mOperations = KsefInvoiceMapper.groupOperationsByInvoice(aOperations);
            return (aHeaders || []).map(function (oHeader) {
                var sKey = String(oHeader.InvoiceUUID || "").toLowerCase();
                return KsefInvoiceMapper.toInvoiceRow(oHeader, mOperations[sKey] || [], bIntegrationAvailable);
            });
        }
    };

    return KsefInvoiceMapper;
});
