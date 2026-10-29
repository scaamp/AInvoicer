sap.ui.define([
    "sap/ui/model/Filter",
    "sap/ui/model/FilterOperator",
    "ainvoicer/model/ksef/KsefContract"
], function (Filter, FilterOperator, KsefContract) {
    "use strict";

    var SEARCH_FIELDS = ["InvoiceNumber", "SellerName", "BuyerName", "SellerNip", "BuyerNip", "KsefNumber"];

    function anyOf(sPath, aValues) {
        if (!aValues || !aValues.length) {
            return null;
        }
        return new Filter({
            filters: aValues.map(function (sValue) {
                return new Filter({ path: sPath, operator: FilterOperator.EQ, value1: sValue });
            }),
            and: false
        });
    }

    function isFiniteNumber(vValue) {
        return vValue !== null && vValue !== undefined && vValue !== "" && isFinite(Number(vValue));
    }

    /**
     * Buduje filtry klienckie (JSONModel) nowej tabeli z neutralnego stanu filtrów.
     * Nie tworzy żadnych wyrażeń dla bazy — wynik jest przetwarzany przez sap.ui.model.ClientListBinding.
     */
    var KsefFilterBuilder = {

        /** @returns {object} pusty stan filtrów */
        createEmptyState: function () {
            return {
                search: "",
                originProcesses: [],
                companyCodes: [],
                ksefStatuses: [],
                issueDateFrom: null,
                issueDateTo: null,
                amountFrom: "",
                amountTo: ""
            };
        },

        /** @returns {sap.ui.model.Filter|null} filtr kierunku wynikający z aktywnego widoku */
        buildViewFilter: function (sViewKey) {
            if (sViewKey === KsefContract.ViewKey.PURCHASE) {
                return new Filter("Direction", FilterOperator.EQ, KsefContract.Direction.INBOUND);
            }
            if (sViewKey === KsefContract.ViewKey.SALES) {
                return new Filter("Direction", FilterOperator.EQ, KsefContract.Direction.OUTBOUND);
            }
            return null;
        },

        /**
         * @param {object} oState stan filtrów (createEmptyState)
         * @param {string} sViewKey ALL / PURCHASE / SALES
         * @returns {sap.ui.model.Filter[]} filtry łączone przez AND
         */
        build: function (oState, sViewKey) {
            var aFilters = [];
            var oViewFilter = KsefFilterBuilder.buildViewFilter(sViewKey);
            if (oViewFilter) {
                aFilters.push(oViewFilter);
            }
            if (!oState) {
                return aFilters;
            }

            var sSearch = (oState.search || "").trim();
            if (sSearch) {
                aFilters.push(new Filter({
                    filters: SEARCH_FIELDS.map(function (sPath) {
                        return new Filter({ path: sPath, operator: FilterOperator.Contains, value1: sSearch, caseSensitive: false });
                    }),
                    and: false
                }));
            }

            [
                anyOf("OriginProcess", oState.originProcesses),
                anyOf("CompanyCode", oState.companyCodes),
                anyOf("KsefStatus", oState.ksefStatuses)
            ].forEach(function (oFilter) {
                if (oFilter) {
                    aFilters.push(oFilter);
                }
            });

            // IssueDate jest ciągiem ISO YYYY-MM-DD (Edm.Date), więc porównanie leksykalne jest poprawne.
            if (oState.issueDateFrom) {
                aFilters.push(new Filter("IssueDate", FilterOperator.GE, oState.issueDateFrom));
            }
            if (oState.issueDateTo) {
                aFilters.push(new Filter("IssueDate", FilterOperator.LE, oState.issueDateTo));
            }
            if (isFiniteNumber(oState.amountFrom)) {
                aFilters.push(new Filter("GrossAmount", FilterOperator.GE, Number(oState.amountFrom)));
            }
            if (isFiniteNumber(oState.amountTo)) {
                aFilters.push(new Filter("GrossAmount", FilterOperator.LE, Number(oState.amountTo)));
            }
            return aFilters;
        },

        /** Liczy wiersze widoków All/Purchase/Sales dla liczników zakładek. */
        countByView: function (aRows) {
            return (aRows || []).reduce(function (oCounts, oRow) {
                oCounts.ALL++;
                if (oRow.Direction === KsefContract.Direction.INBOUND) {
                    oCounts.PURCHASE++;
                } else if (oRow.Direction === KsefContract.Direction.OUTBOUND) {
                    oCounts.SALES++;
                }
                return oCounts;
            }, { ALL: 0, PURCHASE: 0, SALES: 0 });
        },

        /** @returns {string[]} posortowane, unikalne wartości pola (dla list wyboru filtrów) */
        distinctValues: function (aRows, sPath) {
            var mSeen = {};
            (aRows || []).forEach(function (oRow) {
                if (oRow[sPath]) {
                    mSeen[oRow[sPath]] = true;
                }
            });
            return Object.keys(mSeen).sort();
        }
    };

    return KsefFilterBuilder;
});
