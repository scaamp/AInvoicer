sap.ui.define([
    "ainvoicer/model/ksef/KsefInvoiceMapper"
], function (KsefInvoiceMapper) {
    "use strict";

    var Mode = Object.freeze({ MOCK: "mock", CAP: "cap", RAP: "rap" });
    /** Twardy limit strony: UI nigdy nie pobiera całej listy faktur ani całej historii operacji. */
    var MAX_PAGE = 200;

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

    /** Wiersz CAP InvoiceIntegrationStatus → kształt operacji używany przez mapper (jak KsefOperations). */
    function fromIntegrationStatus(oRow) {
        return {
            ID: oRow.operationId,
            sapDocumentRef: oRow.invoiceUUID,
            operationType: oRow.operationType,
            status: oRow.integrationStatus,
            environment: oRow.environment,
            referenceNumber: oRow.ksefReference || "",
            ksefNumber: oRow.ksefNumber || "",
            errorCode: oRow.errorCode || "",
            submissionState: oRow.submissionState || "",
            upoAvailable: !!oRow.upoAvailable,
            writebackState: oRow.writebackState || "",
            writebackOutcome: oRow.writebackOutcome || "",
            createdAt: oRow.createdAt,
            modifiedAt: oRow.modifiedAt
        };
    }

    /**
     * Adapter źródeł danych kokpitu KSeF (ADR-0005).
     *  - mock: lokalne pliki JSON o kształcie RAP ZC_AINV_INV_H (+_Items/_References) i CAP KsefOperations.
     *  - rap:  lista/szczegóły z named OData V4 model "rap" (usługa RAP UI, paging/filter/sort po stronie
     *          serwera), stan integracji z CAP KsefService.InvoiceIntegrationStatus wyłącznie dla UUID
     *          widocznej strony, akcje (requestSend) przez CAP. CAP nie jest proxy listy faktur.
     *  - cap:  dawny wariant „wszystko przez CAP” (G-01) — pozostawiony tylko dla zgodności testów.
     * Adapter nigdy nie łączy się z KSeF API ani nie przechowuje tokenów; uwierzytelnienie zapewnia
     * sesja aplikacji (approuter / XSUAA) — przeglądarka nie widzi poświadczeń SAP ani JWT KSeF.
     */
    function KsefDataAdapter(oConfig, fnFetch, oRapModel) {
        this._oConfig = oConfig || {};
        this._sMode = this._oConfig.dataMode === Mode.CAP ? Mode.CAP
            : this._oConfig.dataMode === Mode.RAP ? Mode.RAP : Mode.MOCK;
        this._fnFetch = fnFetch || (typeof fetch === "function" ? fetch.bind(window) : null);
        this._oRapModel = oRapModel || null;
        this._oMockCache = null;
        this._oLastPage = null;
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
        var bCap = this._sMode === Mode.CAP || this._sMode === Mode.RAP;
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

    /** @returns {?{skip: number, top: number, count: ?number, hasMore: boolean, integrationError: ?string}} */
    KsefDataAdapter.prototype.getLastPage = function () {
        return this._oLastPage;
    };

    /**
     * @param {object} [oQuery] tylko tryb rap: { filters: sap.ui.model.Filter[], sorters: sap.ui.model.Sorter[],
     *   skip: number, top: number } — przekazywane do RAP jako $filter/$orderby/$skip/$top
     * @returns {Promise<object[]>} wiersze modelu widoku (nagłówek RAP + próby SEND z CAP)
     */
    KsefDataAdapter.prototype.loadInvoices = function (oQuery) {
        if (this._sMode === Mode.RAP) {
            return this._loadRapPage(oQuery || {});
        }
        if (this._sMode === Mode.MOCK) {
            return this._loadMock(true).then(function (oData) {
                return KsefInvoiceMapper.toInvoiceRows(oData.headers, oData.operations);
            });
        }
        // Dawny tryb cap nie może stać się proxy listy SAP ani pobierać nieograniczonych operacji.
        return backendMissing("G-01");
    };

    /** @returns {Promise<object>} wiersz z pozycjami i referencjami (_Items/_References) */
    KsefDataAdapter.prototype.loadInvoiceDetails = function (sInvoiceUUID) {
        try {
            assertUuid(sInvoiceUUID);
        } catch (oError) {
            return Promise.reject(oError);
        }
        if (this._sMode === Mode.RAP) {
            return this._loadRapDetails(sInvoiceUUID);
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
        return backendMissing("G-10");
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
        if (this._sMode === Mode.RAP) {
            return this._loadIntegrationStatus([sInvoiceUUID]).then(function (aOperations) {
                return aOperations.slice().sort(KsefInvoiceMapper.compareOperationsDesc);
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
     * Zlecenie wysyłki: CAP action KsefService.requestSend(invoiceUUID). Jedynym wejściem jest UUID —
     * NIP, środowisko, XML i klucz idempotencji wyznacza serwer. Zwraca wyłącznie wynik serwera
     * (operationId + QUEUED); UI nie interpretuje przyjęcia zlecenia jako przyjęcia przez KSeF.
     * Brak automatycznego ponowienia: 409 z serwera oznacza aktywną/przyjętą/nieznaną próbę.
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
        return this._postJson(this._capUrl(sPath), { invoiceUUID: sInvoiceUUID.toLowerCase() });
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

    // ---------------------------------------------------------------- tryb rap

    /** Jedna strona z RAP (serwerowe $filter/$orderby/$skip/$top/$count), potem stan CAP tej strony. */
    KsefDataAdapter.prototype._loadRapPage = function (oQuery) {
        if (!this._oRapModel) {
            return backendMissing("RAP-UI");
        }
        var oRap = this._oConfig.rap || {};
        var iSkip = Math.max(0, oQuery.skip || 0);
        var iTop = Math.min(Math.max(1, oQuery.top || oRap.pageSize || 50), MAX_PAGE);
        var oBinding = this._oRapModel.bindList("/" + (oRap.entitySet || "Invoices"), null,
            oQuery.sorters || [], oQuery.filters || [], { $count: true });
        var that = this;
        var aHeaders;
        var iCount = null;
        return oBinding.requestContexts(iSkip, iTop).then(function (aContexts) {
            aHeaders = aContexts.map(function (oContext) {
                return oContext.getObject();
            });
            return oBinding.getHeaderContext().requestProperty("$count").catch(function () {
                return null;
            });
        }).then(function (vCount) {
            iCount = typeof vCount === "number" ? vCount : null;
            var aUUIDs = aHeaders.map(function (oHeader) {
                return oHeader.InvoiceUUID;
            });
            return that._loadIntegrationStatus(aUUIDs).then(function (aOperations) {
                return { operations: aOperations, available: true, error: null };
            }, function (oError) {
                // CAP niedostępny: pokazujemy dane SAP, ale stan integracji jest nieznany → Send zablokowany.
                return { operations: [], available: false, error: (oError && oError.message) || String(oError) };
            });
        }).then(function (oIntegration) {
            that._oLastPage = {
                skip: iSkip,
                top: iTop,
                count: iCount,
                hasMore: iCount !== null ? iSkip + aHeaders.length < iCount : aHeaders.length === iTop,
                integrationError: oIntegration.error
            };
            return KsefInvoiceMapper.toInvoiceRows(aHeaders, oIntegration.operations, oIntegration.available);
        }).finally(function () {
            oBinding.destroy();
        });
    };

    /** Nagłówek z _Items/_References z RAP i historia z CAP; błąd CAP nie ukrywa danych SAP. */
    KsefDataAdapter.prototype._loadRapDetails = function (sInvoiceUUID) {
        if (!this._oRapModel) {
            return backendMissing("RAP-UI");
        }
        var oRap = this._oConfig.rap || {};
        // Edm.Guid w kluczu OData V4 jest literałem bez cudzysłowów; UUID został zwalidowany.
        var oBinding = this._oRapModel.bindContext("/" + (oRap.entitySet || "Invoices") + "(" + sInvoiceUUID.toLowerCase() + ")",
            null, { $expand: "_Items,_References" });
        var that = this;
        return oBinding.requestObject().then(function (oHeader) {
            if (!oHeader) {
                throw adapterError(ErrorCode.HTTP, "Invoice not found", { status: 404 });
            }
            return that._loadIntegrationStatus([sInvoiceUUID]).then(function (aOperations) {
                return KsefInvoiceMapper.toInvoiceRow(oHeader, aOperations, true);
            }, function () {
                return KsefInvoiceMapper.toInvoiceRow(oHeader, [], false);
            });
        }).finally(function () {
            oBinding.destroy();
        });
    };

    /**
     * Stan integracji CAP wyłącznie dla podanych (widocznych) UUID; bez danych finansowych SAP.
     * Łańcuch `eq ... or` (bez `in`) — ten sam bezpieczny podzbiór OData co w CAP → RAP.
     */
    KsefDataAdapter.prototype._loadIntegrationStatus = function (aUUIDs) {
        var aValid = (aUUIDs || []).filter(function (sUuid) {
            return UUID_PATTERN.test(String(sUuid || ""));
        }).slice(0, MAX_PAGE).map(function (sUuid) { return sUuid.toLowerCase(); });
        if (!aValid.length) {
            return Promise.resolve([]);
        }
        var sFilter = aValid.map(function (sUuid) {
            return "invoiceUUID eq '" + sUuid.toLowerCase() + "'";
        }).join(" or ");
        var sPath = ((this._oConfig.cap || {}).integrationStatus || "InvoiceIntegrationStatus")
            + "?$filter=" + encodeURIComponent(sFilter) + "&$top=" + MAX_PAGE
            + "&$count=true&$orderby=createdAt desc,operationId";
        return this._getJson(this._capUrl(sPath)).then(function (oBody) {
            // HTTP 200 bez kompletnej kolekcji i żądanego count nie dowodzi braku wcześniejszego SEND.
            var vCount = oBody && oBody["@odata.count"];
            var iCount = typeof vCount === "number" || typeof vCount === "string" && /^\d+$/.test(vCount)
                ? Number(vCount) : NaN;
            if (!oBody || !Array.isArray(oBody.value) || !Number.isSafeInteger(iCount) || iCount < 0
                    || oBody.value.some(function (oRow) {
                        return !oRow || aValid.indexOf(String(oRow.invoiceUUID || "").toLowerCase()) === -1
                            || ["operationId", "operationType", "integrationStatus", "environment"].some(function (sField) {
                                return typeof oRow[sField] !== "string" || !oRow[sField].trim();
                            });
                    })) {
                throw adapterError(ErrorCode.PARSE, "CAP_INTEGRATION_HISTORY_INVALID");
            }
            // Uciętej historii nie wolno interpretować jako braku aktywnej/przyjętej próby SEND.
            // Pobieramy ograniczoną partię; nadmiar bezpiecznie wyłącza Send na stronie.
            if (oBody["@odata.nextLink"] || iCount !== oBody.value.length) {
                throw adapterError(ErrorCode.PARSE, "CAP_INTEGRATION_HISTORY_INCOMPLETE");
            }
            return (oBody.value || []).map(fromIntegrationStatus);
        });
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
