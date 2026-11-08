sap.ui.define([
    "sap/ui/model/Filter",
    "sap/ui/model/FilterOperator",
    "ainvoicer/model/ksef/KsefContract"
], function (Filter, FilterOperator, KsefContract) {
    "use strict";

    var SEARCH_FIELDS = ["InvoiceNumber", "SellerName", "BuyerName", "SellerNip", "BuyerNip", "KsefNumber"];
    // MaxLength z metadata UI H01: zbyt długi literał w dowolnej gałęzi OR odrzuca całe zapytanie.
    var SEARCH_LENGTHS = { InvoiceNumber: 35, SellerName: 100, BuyerName: 100, SellerNip: 10, BuyerNip: 10, KsefNumber: 50 };
    /** Pola wyliczane w UI ze stanu CAP — RAP ich nie zna, więc nie trafiają do $filter. */
    var CLIENT_ONLY_FIELDS = ["KsefStatus"];

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
                amountTo: "",
                currencyCode: ""
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
                if (sSearch.length > 100) { throw new Error("Search text must contain at most 100 characters"); }
                aFilters.push(new Filter({
                    filters: SEARCH_FIELDS.filter(function (sPath) { return sSearch.length <= SEARCH_LENGTHS[sPath]; }).map(function (sPath) {
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
            if (oState.currencyCode && oState.currencyCode.trim()) {
                aFilters.push(new Filter("CurrencyCode", FilterOperator.EQ, oState.currencyCode.trim().toUpperCase()));
            }
            return aFilters;
        },

        /**
         * Filtry dla serwera RAP ($filter): te same drzewa bez pól wyliczanych w UI (KsefStatus).
         * Filtr KsefStatus działa dalej po stronie klienta na pobranej stronie (dwufazowy filtr CAP→RAP
         * jest kolejnym krokiem; zob. ADR-0005 „UI Data Sources”).
         * @param {sap.ui.model.Filter[]} aFilters filtry z build() i adaptera AI
         * @returns {sap.ui.model.Filter[]}
         */
        serverFilters: function (aFilters) {
            function strip(oFilter) {
                if (oFilter.aFilters) {
                    var aMapped = oFilter.aFilters.map(strip);
                    // Usunięcie składnika OR zawęziłoby wynik serwera i zgubiło poprawne wiersze.
                    if (!oFilter.bAnd && aMapped.some(function (f) { return !f; })) { return null; }
                    var aChildren = aMapped.filter(Boolean);
                    if (!aChildren.length) {
                        return null;
                    }
                    return aChildren.length === 1 ? aChildren[0] : new Filter({ filters: aChildren, and: !!oFilter.bAnd });
                }
                if (CLIENT_ONLY_FIELDS.indexOf(oFilter.sPath) !== -1) { return null; }
                // H01 UI V4 nie obsługuje tolower(). Nie mutujemy filtrów klienckich ani wyniku AI.
                if (oFilter.bCaseSensitive === false) {
                    return new Filter({ path: oFilter.sPath, operator: oFilter.sOperator,
                        value1: oFilter.oValue1, value2: oFilter.oValue2, caseSensitive: true });
                }
                return oFilter;
            }
            return (aFilters || []).map(strip).filter(Boolean);
        },

        /** RAP wymaga waluty przy porównaniu kwoty; waluta wewnątrz OR nie ogranicza całego wyniku. */
        hasUnscopedAmount: function (aFilters) {
            function hasAmount(f) {
                return f.sPath === "GrossAmount" || (f.aFilters || []).some(hasAmount);
            }
            function hasCurrency(f) {
                return f.aFilters ? f.bAnd && f.aFilters.some(hasCurrency)
                    : f.sPath === "CurrencyCode" && f.sOperator === FilterOperator.EQ && /^[A-Z]{3}$/.test(f.oValue1);
            }
            return aFilters.some(hasAmount) && !aFilters.some(hasCurrency);
        },

        /** RAP nie zna pól wyliczanych z CAP; takie sortery zostają wyłącznie na bieżącej stronie. */
        serverSorters: function (aSorters) {
            return (aSorters || []).filter(function (s) {
                return ["KsefStatus", "LastUpdate", "OperationReference"].indexOf(s.sPath) === -1;
            });
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
