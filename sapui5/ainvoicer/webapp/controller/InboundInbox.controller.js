sap.ui.define([
    "sap/ui/core/mvc/Controller",
    "sap/ui/model/json/JSONModel",
    "sap/ui/core/Fragment",
    "sap/ui/core/routing/History",
    "sap/m/MessageToast",
    "sap/m/MessageBox",
    "sap/base/strings/formatMessage",
    "ainvoicer/model/ksef/inbound/InboundContract",
    "ainvoicer/model/ksef/inbound/InboundInboxAdapter",
    "ainvoicer/model/ksef/inbound/InboundQuery",
    "ainvoicer/model/ksef/inbound/InboundFormatter"
], function (Controller, JSONModel, Fragment, History, MessageToast, MessageBox, formatMessage,
    InboundContract, InboundInboxAdapter, InboundQuery, InboundFormatter) {
    "use strict";

    var ErrorCode = InboundInboxAdapter.ErrorCode;
    /** Twardy limit wierszy trzymanych w przeglądarce przy „Load more” (bez nieograniczonej pamięci). */
    var MAX_LOADED_ROWS = 1000;

    function toIsoDate(oDate) {
        if (!oDate) {
            return null;
        }
        return oDate.getFullYear() + "-" + String(oDate.getMonth() + 1).padStart(2, "0") + "-"
            + String(oDate.getDate()).padStart(2, "0");
    }

    /** Czas życia tymczasowego URL blob dla pobieranego XML (przeglądarka zaczyna zapis synchronicznie po kliknięciu). */
    var BLOB_URL_TTL_MS = 10000;

    /**
     * KSeF Inbox (UI5-10): faktury zakupowe ODEBRANE z KSeF (staging CAP), osobna domena od zakładki
     * PURCHASE kokpitu (faktury z SAP RAP). Widok jest wyłącznie do odczytu: akcje mutujące (duplikaty,
     * powiązanie FI/MM, księgowanie) są wyłączone — backend ich nie obsługuje. BETA-01: pobranie oryginalnego XML
     * (odczyt) tylko przy capability `viewXml` i `xmlAvailable === true`.
     * Dane pochodzą z InboundInboxAdapter; kontroler nie wykonuje HTTP i nie zna adresów usług.
     */
    return Controller.extend("ainvoicer.controller.InboundInbox", {

        formatter: InboundFormatter,

        /** Tekst zamiast przycisku pobrania XML: powód niedostępności (wersja bez funkcji / XML niezapisany). */
        xmlDownloadInfo: function (bEnabled, bXmlAvailable) {
            var oBundle = this.getOwnerComponent().getModel("i18n").getResourceBundle();
            return oBundle.getText(bEnabled === true && bXmlAvailable !== true ? "inboxXmlDownloadNotStored" : "inboxXmlDownloadNotInVersion");
        },

        /** Formatter tekstów i18n z parametrami: parts: ['i18n>key', 'inbox>/value', ...]; brak wartości = myślnik. */
        formatMessage: function (sPattern) {
            var aArgs = Array.prototype.slice.call(arguments, 1).map(function (vValue) {
                return vValue === null || vValue === undefined || vValue === "" ? "—" : vValue;
            });
            return sPattern ? formatMessage(sPattern, aArgs) : "";
        },

        onInit: function () {
            this.getView().setModel(new JSONModel({ invoice: {}, busy: false, error: "", downloading: false }), "inboxDetails");
            var oConfig = this.getOwnerComponent().getManifestEntry("/sap.ui5/config/ksefInbox");
            this._setupAdapter(oConfig, null);
            this.getOwnerComponent().getRouter().getRoute("RouteInboundInbox")
                .attachPatternMatched(this._onRouteMatched, this);
        },

        /**
         * (Re)inicjalizacja adaptera i stanu widoku. Brak konfiguracji = tryb wyłączony (fail-closed).
         * Wydzielone, by testy mogły sprawdzić wariant CF bez backendu bez przebudowy aplikacji.
         */
        _setupAdapter: function (oConfig, fnFetch) {
            this._oAdapter = new InboundInboxAdapter(oConfig || {}, fnFetch || undefined);
            this._iRequestSeq = 0;
            var sMode = this._oAdapter.getMode();
            var oModel = new JSONModel({
                mode: sMode,
                available: this._oAdapter.isAvailable(),
                demo: sMode === InboundContract.Mode.MOCK,
                busy: false,
                loaded: false,
                error: "",
                errorCode: "",
                loginRequired: false,
                rows: [],
                count: 0,
                hasMore: false,
                rejected: 0,
                pageSize: this._oAdapter.getPageSize(),
                filters: InboundQuery.createEmptyFilters(),
                sort: Object.assign({}, InboundContract.DefaultSort),
                valueLists: this._valueLists(),
                actions: this._actionStates(),
                empty: this._emptyState("initial")
            });
            oModel.setSizeLimit(MAX_LOADED_ROWS);
            this.getView().setModel(oModel, "inbox");
            this._resetDateControls();
        },

        _model: function () {
            return this.getView().getModel("inbox");
        },

        _text: function (sKey, aArgs) {
            return this.getOwnerComponent().getModel("i18n").getResourceBundle().getText(sKey, aArgs);
        },

        _onRouteMatched: function () {
            // Pierwsze wejście ładuje listę; powrót zachowuje filtry/stronę (odświeżenie na żądanie).
            if (this._oAdapter.isAvailable() && !this._model().getProperty("/loaded")) {
                this._load(false);
            }
        },

        // ------------------------------------------------------------------ listy wartości i akcje

        _valueLists: function () {
            var that = this;
            function list(sField) {
                return Object.keys(InboundContract.statusDictionary(sField)).map(function (sKey) {
                    return { key: sKey, text: that._text("inboxStatus_" + sField + "_" + sKey) };
                });
            }
            return {
                ingestion: list("ingestionStatus"),
                parse: list("parseStatus"),
                integrity: list("integrityStatus"),
                sap: list("sapPostingStatus"),
                environments: [{ key: "", text: this._text("inboxFilterEnvironmentAll") }].concat(
                    this._oAdapter.getEnvironments().map(function (sEnv) { return { key: sEnv, text: sEnv }; }))
            };
        },

        /** Stan akcji: wyłączone z jawnym powodem (tooltip + tekst); `viewXml` wg capability adaptera (BETA-01). */
        _actionStates: function () {
            var oCaps = this._oAdapter.getCapabilities();
            var sReason = this._text("inboxActionUnavailableReason");
            var sXmlReason = this._text("inboxXmlDownloadNotInVersion");
            return InboundContract.FutureActions.reduce(function (oResult, sAction) {
                oResult[sAction] = { enabled: oCaps[sAction] === true, reason: sAction === "viewXml" ? sXmlReason : sReason };
                return oResult;
            }, {});
        },

        _emptyState: function (sKind) {
            var mStates = {
                initial: ["sapIllus-NoData", "inboxEmptyInitialTitle", "inboxEmptyInitialText"],
                noData: ["sapIllus-NoEntries", "inboxEmptyNoDataTitle", "inboxEmptyNoDataText"],
                noMatch: ["sapIllus-NoFilterResults", "inboxEmptyNoMatchTitle", "inboxEmptyNoMatchText"],
                error: ["sapIllus-UnableToLoad", "inboxEmptyErrorTitle", "inboxEmptyErrorText"]
            };
            var aState = mStates[sKind] || mStates.initial;
            return { kind: sKind, illustration: aState[0], title: this._text(aState[1]), description: this._text(aState[2]) };
        },

        _hasActiveFilters: function () {
            var oFilters = this._model().getProperty("/filters");
            return Object.keys(oFilters).some(function (sKey) {
                var vValue = oFilters[sKey];
                return Array.isArray(vValue) ? vValue.length > 0 : !!vValue;
            });
        },

        // ------------------------------------------------------------------ ładowanie

        /**
         * Ładuje pierwszą stronę (bAppend=false) albo kolejną i dokleja (progressive loading).
         * Odpowiedź starszego żądania jest ignorowana (szybkie zmiany filtrów nie mieszają wyników).
         * Błąd czyści listę — nie pokazujemy nieaktualnych ani zastępczych (mock) danych.
         */
        _load: function (bAppend, bReload) {
            var oModel = this._model();
            if (!this._oAdapter.isAvailable()) {
                return Promise.resolve([]);
            }
            var aCurrent = bAppend ? oModel.getProperty("/rows") : [];
            var iSeq = ++this._iRequestSeq;
            oModel.setProperty("/busy", true);
            oModel.setProperty("/error", "");
            oModel.setProperty("/errorCode", "");
            oModel.setProperty("/loginRequired", false);

            return this._oAdapter.loadPage({
                filters: oModel.getProperty("/filters"),
                sort: oModel.getProperty("/sort"),
                skip: aCurrent.length,
                top: oModel.getProperty("/pageSize"),
                reload: !!bReload
            }).then(function (oPage) {
                if (iSeq !== this._iRequestSeq) {
                    return null;
                }
                var mSeen = {};
                aCurrent.forEach(function (oRow) { mSeen[oRow.id] = true; });
                var aRows = aCurrent.concat(oPage.rows.filter(function (oRow) {
                    return !mSeen[oRow.id];
                })).slice(0, MAX_LOADED_ROWS);
                oModel.setProperty("/rows", aRows);
                oModel.setProperty("/count", oPage.count);
                oModel.setProperty("/hasMore", oPage.hasMore && aRows.length < MAX_LOADED_ROWS);
                oModel.setProperty("/rejected", oPage.rejected || 0);
                oModel.setProperty("/loaded", true);
                oModel.setProperty("/empty", this._emptyState(this._hasActiveFilters() ? "noMatch" : "noData"));
                return aRows;
            }.bind(this)).catch(function (oError) {
                if (iSeq !== this._iRequestSeq) {
                    return null;
                }
                oModel.setProperty("/rows", []);
                oModel.setProperty("/count", 0);
                oModel.setProperty("/hasMore", false);
                oModel.setProperty("/errorCode", (oError && oError.code) || "");
                oModel.setProperty("/loginRequired", this._isLoginRequired(oError));
                oModel.setProperty("/error", this._errorText(oError));
                oModel.setProperty("/empty", this._emptyState("error"));
                return [];
            }.bind(this)).finally(function () {
                if (iSeq === this._iRequestSeq) {
                    oModel.setProperty("/busy", false);
                }
            }.bind(this));
        },

        /** Bezpieczny tekst błędu dla użytkownika (bez URL, nagłówków, treści odpowiedzi poza message OData). */
        _errorText: function (oError) {
            var sCode = oError && oError.code;
            switch (sCode) {
                case ErrorCode.VALIDATION:
                    return this._text("inboxErrorValidation", [(oError.errors || []).map(function (oItem) {
                        return this._text(oItem.key, oItem.args);
                    }, this).join(" ")]);
                case ErrorCode.TIMEOUT:
                    return this._text("inboxErrorTimeout");
                case ErrorCode.NETWORK:
                    return this._text("inboxErrorNetwork");
                case ErrorCode.BACKEND_MISSING:
                    return this._text("inboxErrorBackendMissing", [oError.gap || ""]);
                case ErrorCode.DISABLED:
                    return this._text("inboxUnavailableText");
                case ErrorCode.PARSE:
                    return this._text("inboxErrorParse");
                case ErrorCode.NOT_FOUND:
                    return this._text("inboxErrorNotFound");
                case ErrorCode.LOGIN_REQUIRED:
                    return this._text("inboxErrorUnauthorized");
                case ErrorCode.HTTP:
                    return this._httpErrorText(oError);
                default:
                    return this._text("inboxErrorGeneric");
            }
        },

        /**
         * HTTP: 401 → ponowne logowanie, 403 → brak roli/uprawnień, 404 listy → brak usługi, 5xx → usługa niedostępna
         * (bez treści odpowiedzi serwera/AppRoutera). Inne kody (np. 400 INBOX_QUERY_UNSUPPORTED) z komunikatem OData.
         */
        _httpErrorText: function (oError) {
            var iStatus = oError.status;
            if (iStatus === 401) {
                return this._text("inboxErrorUnauthorized");
            }
            if (iStatus === 403) {
                return this._text("inboxErrorForbidden");
            }
            if (iStatus === 404) {
                return this._text("inboxErrorServiceNotFound");
            }
            if (iStatus >= 500 && iStatus <= 599) {
                return this._text("inboxErrorServer", [iStatus]);
            }
            return this._text("inboxErrorHttp", [iStatus || "?", oError.message || ""]);
        },

        _isLoginRequired: function (oError) {
            return !!oError && (oError.code === ErrorCode.LOGIN_REQUIRED || (oError.code === ErrorCode.HTTP && oError.status === 401));
        },

        /** Ponowne logowanie: przeładowanie strony przez AppRouter prowadzi do XSUAA (bez obsługi tokenów w UI). */
        onSignInAgain: function () {
            window.location.reload();
        },

        // ------------------------------------------------------------------ filtry, sortowanie, strony

        onSearch: function () {
            return this._load(false);
        },

        onClearFilters: function () {
            this._model().setProperty("/filters", InboundQuery.createEmptyFilters());
            this._resetDateControls();
            return this._load(false);
        },

        _resetDateControls: function () {
            ["inboxIssueDateFilter", "inboxAcquisitionDateFilter"].forEach(function (sId) {
                var oControl = this.byId(sId);
                if (oControl) {
                    oControl.setValue("");
                    oControl.setValueState("None");
                }
            }, this);
        },

        onIssueDateChange: function (oEvent) {
            this._applyDateRange(oEvent, "issueDate");
        },

        onAcquisitionDateChange: function (oEvent) {
            this._applyDateRange(oEvent, "acquisitionDate");
        },

        /** Niepoprawny tekst zakresu dat oznacza pole błędem i zeruje filtr (nie filtrujemy po zgadywanej dacie). */
        _applyDateRange: function (oEvent, sPrefix) {
            var bValid = oEvent.getParameter("valid") !== false;
            var oSource = oEvent.getSource();
            oSource.setValueState(bValid ? "None" : "Error");
            oSource.setValueStateText(bValid ? "" : this._text("inboxFilterErrorDate"));
            var oModel = this._model();
            oModel.setProperty("/filters/" + sPrefix + "From", bValid ? toIsoDate(oEvent.getParameter("from")) : null);
            oModel.setProperty("/filters/" + sPrefix + "To", bValid ? toIsoDate(oEvent.getParameter("to")) : null);
        },

        onLoadMore: function () {
            if (!this._model().getProperty("/hasMore") || this._model().getProperty("/busy")) {
                return Promise.resolve(null);
            }
            return this._load(true);
        },

        onRefresh: function () {
            return this._load(false, true).then(function (aRows) {
                if (aRows && !this._model().getProperty("/error")) {
                    MessageToast.show(this._text("inboxRefreshed"));
                }
                return aRows;
            }.bind(this));
        },

        onOpenSort: function () {
            return this._loadFragment("_pSortDialog", "ainvoicer.view.InboundInboxSort").then(function (oDialog) {
                var oSort = this._model().getProperty("/sort");
                oDialog.setSelectedSortItem(this.createId("inboxSortItem-" + oSort.field));
                oDialog.setSortDescending(oSort.descending);
                oDialog.open();
                return oDialog;
            }.bind(this));
        },

        onSortConfirm: function (oEvent) {
            var oItem = oEvent.getParameter("sortItem");
            this._applySort(oItem ? oItem.getKey() : InboundContract.DefaultSort.field, !!oEvent.getParameter("sortDescending"));
        },

        _applySort: function (sField, bDescending) {
            this._model().setProperty("/sort", InboundQuery.normalizeSort({ field: sField, descending: bDescending }));
            return this._load(false);
        },

        onErrorClose: function () {
            this._model().setProperty("/error", "");
            this._model().setProperty("/loginRequired", false);
        },

        // ------------------------------------------------------------------ szczegóły

        onItemPress: function (oEvent) {
            var oItem = oEvent.getParameter("listItem") || oEvent.getSource();
            var oContext = oItem && oItem.getBindingContext("inbox");
            return oContext ? this._openDetails(oContext.getObject()) : Promise.resolve();
        },

        /** Pokazuje wiersz natychmiast, potem odczytuje aktualny rekord z adaptera (błąd nie zamyka dialogu). */
        _openDetails: function (oRow) {
            var oDetails = this.getView().getModel("inboxDetails");
            oDetails.setData({ invoice: oRow, busy: true, error: "", downloading: false });
            var pRefresh = this._oAdapter.loadInvoice(oRow.id).then(function (oFresh) {
                if (oDetails.getProperty("/invoice/id") === oFresh.id) {
                    oDetails.setProperty("/invoice", oFresh);
                }
            }).catch(function (oError) {
                oDetails.setProperty("/error", this._text("inboxDetailsRefreshError", [this._errorText(oError)]));
            }.bind(this)).finally(function () {
                oDetails.setProperty("/busy", false);
            });
            return this._loadFragment("_pDetailsDialog", "ainvoicer.view.InboundInvoiceDetails").then(function (oDialog) {
                oDialog.open();
                return pRefresh;
            });
        },

        onDetailsClose: function () {
            this.byId("inboxDetailsDialog").close();
        },

        onDetailsAfterClose: function () {
            this.getView().getModel("inboxDetails").setData({ invoice: {}, busy: false, error: "", downloading: false });
        },

        /**
         * BETA-01: pobranie oryginalnego XML bieżącej faktury. Ponowna kontrola capability i `xmlAvailable` (obrona
         * przed skrótami/zmianą stanu); URL buduje adapter wyłącznie z UUID. Błąd pokazany w dialogu, bez danych zastępczych.
         */
        onDownloadXml: function () {
            var oDetails = this.getView().getModel("inboxDetails");
            var oRow = oDetails.getProperty("/invoice");
            if (!this._oAdapter.canDownloadXml(oRow)) {
                MessageBox.information(this.xmlDownloadInfo(this._oAdapter.getCapabilities().viewXml, oRow && oRow.xmlAvailable));
                return Promise.resolve(false);
            }
            if (oDetails.getProperty("/downloading")) {
                return Promise.resolve(false);
            }
            oDetails.setProperty("/downloading", true);
            oDetails.setProperty("/error", "");
            return this._oAdapter.downloadXml(oRow.id).then(function (oBlob) {
                this._saveFile(oBlob, InboundInboxAdapter.xmlFileName(oRow));
                MessageToast.show(this._text("inboxXmlDownloaded"));
                return true;
            }.bind(this), function (oError) {
                var sReason = oError && oError.code === ErrorCode.NOT_FOUND ? this._text("inboxXmlErrorNotFound") : this._errorText(oError);
                oDetails.setProperty("/error", this._text("inboxXmlDownloadError", [sReason]));
                return false;
            }.bind(this)).finally(function () {
                oDetails.setProperty("/downloading", false);
            });
        },

        /** Zapis pliku przez tymczasowy URL blob (bez localStorage, bez renderowania XML w stronie). */
        _saveFile: function (oBlob, sFileName) {
            var sUrl = URL.createObjectURL(oBlob);
            var oLink = document.createElement("a");
            oLink.href = sUrl;
            oLink.download = sFileName;
            oLink.rel = "noopener";
            oLink.style.display = "none";
            document.body.appendChild(oLink);
            try {
                oLink.click();
            } finally {
                document.body.removeChild(oLink);
                setTimeout(function () {
                    URL.revokeObjectURL(sUrl);
                }, BLOB_URL_TTL_MS);
            }
        },

        /** Kopiowanie pełnego numeru KSeF. Bez API schowka pole tekstowe pozostaje do ręcznego zaznaczenia. */
        onCopyKsefNumber: function () {
            var sNumber = this.getView().getModel("inboxDetails").getProperty("/invoice/ksefNumber");
            if (!sNumber) {
                return Promise.resolve(false);
            }
            var oClipboard = window.navigator && window.navigator.clipboard;
            if (!oClipboard || typeof oClipboard.writeText !== "function") {
                this._selectKsefNumberField();
                MessageToast.show(this._text("inboxCopyManual"));
                return Promise.resolve(false);
            }
            return oClipboard.writeText(sNumber).then(function () {
                MessageToast.show(this._text("inboxCopied"));
                return true;
            }.bind(this), function () {
                this._selectKsefNumberField();
                MessageToast.show(this._text("inboxCopyManual"));
                return false;
            }.bind(this));
        },

        _selectKsefNumberField: function () {
            var oInput = this.byId("inboxDetailsKsefNumber");
            if (oInput && oInput.getFocusDomRef()) {
                oInput.focus();
                oInput.selectText(0, oInput.getValue().length);
            }
        },

        /**
         * Obrona przed wywołaniem akcji przyszłej (przyciski są wyłączone; to zabezpieczenie dla skrótów
         * i zmian stanu). Nie wykonuje żadnego żądania.
         */
        onFutureAction: function (oEvent) {
            var sAction = oEvent.getSource().data("action");
            var oState = this._model().getProperty("/actions/" + sAction);
            if (!oState || !oState.enabled) {
                MessageBox.information(this._text("inboxActionUnavailableReason"));
            }
        },

        // ------------------------------------------------------------------ nawigacja

        onNavBack: function () {
            var sPrevious = History.getInstance().getPreviousHash();
            if (sPrevious !== undefined) {
                window.history.go(-1);
            } else {
                this.getOwnerComponent().getRouter().navTo("RouteMain", {}, true);
            }
        },

        _loadFragment: function (sMember, sName) {
            if (!this[sMember]) {
                this[sMember] = Fragment.load({
                    id: this.getView().getId(),
                    name: sName,
                    controller: this
                }).then(function (oControl) {
                    this.getView().addDependent(oControl);
                    return oControl;
                }.bind(this));
            }
            return this[sMember];
        }
    });
});
