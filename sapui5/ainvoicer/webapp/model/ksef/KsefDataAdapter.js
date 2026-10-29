sap.ui.define([
    "ainvoicer/model/ksef/KsefInvoiceMapper"
], function (KsefInvoiceMapper) {
    "use strict";

    var Mode = Object.freeze({ MOCK: "mock", CAP: "cap" });

    var ErrorCode = Object.freeze({
        BACKEND_MISSING: "BACKEND_MISSING",
        HTTP: "HTTP",
        NETWORK: "NETWORK",
        PARSE: "PARSE"
    });

    var MAX_MESSAGE_LENGTH = 300;
    var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

    function adapterError(sCode, sMessage, mDetails) {
        var oError = new Error(sMessage);
        oError.code = sCode;
        Object.assign(oError, mDetails || {});
        return oError;
    }

    function backendMissing(sGap) {
        return Promise.reject(adapterError(ErrorCode.BACKEND_MISSING, "Backend contract " + sGap + " is not available", { gap: sGap }));
    }

    function assertUuid(sUuid) {
        if (!UUID_PATTERN.test(String(sUuid || ""))) {
            throw adapterError(ErrorCode.PARSE, "Invalid InvoiceUUID");
        }
    }

    /** Zwraca wyłącznie bezpieczny, skrócony komunikat błędu OData; bez treści technicznych/tokenów. */
    function readODataError(oResponse) {
        return oResponse.json().then(function (oBody) {
            var sMessage = oBody && oBody.error && oBody.error.message;
            return typeof sMessage === "string" ? sMessage.substring(0, MAX_MESSAGE_LENGTH) : oResponse.statusText;
        }, function () {
            return oResponse.statusText;
        });
    }

    /**
     * Adapter źródeł danych kokpitu KSeF.
     *  - mock: lokalne pliki JSON o kształcie RAP ZC_AINV_INV_H (+_Items/_References) i CAP KsefOperations.
     *  - cap:  CAP Java OData V4 (KsefService). Lista faktur wymaga endpointu z luki G-01.
     * Adapter nigdy nie łączy się z KSeF API ani nie przechowuje tokenów; uwierzytelnienie zapewnia
     * sesja aplikacji (approuter / XSUAA) — przeglądarka nie widzi poświadczeń SAP ani JWT KSeF.
     */
    function KsefDataAdapter(oConfig, fnFetch) {
        this._oConfig = oConfig || {};
        this._sMode = this._oConfig.dataMode === Mode.CAP ? Mode.CAP : Mode.MOCK;
        this._fnFetch = fnFetch || (typeof fetch === "function" ? fetch.bind(window) : null);
        this._oMockCache = null;
    }

    KsefDataAdapter.Mode = Mode;
    KsefDataAdapter.ErrorCode = ErrorCode;

    KsefDataAdapter.prototype.getMode = function () {
        return this._sMode;
    };

    /**
     * Efektywne flagi backendu: konfiguracja ograniczona do tego, co tryb rzeczywiście obsługuje.
     * readOperations jest prawdziwe, bo CAP KsefService.KsefOperations ma już READ (rola KsefOperator).
     */
    KsefDataAdapter.prototype.getCapabilities = function () {
        var oCaps = this._oConfig.capabilities || {};
        var oCap = this._oConfig.cap || {};
        var oActions = oCap.actions || {};
        var bCap = this._sMode === Mode.CAP;
        return {
            readOperations: true,
            sendInvoice: bCap && !!oCaps.sendInvoice && !!oActions.send,
            syncInbound: bCap && !!oCaps.syncInbound && !!oActions.sync,
            viewXml: bCap && !!oCaps.viewXml && !!oActions.xml,
            downloadUpo: bCap && !!oCaps.downloadUpo && !!oActions.upo,
            checkDuplicate: bCap && !!oCaps.checkDuplicate && !!oActions.duplicate
        };
    };

    // ---------------------------------------------------------------- odczyt

    /** @returns {Promise<object[]>} wiersze modelu widoku (nagłówek RAP + najnowsza operacja CAP) */
    KsefDataAdapter.prototype.loadInvoices = function () {
        if (this._sMode === Mode.MOCK) {
            return this._loadMock(true).then(function (oData) {
                return KsefInvoiceMapper.toInvoiceRows(oData.headers, oData.operations);
            });
        }
        var sListUrl = (this._oConfig.cap || {}).invoiceListUrl;
        if (!sListUrl) {
            return backendMissing("G-01");
        }
        return Promise.all([
            this._getJson(sListUrl),
            this._getJson(this._capUrl("KsefOperations?$orderby=createdAt desc"))
        ]).then(function (aResults) {
            return KsefInvoiceMapper.toInvoiceRows(aResults[0].value || [], aResults[1].value || []);
        });
    };

    /** @returns {Promise<object>} wiersz z pozycjami i referencjami (_Items/_References) */
    KsefDataAdapter.prototype.loadInvoiceDetails = function (sInvoiceUUID) {
        try {
            assertUuid(sInvoiceUUID);
        } catch (oError) {
            return Promise.reject(oError);
        }
        if (this._sMode === Mode.MOCK) {
            return this._loadMock(false).then(function (oData) {
                var oHeader = oData.headers.filter(function (oItem) {
                    return oItem.InvoiceUUID.toLowerCase() === sInvoiceUUID.toLowerCase();
                })[0];
                if (!oHeader) {
                    throw adapterError(ErrorCode.HTTP, "Invoice not found", { status: 404 });
                }
                var aOps = KsefInvoiceMapper.groupOperationsByInvoice(oData.operations)[sInvoiceUUID.toLowerCase()] || [];
                return KsefInvoiceMapper.toInvoiceRow(oHeader, aOps);
            });
        }
        var sDetailsUrl = (this._oConfig.cap || {}).invoiceDetailsUrl;
        if (!sDetailsUrl) {
            return backendMissing("G-10");
        }
        return Promise.all([
            this._getJson(sDetailsUrl.replace("{InvoiceUUID}", encodeURIComponent(sInvoiceUUID))),
            this.loadOperations(sInvoiceUUID)
        ]).then(function (aResults) {
            return KsefInvoiceMapper.toInvoiceRow(aResults[0], aResults[1]);
        });
    };

    /** Historia operacji CAP dla faktury (najnowsze pierwsze). Odczyt — nie wywołuje KSeF. */
    KsefDataAdapter.prototype.loadOperations = function (sInvoiceUUID) {
        try {
            assertUuid(sInvoiceUUID);
        } catch (oError) {
            return Promise.reject(oError);
        }
        if (this._sMode === Mode.MOCK) {
            return this._loadMock(true).then(function (oData) {
                return KsefInvoiceMapper.groupOperationsByInvoice(oData.operations)[sInvoiceUUID.toLowerCase()] || [];
            });
        }
        // sapDocumentRef to String(120) w CAP; wartość jest zwalidowanym UUID, więc literał jest bezpieczny.
        var sQuery = "KsefOperations?$filter=sapDocumentRef eq '" + sInvoiceUUID.toLowerCase() + "'&$orderby=createdAt desc";
        return this._getJson(this._capUrl(sQuery)).then(function (oBody) {
            return (oBody.value || []).slice().sort(KsefInvoiceMapper.compareOperationsDesc);
        });
    };

    // ---------------------------------------------------------------- akcje

    /**
     * Zlecenie wysyłki przez CAP (luka G-02). Zwraca wyłącznie wynik serwera — zwykle operację w stanie NEW/QUEUED.
     * UI nie interpretuje przyjęcia zlecenia jako przyjęcia faktury przez KSeF.
     */
    KsefDataAdapter.prototype.requestSend = function (sInvoiceUUID) {
        if (!this.getCapabilities().sendInvoice) {
            return backendMissing("G-02");
        }
        try {
            assertUuid(sInvoiceUUID);
        } catch (oError) {
            return Promise.reject(oError);
        }
        var sPath = this._oConfig.cap.actions.send.replace("{InvoiceUUID}", encodeURIComponent(sInvoiceUUID));
        return this._postJson(this._capUrl(sPath), {});
    };

    KsefDataAdapter.prototype.requestSync = function () {
        if (!this.getCapabilities().syncInbound) {
            return backendMissing("G-04");
        }
        return this._postJson(this._capUrl(this._oConfig.cap.actions.sync), {});
    };

    KsefDataAdapter.prototype.requestDuplicateCheck = function (sInvoiceUUID) {
        if (!this.getCapabilities().checkDuplicate) {
            return backendMissing("G-08");
        }
        try {
            assertUuid(sInvoiceUUID);
        } catch (oError) {
            return Promise.reject(oError);
        }
        var sPath = this._oConfig.cap.actions.duplicate.replace("{InvoiceUUID}", encodeURIComponent(sInvoiceUUID));
        return this._postJson(this._capUrl(sPath), {});
    };

    /**
     * URL dokumentu XML/UPO serwowanego przez CAP (luki G-05/G-06). Przeglądarka pobiera go w ramach
     * sesji aplikacji; dokument nigdy nie jest pobierany z KSeF bezpośrednio.
     * @throws {Error} BACKEND_MISSING, gdy endpoint nie jest skonfigurowany
     */
    KsefDataAdapter.prototype.getDocumentUrl = function (sKind, sInvoiceUUID) {
        var mGaps = { xml: "G-05", upo: "G-06" };
        var mCaps = { xml: "viewXml", upo: "downloadUpo" };
        if (!mGaps[sKind] || !this.getCapabilities()[mCaps[sKind]]) {
            throw adapterError(ErrorCode.BACKEND_MISSING, "Backend contract " + (mGaps[sKind] || sKind) + " is not available",
                { gap: mGaps[sKind] || sKind });
        }
        assertUuid(sInvoiceUUID);
        return this._capUrl(this._oConfig.cap.actions[sKind].replace("{InvoiceUUID}", encodeURIComponent(sInvoiceUUID)));
    };

    // ---------------------------------------------------------------- pomocnicze

    KsefDataAdapter.prototype._capUrl = function (sPath) {
        var sBase = (this._oConfig.cap || {}).serviceUrl || "/odata/v4/KsefService/";
        return sBase.replace(/\/?$/, "/") + sPath.replace(/^\//, "");
    };

    KsefDataAdapter.prototype._loadMock = function (bReload) {
        if (this._oMockCache && !bReload) {
            return Promise.resolve(this._oMockCache);
        }
        var oMock = this._oConfig.mock || {};
        var that = this;
        return Promise.all([
            this._getJson(sap.ui.require.toUrl(oMock.headersPath || "ainvoicer/localService/ksef/ZC_AINV_INV_H.json")),
            this._getJson(sap.ui.require.toUrl(oMock.operationsPath || "ainvoicer/localService/ksef/KsefOperations.json"))
        ]).then(function (aResults) {
            that._oMockCache = { headers: aResults[0].value || [], operations: aResults[1].value || [] };
            return that._oMockCache;
        });
    };

    KsefDataAdapter.prototype._getJson = function (sUrl) {
        return this._request(sUrl, { method: "GET" });
    };

    KsefDataAdapter.prototype._postJson = function (sUrl, oBody) {
        var that = this;
        return this._fetchCsrfToken().then(function (sToken) {
            var mHeaders = { "Content-Type": "application/json" };
            if (sToken) {
                mHeaders["X-CSRF-Token"] = sToken;
            }
            return that._request(sUrl, { method: "POST", headers: mHeaders, body: JSON.stringify(oBody) });
        });
    };

    /** Token CSRF approutera/CAP; brak tokenu nie jest błędem (lokalny CAP go nie wymaga). */
    KsefDataAdapter.prototype._fetchCsrfToken = function () {
        if (!this._fnFetch) {
            return Promise.resolve(null);
        }
        return this._fnFetch(this._capUrl(""), {
            method: "HEAD",
            credentials: "same-origin",
            headers: { "X-CSRF-Token": "Fetch" }
        }).then(function (oResponse) {
            return oResponse.headers.get("X-CSRF-Token");
        }, function () {
            return null;
        });
    };

    KsefDataAdapter.prototype._request = function (sUrl, oOptions) {
        if (!this._fnFetch) {
            return Promise.reject(adapterError(ErrorCode.NETWORK, "fetch is not available"));
        }
        var oRequest = Object.assign({ credentials: "same-origin" }, oOptions);
        oRequest.headers = Object.assign({ Accept: "application/json;IEEE754Compatible=true" }, oOptions.headers);
        return this._fnFetch(sUrl, oRequest).then(function (oResponse) {
            if (!oResponse.ok) {
                return readODataError(oResponse).then(function (sMessage) {
                    throw adapterError(ErrorCode.HTTP, sMessage || ("HTTP " + oResponse.status), { status: oResponse.status });
                });
            }
            if (oResponse.status === 204) {
                return {};
            }
            return oResponse.json().catch(function () {
                throw adapterError(ErrorCode.PARSE, "Response is not valid JSON");
            });
        }, function (oError) {
            throw adapterError(ErrorCode.NETWORK, (oError && oError.message) || "Network error");
        });
    };

    return KsefDataAdapter;
});
