sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundContract",
    "ainvoicer/model/ksef/inbound/InboundInvoiceMapper"
], function (InboundContract, InboundInvoiceMapper) {
    "use strict";

    /**
     * Jedna definicja semantyki filtrów/sortowania KSeF Inbox dla obu providerów:
     *  - mock: `matches` + `sortRows` (emulacja serwera na danych syntetycznych),
     *  - przyszły CAP OData V4: `toODataQuery` ($filter/$orderby/$skip/$top/$count/$select).
     * Wejście użytkownika jest walidowane PRZED HTTP; do OData trafiają wyłącznie pola i statusy z listy białej.
     */

    var STATUS_VALUE = /^[A-Z][A-Z0-9_]{0,39}$/;
    var STATUS_FILTERS = Object.freeze({
        ingestionStatuses: "ingestionStatus",
        integrityStatuses: "integrityStatus",
        sapPostingStatuses: "sapPostingStatus"
    });
    var MAX_TOP = 100;

    function createEmptyFilters() {
        return {
            invoiceNumber: "",
            ksefNumber: "",
            sellerNip: "",
            issueDateFrom: null,
            issueDateTo: null,
            acquisitionDateFrom: null,
            acquisitionDateTo: null,
            ingestionStatuses: [],
            integrityStatuses: [],
            sapPostingStatuses: [],
            environment: ""
        };
    }

    function trimmed(vValue) {
        return typeof vValue === "string" ? vValue.trim() : "";
    }

    /** NIP w filtrze: dopuszczalne separatory "-" i spacje (np. 222-222-22-22); do zapytania tylko cyfry. */
    function normalizeNip(sValue) {
        return trimmed(sValue).replace(/[\s-]/g, "");
    }

    /**
     * Walidacja i normalizacja filtrów. Zwraca { filters, errors }; errors to klucze i18n (+ argumenty).
     * Każdy błąd blokuje zapytanie — UI nie wysyła „poprawionego” filtra bez wiedzy użytkownika.
     */
    function normalize(oInput) {
        var oFilters = Object.assign(createEmptyFilters(), oInput || {});
        var aErrors = [];
        var oResult = createEmptyFilters();

        oResult.invoiceNumber = trimmed(oFilters.invoiceNumber);
        if (oResult.invoiceNumber.length > InboundContract.MaxLength.invoiceNumber) {
            aErrors.push({ key: "inboxFilterErrorTooLong", args: [InboundContract.MaxLength.invoiceNumber] });
        }

        oResult.ksefNumber = trimmed(oFilters.ksefNumber).toUpperCase();
        if (oResult.ksefNumber && !/^[0-9A-F-]{1,64}$/.test(oResult.ksefNumber)) {
            aErrors.push({ key: "inboxFilterErrorKsefNumber" });
        }

        oResult.sellerNip = normalizeNip(oFilters.sellerNip);
        if (oResult.sellerNip && !/^\d{1,10}$/.test(oResult.sellerNip)) {
            aErrors.push({ key: "inboxFilterErrorNip" });
        }

        ["issueDateFrom", "issueDateTo", "acquisitionDateFrom", "acquisitionDateTo"].forEach(function (sKey) {
            var vValue = oFilters[sKey];
            if (vValue && !(typeof vValue === "string" && InboundInvoiceMapper.isRealDate(vValue))) {
                aErrors.push({ key: "inboxFilterErrorDate" });
                return;
            }
            oResult[sKey] = vValue || null;
        });
        if (oResult.issueDateFrom && oResult.issueDateTo && oResult.issueDateFrom > oResult.issueDateTo
                || oResult.acquisitionDateFrom && oResult.acquisitionDateTo && oResult.acquisitionDateFrom > oResult.acquisitionDateTo) {
            aErrors.push({ key: "inboxFilterErrorDateRange" });
        }

        Object.keys(STATUS_FILTERS).forEach(function (sKey) {
            var aValues = Array.isArray(oFilters[sKey]) ? oFilters[sKey] : [];
            if (aValues.some(function (sValue) { return !STATUS_VALUE.test(String(sValue)); })) {
                aErrors.push({ key: "inboxFilterErrorStatus" });
                return;
            }
            oResult[sKey] = aValues.slice(0, 20);
        });

        oResult.environment = trimmed(oFilters.environment);
        if (oResult.environment && !InboundContract.has(InboundContract.Environment, oResult.environment)) {
            aErrors.push({ key: "inboxFilterErrorEnvironment" });
        }

        return { filters: oResult, errors: aErrors };
    }

    /** Sortowanie tylko po polach z listy białej; inaczej domyślne (najnowsze otrzymane). */
    function normalizeSort(oSort) {
        var sField = oSort && oSort.field;
        if (!InboundContract.has(InboundContract.SortableFields, sField)) {
            return Object.assign({}, InboundContract.DefaultSort);
        }
        return { field: sField, descending: !!oSort.descending };
    }

    /**
     * Granice dnia lokalnego (strefa przeglądarki) jako instanty UTC: [from 00:00, to+1 00:00).
     * Data otrzymania to Edm.DateTimeOffset — użytkownik wybiera dni w swojej strefie.
     */
    function localDayStart(sIsoDate, iPlusDays) {
        var aParts = sIsoDate.split("-").map(Number);
        return new Date(aParts[0], aParts[1] - 1, aParts[2] + (iPlusDays || 0), 0, 0, 0, 0);
    }

    function acquisitionBounds(oFilters) {
        return {
            from: oFilters.acquisitionDateFrom ? localDayStart(oFilters.acquisitionDateFrom).getTime() : null,
            toExclusive: oFilters.acquisitionDateTo ? localDayStart(oFilters.acquisitionDateTo, 1).getTime() : null
        };
    }

    // ------------------------------------------------------------------ mock (emulacja serwera)

    /** Czy wiersz spełnia znormalizowane filtry (semantyka 1:1 z toODataQuery). */
    function matches(oRow, oFilters) {
        if (oFilters.invoiceNumber && (!oRow.invoiceNumber
                || oRow.invoiceNumber.toLowerCase().indexOf(oFilters.invoiceNumber.toLowerCase()) === -1)) {
            return false;
        }
        if (oFilters.ksefNumber && (!oRow.ksefNumber || oRow.ksefNumber.indexOf(oFilters.ksefNumber) === -1)) {
            return false;
        }
        if (oFilters.sellerNip) {
            var bExact = oFilters.sellerNip.length === 10;
            if (!oRow.sellerNip || (bExact ? oRow.sellerNip !== oFilters.sellerNip : oRow.sellerNip.indexOf(oFilters.sellerNip) !== 0)) {
                return false;
            }
        }
        // Wiersz bez daty nie spełnia filtra zakresu dat (tak samo jak porównanie z null w OData).
        if ((oFilters.issueDateFrom || oFilters.issueDateTo) && (!oRow.issueDate
                || oFilters.issueDateFrom && oRow.issueDate < oFilters.issueDateFrom
                || oFilters.issueDateTo && oRow.issueDate > oFilters.issueDateTo)) {
            return false;
        }
        var oBounds = acquisitionBounds(oFilters);
        if (oBounds.from !== null || oBounds.toExclusive !== null) {
            var iTime = oRow.acquisitionDate ? new Date(oRow.acquisitionDate).getTime() : NaN;
            if (isNaN(iTime) || oBounds.from !== null && iTime < oBounds.from
                    || oBounds.toExclusive !== null && iTime >= oBounds.toExclusive) {
                return false;
            }
        }
        var bStatusMismatch = Object.keys(STATUS_FILTERS).some(function (sKey) {
            var aValues = oFilters[sKey];
            return aValues.length > 0 && aValues.indexOf(oRow[STATUS_FILTERS[sKey]]) === -1;
        });
        if (bStatusMismatch) {
            return false;
        }
        return !oFilters.environment || oRow.environment === oFilters.environment;
    }

    function compareValues(oLeft, oRight, sField) {
        var vLeft = oLeft[sField];
        var vRight = oRight[sField];
        if (sField === "grossAmount") {
            vLeft = oLeft.grossAmountValid ? vLeft : null;
            vRight = oRight.grossAmountValid ? vRight : null;
        }
        if (vLeft === null || vLeft === undefined) {
            return vRight === null || vRight === undefined ? 0 : 1;
        }
        if (vRight === null || vRight === undefined) {
            return -1;
        }
        switch (InboundContract.SortableFields[sField]) {
            case "decimal":
                return InboundInvoiceMapper.compareDecimal(vLeft, vRight);
            case "datetime":
                return new Date(vLeft).getTime() - new Date(vRight).getTime();
            default:
                return vLeft < vRight ? -1 : vLeft > vRight ? 1 : 0;
        }
    }

    /**
     * Stabilne sortowanie kopii wierszy; wartości puste zawsze na końcu (w obu kierunkach),
     * remis rozstrzyga `id` — tak samo jak `$orderby=<pole>,id` w zapytaniu OData.
     */
    function sortRows(aRows, oSort) {
        var oNormalized = normalizeSort(oSort);
        var iDirection = oNormalized.descending ? -1 : 1;
        return aRows.slice().sort(function (oLeft, oRight) {
            var bLeftEmpty = oLeft[oNormalized.field] === null || oLeft[oNormalized.field] === undefined
                || oNormalized.field === "grossAmount" && !oLeft.grossAmountValid;
            var bRightEmpty = oRight[oNormalized.field] === null || oRight[oNormalized.field] === undefined
                || oNormalized.field === "grossAmount" && !oRight.grossAmountValid;
            if (bLeftEmpty !== bRightEmpty) {
                return bLeftEmpty ? 1 : -1;
            }
            var iResult = bLeftEmpty ? 0 : iDirection * compareValues(oLeft, oRight, oNormalized.field);
            return iResult || (oLeft.id < oRight.id ? -1 : oLeft.id > oRight.id ? 1 : 0);
        });
    }

    // ------------------------------------------------------------------ przyszły CAP OData V4

    /** Literał tekstowy OData: apostrof podwojony (OData ABNF), bez innych transformacji. */
    function literal(sValue) {
        return "'" + String(sValue).replace(/'/g, "''") + "'";
    }

    /**
     * Opcje zapytania OData V4 dla KsefService.InboundInvoiceOverview (kontrakt roboczy, niezweryfikowany LIVE).
     * Wymaga znormalizowanych filtrów. Funkcje: contains/tolower/startswith — standard OData V4.0;
     * obsługę `tolower` w CAP Java potwierdzić w INT-02 testem integracyjnym.
     * @returns {object} mapa opcji systemowych (wartości niezakodowane)
     */
    function toODataQuery(oFilters, oSort, iSkip, iTop) {
        var aClauses = [];
        if (oFilters.invoiceNumber) {
            aClauses.push("contains(tolower(invoiceNumber)," + literal(oFilters.invoiceNumber.toLowerCase()) + ")");
        }
        if (oFilters.ksefNumber) {
            aClauses.push("contains(ksefNumber," + literal(oFilters.ksefNumber) + ")");
        }
        if (oFilters.sellerNip) {
            aClauses.push(oFilters.sellerNip.length === 10 ? "sellerNip eq " + literal(oFilters.sellerNip)
                : "startswith(sellerNip," + literal(oFilters.sellerNip) + ")");
        }
        if (oFilters.issueDateFrom) {
            aClauses.push("issueDate ge " + oFilters.issueDateFrom); // Edm.Date: literał bez cudzysłowów
        }
        if (oFilters.issueDateTo) {
            aClauses.push("issueDate le " + oFilters.issueDateTo);
        }
        var oBounds = acquisitionBounds(oFilters);
        if (oBounds.from !== null) {
            aClauses.push("acquisitionDate ge " + new Date(oBounds.from).toISOString());
        }
        if (oBounds.toExclusive !== null) {
            aClauses.push("acquisitionDate lt " + new Date(oBounds.toExclusive).toISOString());
        }
        Object.keys(STATUS_FILTERS).forEach(function (sKey) {
            var aValues = oFilters[sKey];
            if (aValues.length) {
                var sClause = aValues.map(function (sValue) {
                    return STATUS_FILTERS[sKey] + " eq " + literal(sValue);
                }).join(" or ");
                aClauses.push(aValues.length > 1 ? "(" + sClause + ")" : sClause);
            }
        });
        if (oFilters.environment) {
            aClauses.push("environment eq " + literal(oFilters.environment));
        }
        var oNormalizedSort = normalizeSort(oSort);
        var oQuery = {
            $select: InboundContract.Fields.join(","),
            $orderby: oNormalizedSort.field + (oNormalizedSort.descending ? " desc" : " asc") + ",id asc",
            $skip: Math.max(0, Math.floor(iSkip || 0)),
            $top: Math.min(Math.max(1, Math.floor(iTop || 20)), MAX_TOP),
            $count: true
        };
        if (aClauses.length) {
            oQuery.$filter = aClauses.join(" and ");
        }
        return oQuery;
    }

    /** Kodowanie opcji do query string (kolejność stała — ułatwia testy i cache). */
    function toQueryString(oQuery) {
        return ["$select", "$filter", "$orderby", "$skip", "$top", "$count"].filter(function (sKey) {
            return oQuery[sKey] !== undefined;
        }).map(function (sKey) {
            return sKey + "=" + encodeURIComponent(String(oQuery[sKey]));
        }).join("&");
    }

    return {
        MAX_TOP: MAX_TOP,
        createEmptyFilters: createEmptyFilters,
        normalize: normalize,
        normalizeSort: normalizeSort,
        matches: matches,
        sortRows: sortRows,
        toODataQuery: toODataQuery,
        toQueryString: toQueryString
    };
});
