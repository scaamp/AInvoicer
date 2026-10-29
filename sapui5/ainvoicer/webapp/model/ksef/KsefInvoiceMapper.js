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

        /**
         * Wylicza status KSeF prezentowany w tabeli.
         * Priorytet: najnowsza operacja CAP → numer KSeF zapisany w SAP → brak operacji.
         * @returns {{status: string, source: string}}
         */
        deriveKsefStatus: function (oHeader, oLatestOperation) {
            if (oLatestOperation && oLatestOperation.status) {
                return { status: oLatestOperation.status, source: KsefContract.Source.CAP };
            }
            if (oHeader && oHeader.KsefNumber) {
                return { status: KsefContract.DerivedStatus.RECORDED_IN_SAP, source: KsefContract.Source.RAP };
            }
            return { status: KsefContract.DerivedStatus.NO_OPERATION, source: KsefContract.Source.UI };
        },

        /**
         * @param {object} oHeader nagłówek ZC_AINV_INV_H (opcjonalnie z _Items/_References)
         * @param {object[]} aOperations operacje CAP tej faktury, posortowane malejąco
         * @returns {object} wiersz modelu widoku kokpitu
         */
        toInvoiceRow: function (oHeader, aOperations) {
            var aOps = (aOperations || []).slice().sort(KsefInvoiceMapper.compareOperationsDesc);
            var oLatest = aOps[0] || null;
            var oStatus = KsefInvoiceMapper.deriveKsefStatus(oHeader, oLatest);
            // SAP jest źródłem prawdy numeru KSeF po writeback; operacja CAP może go znać wcześniej.
            var sKsefNumber = oHeader.KsefNumber || (oLatest && oLatest.ksefNumber) || "";
            var sEnvironment = oHeader.KsefEnvironment || (oLatest && oLatest.environment) || "";

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
                OperationCount: aOps.length,

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
        toInvoiceRows: function (aHeaders, aOperations) {
            var mOperations = KsefInvoiceMapper.groupOperationsByInvoice(aOperations);
            return (aHeaders || []).map(function (oHeader) {
                var sKey = String(oHeader.InvoiceUUID || "").toLowerCase();
                return KsefInvoiceMapper.toInvoiceRow(oHeader, mOperations[sKey] || []);
            });
        }
    };

    return KsefInvoiceMapper;
});
