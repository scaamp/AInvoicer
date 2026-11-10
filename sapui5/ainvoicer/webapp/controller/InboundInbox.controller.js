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

    /**
     * KSeF Inbox (UI5-10): faktury zakupowe ODEBRANE z KSeF (staging CAP), osobna domena od zakładki
     * PURCHASE kokpitu (faktury z SAP RAP). Widok jest wyłącznie do odczytu: wszystkie akcje mutujące
     * (podgląd XML, duplikaty, powiązanie FI/MM, księgowanie) są wyłączone — backend ich nie obsługuje.
     * Dane pochodzą z InboundInboxAdapter; kontroler nie wykonuje HTTP i nie zna adresów usług.
     */
    return Controller.extend("ainvoicer.controller.InboundInbox", {

        formatter: InboundFormatter,

        /** Formatter tekstów i18n z parametrami: parts: ['i18n>key', 'inbox>/value', ...]; brak wartości = myślnik. */
        formatMessage: function (sPattern) {
            var aArgs = Array.prototype.slice.call(arguments, 1).map(function (vValue) {
                return vValue === null || vValue === undefined || vValue === "" ? "—" : vValue;
            });
            return sPattern ? formatMessage(sPattern, aArgs) : "";
        },

        onInit: function () {
            this.getView().setModel(new JSONModel({ invoice: {}, busy: false, error: "" }), "inboxDetails");
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
                integrity: list("integrityStatus"),
                sap: list("sapPostingStatus"),
                environments: [{ key: "", text: this._text("inboxFilterEnvironmentAll") }].concat(
                    this._oAdapter.getEnvironments().map(function (sEnv) { return { key: sEnv, text: sEnv }; }))
            };
        },

        /** Stan akcji przyszłych: zawsze wyłączone z jawnym powodem (tooltip + tekst w szczegółach). */
        _actionStates: function () {
            var oCaps = this._oAdapter.getCapabilities();
            var sReason = this._text("inboxActionUnavailableReason");
            return InboundContract.FutureActions.reduce(function (oResult, sAction) {
                oResult[sAction] = { enabled: oCaps[sAction] === true, reason: sReason };
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
                case ErrorCode.HTTP:
                    return this._text("inboxErrorHttp", [oError.status || "?", oError.message || ""]);
                default:
                    return this._text("inboxErrorGeneric");
            }
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
            oDetails.setData({ invoice: oRow, busy: true, error: "" });
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
            this.getView().getModel("inboxDetails").setData({ invoice: {}, busy: false, error: "" });
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
