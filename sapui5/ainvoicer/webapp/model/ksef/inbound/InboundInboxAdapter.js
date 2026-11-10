sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundContract",
    "ainvoicer/model/ksef/inbound/InboundInvoiceMapper",
    "ainvoicer/model/ksef/inbound/InboundQuery"
], function (InboundContract, InboundInvoiceMapper, InboundQuery) {
    "use strict";

    var Mode = InboundContract.Mode;

    var ErrorCode = Object.freeze({
        DISABLED: "DISABLED",                 // funkcja wyłączona konfiguracją (np. CF bez backendu) — bez HTTP
        BACKEND_MISSING: "BACKEND_MISSING",   // tryb cap bez skonfigurowanej encji (brak konfiguracji)
        VALIDATION: "VALIDATION",             // błędne filtry — odrzucone przed HTTP
        HTTP: "HTTP",
        TIMEOUT: "TIMEOUT",
        NETWORK: "NETWORK",
        PARSE: "PARSE",
        NOT_FOUND: "NOT_FOUND"
    });

    var DEFAULT_PAGE_SIZE = 20;
    var DEFAULT_TIMEOUT_MS = 15000;
    var MAX_TIMEOUT_MS = 60000;
    var MAX_MESSAGE_LENGTH = 300;
    var SERVICE_PATH = /^\/(?!\/)[A-Za-z0-9_/-]+\/$/;
    var ENTITY_SET = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;
    var MODULE_PATH = /^[A-Za-z0-9_]+(\/[A-Za-z0-9_.-]+)+\.json$/;

    function adapterError(sCode, sMessage, mDetails) {
        var oError = new Error(sMessage);
        oError.code = sCode;
        Object.assign(oError, mDetails || {});
        return oError;
    }

    function rejectWith(sCode, sMessage, mDetails) {
        return Promise.reject(adapterError(sCode, sMessage, mDetails));
    }

    /**
     * Tylko ten sam origin co aplikacja. Chroni przed przypadkowym/złośliwym skierowaniem odczytu
     * (z ciasteczkami sesji approutera) na obcy host — także dla ścieżek mock z manifestu.
     */
    function assertSameOrigin(sUrl) {
        var oLocation = typeof window !== "undefined" && window.location;
        var oUrl;
        try {
            oUrl = new URL(sUrl, oLocation ? oLocation.href : undefined);
        } catch (oError) {
            throw adapterError(ErrorCode.PARSE, "INBOX_URL_INVALID");
        }
        // "//host", "javascript:", "data:" i obce hosty mają inny (lub "null") origin — wszystkie odrzucone.
        if (!oLocation || oUrl.origin !== oLocation.origin) {
            throw adapterError(ErrorCode.PARSE, "INBOX_URL_NOT_SAME_ORIGIN");
        }
        return sUrl;
    }

    /** Bezpieczny, skrócony komunikat błędu OData (bez treści technicznych, nagłówków i tokenów). */
    function readODataError(oResponse) {
        return Promise.resolve().then(function () {
            return oResponse.json();
        }).then(function (oBody) {
            var sMessage = oBody && oBody.error && oBody.error.message;
            return typeof sMessage === "string" ? sMessage.substring(0, MAX_MESSAGE_LENGTH) : "";
        }, function () {
            return "";
        });
    }

    /**
     * Adapter danych KSeF Inbox (UI5-10). Provider wybiera WYŁĄCZNIE `dataMode` z manifestu:
     *  - "mock":     syntetyczne dane z pliku JSON (localService) — widocznie oznaczane w UI jako demo;
     *  - "cap":      przyszły read-only CAP OData V4 `KsefService.<entitySet>` (INT-02). Bez `cap.entitySet`
     *                zwraca BACKEND_MISSING bez HTTP. Nigdy nie przechodzi na mock po błędzie;
     *  - inne/brak:  DISABLED — żadnego HTTP, żadnych danych (wariant CF bez backendu).
     * Adapter nie łączy się z KSeF, nie zna tokenów KSeF i nie zapisuje danych w localStorage.
     * Autoryzacja (granty NIP/rola) należy do CAP — filtr UI nie jest mechanizmem dostępu.
     *
     * @param {object} oConfig sap.ui5/config/ksefInbox
     * @param {function} [fnFetch] wstrzykiwany fetch (testy)
     */
    function InboundInboxAdapter(oConfig, fnFetch) {
        this._oConfig = oConfig || {};
        var sMode = this._oConfig.dataMode;
        this._sMode = sMode === Mode.MOCK || sMode === Mode.CAP ? sMode : Mode.DISABLED;
        this._fnFetch = fnFetch || (typeof fetch === "function" ? fetch.bind(window) : null);
        this._aMockRows = null;
        this._iMockRejected = 0;
    }

    InboundInboxAdapter.ErrorCode = ErrorCode;
    InboundInboxAdapter.Mode = Mode;

    InboundInboxAdapter.prototype.getMode = function () {
        return this._sMode;
    };

    InboundInboxAdapter.prototype.isAvailable = function () {
        return this._sMode !== Mode.DISABLED;
    };

    /** Rozmiar strony z konfiguracji, ograniczony do 1..MAX_TOP. */
    InboundInboxAdapter.prototype.getPageSize = function () {
        var iSize = this._oConfig.pageSize;
        return Number.isSafeInteger(iSize) && iSize > 0 ? Math.min(iSize, InboundQuery.MAX_TOP) : DEFAULT_PAGE_SIZE;
    };

    /** Środowiska do filtra — tylko znane wartości z konfiguracji. */
    InboundInboxAdapter.prototype.getEnvironments = function () {
        return (Array.isArray(this._oConfig.environments) ? this._oConfig.environments : []).filter(function (sEnv) {
            return InboundContract.has(InboundContract.Environment, sEnv);
        });
    };

    /**
     * Akcje przyszłe: zawsze false. Backend nie ma kontraktu dla podglądu XML, duplikatów, powiązania FI/MM
     * ani księgowania — flaga w manifeście nie może ich włączyć (INT-0x musi zmienić tę metodę świadomie).
     */
    InboundInboxAdapter.prototype.getCapabilities = function () {
        return InboundContract.FutureActions.reduce(function (oResult, sAction) {
            oResult[sAction] = false;
            return oResult;
        }, {});
    };

    /**
     * Jedna strona listy.
     * @param {object} oRequest { filters, sort: {field, descending}, skip, top, reload }
     * @returns {Promise<{rows: object[], count: number, skip: number, top: number, hasMore: boolean, rejected: number}>}
     */
    InboundInboxAdapter.prototype.loadPage = function (oRequest) {
        if (this._sMode === Mode.DISABLED) {
            return rejectWith(ErrorCode.DISABLED, "INBOX_DISABLED");
        }
        var oQuery = oRequest || {};
        var oNormalized = InboundQuery.normalize(oQuery.filters);
        if (oNormalized.errors.length) {
            return rejectWith(ErrorCode.VALIDATION, "INBOX_FILTER_INVALID", { errors: oNormalized.errors });
        }
        var iSkip = Math.max(0, Math.floor(oQuery.skip || 0));
        var iTop = Math.min(Math.max(1, Math.floor(oQuery.top || this.getPageSize())), InboundQuery.MAX_TOP);
        var oSort = InboundQuery.normalizeSort(oQuery.sort);

        if (this._sMode === Mode.MOCK) {
            return this._loadMockRows(!!oQuery.reload).then(function (oData) {
                var aMatching = InboundQuery.sortRows(oData.rows.filter(function (oRow) {
                    return InboundQuery.matches(oRow, oNormalized.filters);
                }), oSort);
                return {
                    rows: aMatching.slice(iSkip, iSkip + iTop),
                    count: aMatching.length,
                    skip: iSkip,
                    top: iTop,
                    hasMore: iSkip + iTop < aMatching.length,
                    rejected: oData.rejected
                };
            });
        }
        var sQuery = InboundQuery.toQueryString(InboundQuery.toODataQuery(oNormalized.filters, oSort, iSkip, iTop));
        var that = this;
        return Promise.resolve().then(function () {
            return that._getJson(that._capEntityUrl() + "?" + sQuery);
        }).then(function (oBody) {
            // Kompletna odpowiedź OData 4.0: tablica value + @odata.count. Inaczej nie pokazujemy „pustej” listy.
            var vCount = oBody && oBody["@odata.count"];
            var iCount = typeof vCount === "string" && /^\d+$/.test(vCount) ? Number(vCount) : vCount;
            if (!oBody || !Array.isArray(oBody.value) || !Number.isSafeInteger(iCount) || iCount < 0
                    || oBody.value.length > iTop) {
                throw adapterError(ErrorCode.PARSE, "INBOX_RESPONSE_INVALID");
            }
            var oMapped = InboundInvoiceMapper.toRows(oBody.value);
            return {
                rows: oMapped.rows,
                count: iCount,
                skip: iSkip,
                top: iTop,
                hasMore: iSkip + oBody.value.length < iCount,
                rejected: oMapped.rejected
            };
        });
    };

    /** Szczegóły jednej faktury (odczyt). Zwraca wiersz zmapowany jak na liście. */
    InboundInboxAdapter.prototype.loadInvoice = function (sId) {
        if (this._sMode === Mode.DISABLED) {
            return rejectWith(ErrorCode.DISABLED, "INBOX_DISABLED");
        }
        if (!InboundInvoiceMapper.UUID_PATTERN.test(String(sId || ""))) {
            return rejectWith(ErrorCode.PARSE, "INBOX_ID_INVALID");
        }
        var sKey = String(sId).toLowerCase();
        if (this._sMode === Mode.MOCK) {
            return this._loadMockRows(false).then(function (oData) {
                var oRow = oData.rows.filter(function (oItem) { return oItem.id === sKey; })[0];
                if (!oRow) {
                    throw adapterError(ErrorCode.NOT_FOUND, "INBOX_NOT_FOUND", { status: 404 });
                }
                return oRow;
            });
        }
        var that = this;
        return Promise.resolve().then(function () {
            // cds.UUID → Edm.Guid: literał klucza bez cudzysłowów (zwalidowany UUID). Potwierdzić w INT-02 z $metadata.
            return that._getJson(that._capEntityUrl() + "(" + sKey + ")?$select="
                + encodeURIComponent(InboundContract.Fields.join(",")));
        }).then(function (oBody) {
            var oRow = InboundInvoiceMapper.toRow(oBody);
            if (!oRow || oRow.id !== sKey) {
                throw adapterError(ErrorCode.PARSE, "INBOX_RESPONSE_INVALID");
            }
            return oRow;
        });
    };

    // ---------------------------------------------------------------- providery

    /** Mock: jeden plik JSON w kształcie odpowiedzi OData ({ value: [...] }), mapowany jak dane CAP. */
    InboundInboxAdapter.prototype._loadMockRows = function (bReload) {
        if (this._aMockRows && !bReload) {
            return Promise.resolve({ rows: this._aMockRows, rejected: this._iMockRejected });
        }
        var sPath = (this._oConfig.mock || {}).invoicesPath;
        // Brak ścieżki = brak danych. Nie ma domyślnego pliku, więc artefakt bez konfiguracji mock nie ładuje fixture.
        if (typeof sPath !== "string" || !MODULE_PATH.test(sPath) || /(^|\/)\.\.(\/|$)/.test(sPath)) {
            return rejectWith(ErrorCode.BACKEND_MISSING, "INBOX_MOCK_NOT_CONFIGURED", { gap: "INBOX-MOCK" });
        }
        var that = this;
        return Promise.resolve().then(function () {
            return that._getJson(sap.ui.require.toUrl(sPath));
        }).then(function (oBody) {
            if (!oBody || !Array.isArray(oBody.value)) {
                throw adapterError(ErrorCode.PARSE, "INBOX_RESPONSE_INVALID");
            }
            var oMapped = InboundInvoiceMapper.toRows(oBody.value);
            that._aMockRows = oMapped.rows;
            that._iMockRejected = oMapped.rejected;
            return { rows: oMapped.rows, rejected: oMapped.rejected };
        });
    };

    /** Same-origin URL zbioru encji CAP; brak entitySet = INT-02 jeszcze nie podłączone (bez HTTP). */
    InboundInboxAdapter.prototype._capEntityUrl = function () {
        var oCap = this._oConfig.cap || {};
        if (!oCap.entitySet) {
            throw adapterError(ErrorCode.BACKEND_MISSING, "INBOX_BACKEND_NOT_CONFIGURED", { gap: "INBOX-OVERVIEW" });
        }
        if (typeof oCap.serviceUrl !== "string" || !SERVICE_PATH.test(oCap.serviceUrl) || /(^|\/)\.\.(\/|$)/.test(oCap.serviceUrl)
                || !ENTITY_SET.test(oCap.entitySet)) {
            throw adapterError(ErrorCode.PARSE, "INBOX_SERVICE_PATH_INVALID");
        }
        return oCap.serviceUrl + oCap.entitySet;
    };

    /**
     * GET JSON: same-origin, bez przekierowań, z limitem czasu (połączenie + treść). Tylko odczyt —
     * adapter nie ma metod POST/PATCH/DELETE. Abort przerywa wyłącznie bieżący odczyt.
     */
    InboundInboxAdapter.prototype._getJson = function (sUrl) {
        if (!this._fnFetch) {
            return rejectWith(ErrorCode.NETWORK, "fetch is not available");
        }
        try {
            assertSameOrigin(sUrl);
        } catch (oError) {
            return Promise.reject(oError);
        }
        var iConfigured = (this._oConfig.cap || {}).readTimeoutMs;
        var iTimeout = Number.isSafeInteger(iConfigured) && iConfigured > 0 ? Math.min(iConfigured, MAX_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS;
        var oController = typeof AbortController === "function" ? new AbortController() : null;
        var fnFetch = this._fnFetch;
        var iTimer;
        return Promise.race([
            Promise.resolve().then(function () {
                return fnFetch(sUrl, {
                    method: "GET",
                    credentials: "same-origin",
                    redirect: "error",
                    signal: oController ? oController.signal : undefined,
                    // Edm.Decimal jako string (bez utraty precyzji); kontrakt OData 4.0 (@odata.count).
                    headers: { Accept: "application/json;IEEE754Compatible=true", "OData-MaxVersion": "4.0" }
                });
            }).then(function (oResponse) {
                if (!oResponse.ok) {
                    return readODataError(oResponse).then(function (sMessage) {
                        throw adapterError(oResponse.status === 404 ? ErrorCode.NOT_FOUND : ErrorCode.HTTP,
                            sMessage || "HTTP " + oResponse.status, { status: oResponse.status });
                    });
                }
                return Promise.resolve().then(function () {
                    return oResponse.json();
                }).catch(function () {
                    throw adapterError(ErrorCode.PARSE, "INBOX_RESPONSE_NOT_JSON");
                });
            }, function (oError) {
                if (oError && oError.code) {
                    throw oError;
                }
                throw adapterError(ErrorCode.NETWORK, "INBOX_NETWORK_ERROR");
            }),
            new Promise(function (resolve, reject) {
                iTimer = setTimeout(function () {
                    reject(adapterError(ErrorCode.TIMEOUT, "INBOX_READ_TIMEOUT"));
                    if (oController) {
                        oController.abort();
                    }
                }, iTimeout);
            })
        ]).finally(function () {
            clearTimeout(iTimer);
        });
    };

    return InboundInboxAdapter;
});
