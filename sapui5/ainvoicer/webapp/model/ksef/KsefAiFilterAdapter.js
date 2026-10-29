sap.ui.define([
    "sap/ui/model/Filter",
    "sap/ui/model/FilterOperator",
    "sap/ui/model/Sorter",
    "ainvoicer/model/ksef/KsefContract"
], function (Filter, FilterOperator, Sorter, KsefContract) {
    "use strict";

    var MAX_STRING_LENGTH = 100;
    var MAX_LIMIT = 1000;
    var ENUM_FIELDS = ["Direction", "OriginProcess", "KsefStatus", "InvoiceStatus"];
    var ALLOWED_OPERATORS = ["EQ", "NE", "GT", "GE", "LT", "LE", "BT", "Contains", "StartsWith", "EndsWith"];

    /**
     * Mapowanie ścieżek zwracanych przez obecną usługę Python /filter (model ZC_FI_ACDOCA)
     * na pola nowej tabeli. "approximate" = inna semantyka; użytkownik dostaje o tym informację.
     * Pola bez odpowiednika (np. DocumentNo, Vendor, Customer, Account) są ignorowane jawnie.
     */
    var LEGACY_PATH_MAP = Object.freeze({
        CompanyCode: { path: "CompanyCode", approximate: false },
        CurrencyCode: { path: "CurrencyCode", approximate: false },
        PostingDate: { path: "PostingDate", approximate: false },
        DocumentDate: { path: "IssueDate", approximate: true },
        AmountDocument: { path: "GrossAmount", approximate: true },
        Reference: { path: "InvoiceNumber", approximate: true },
        FiscalYear: { path: "IssueDate", approximate: true, transform: "year" }
    });

    function resolvePath(sPath) {
        if (Object.prototype.hasOwnProperty.call(KsefContract.FilterableFields, sPath)) {
            return { path: sPath, approximate: false, native: true };
        }
        // hasOwnProperty: ścieżki z LLM typu "__proto__"/"constructor" nie mogą trafić w prototyp obiektu.
        return Object.prototype.hasOwnProperty.call(LEGACY_PATH_MAP, sPath)
            ? Object.assign({ native: false }, LEGACY_PATH_MAP[sPath])
            : null;
    }

    /** Waliduje i konwertuje wartość; zwraca undefined dla wartości niedozwolonej. */
    function normalizeValue(vValue, sType, sTargetPath) {
        if (vValue === null || vValue === undefined) {
            return undefined;
        }
        if (sType === "number") {
            // Number() zamiast parseFloat: "1; DROP ..." ma być odrzucone, a nie obcięte do 1.
            var sNumber = typeof vValue === "number" ? null : String(vValue).replace(/\s/g, "").replace(",", ".");
            if (sNumber === "") {
                return undefined;
            }
            var fValue = sNumber === null ? vValue : Number(sNumber);
            return isFinite(fValue) ? fValue : undefined;
        }
        if (sType === "date") {
            var sDate = String(vValue).trim().substring(0, 10);
            return /^\d{4}-\d{2}-\d{2}$/.test(sDate) ? sDate : undefined;
        }
        var sValue = String(vValue).trim();
        if (!sValue || sValue.length > MAX_STRING_LENGTH) {
            return undefined;
        }
        return ENUM_FIELDS.indexOf(sTargetPath) !== -1 ? sValue.toUpperCase() : sValue;
    }

    function ignored(aIgnored, oInput, sReason) {
        aIgnored.push({ path: oInput && oInput.path, operator: oInput && oInput.operator, reason: sReason });
    }

    /**
     * Tłumaczy odpowiedź usługi AI na filtry klienckie nowej tabeli.
     * Odpowiedź LLM jest traktowana jako niezaufane dane: lista biała pól i operatorów,
     * walidacja typów, brak wykonywania wyrażeń, brak przekazywania SQL do backendu.
     */
    var KsefAiFilterAdapter = {

        LEGACY_PATH_MAP: LEGACY_PATH_MAP,

        /**
         * @param {{filters?: object[], sortBy?: {path: string, descending: boolean}, limit?: number}} oResponse
         * @returns {{filters: sap.ui.model.Filter[], applied: object[], ignored: object[], sorter: ?sap.ui.model.Sorter, limit: ?number}}
         */
        translate: function (oResponse) {
            var oResult = { filters: [], applied: [], ignored: [], sorter: null, limit: null };
            if (!oResponse || typeof oResponse !== "object") {
                return oResult;
            }

            (Array.isArray(oResponse.filters) ? oResponse.filters : []).forEach(function (oInput) {
                if (!oInput || typeof oInput.path !== "string") {
                    ignored(oResult.ignored, oInput, "invalid");
                    return;
                }
                var oTarget = resolvePath(oInput.path);
                if (!oTarget) {
                    ignored(oResult.ignored, oInput, "unsupportedField");
                    return;
                }
                if (ALLOWED_OPERATORS.indexOf(oInput.operator) === -1) {
                    ignored(oResult.ignored, oInput, "unsupportedOperator");
                    return;
                }
                var sType = KsefContract.FilterableFields[oTarget.path];
                var sOperator = oInput.operator;
                var vValue1;
                var vValue2;

                if (oTarget.transform === "year") {
                    // FiscalYear EQ 2026 → IssueDate BT 2026-01-01..2026-12-31 (rok podatkowy ≈ rok kalendarzowy).
                    var sYear = String(oInput.value1 || "").trim();
                    if (sOperator !== "EQ" || !/^\d{4}$/.test(sYear)) {
                        ignored(oResult.ignored, oInput, "invalidValue");
                        return;
                    }
                    sOperator = "BT";
                    vValue1 = sYear + "-01-01";
                    vValue2 = sYear + "-12-31";
                } else {
                    if (sType !== "string" && ["Contains", "StartsWith", "EndsWith"].indexOf(sOperator) !== -1) {
                        ignored(oResult.ignored, oInput, "unsupportedOperator");
                        return;
                    }
                    vValue1 = normalizeValue(oInput.value1, sType, oTarget.path);
                    vValue2 = sOperator === "BT" ? normalizeValue(oInput.value2, sType, oTarget.path) : undefined;
                    if (vValue1 === undefined || (sOperator === "BT" && vValue2 === undefined)) {
                        ignored(oResult.ignored, oInput, "invalidValue");
                        return;
                    }
                }

                oResult.filters.push(sOperator === "BT"
                    ? new Filter(oTarget.path, FilterOperator.BT, vValue1, vValue2)
                    : new Filter({ path: oTarget.path, operator: FilterOperator[sOperator], value1: vValue1, caseSensitive: false }));
                oResult.applied.push({
                    path: oTarget.path,
                    mappedFrom: oTarget.native ? null : oInput.path,
                    approximate: oTarget.approximate,
                    operator: sOperator,
                    value1: vValue1,
                    value2: vValue2
                });
            });

            if (oResponse.sortBy && typeof oResponse.sortBy.path === "string") {
                var oSortTarget = resolvePath(oResponse.sortBy.path);
                if (oSortTarget && !oSortTarget.transform) {
                    oResult.sorter = new Sorter(oSortTarget.path, oResponse.sortBy.descending === true);
                } else {
                    ignored(oResult.ignored, { path: oResponse.sortBy.path, operator: "sort" }, "unsupportedField");
                }
            }

            if (Number.isInteger(oResponse.limit) && oResponse.limit > 0) {
                oResult.limit = Math.min(oResponse.limit, MAX_LIMIT);
            }
            return oResult;
        }
    };

    return KsefAiFilterAdapter;
});
