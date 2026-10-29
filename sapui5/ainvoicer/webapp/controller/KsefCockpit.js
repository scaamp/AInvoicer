sap.ui.define([
    "sap/ui/model/json/JSONModel",
    "sap/ui/model/Filter",
    "sap/ui/model/FilterOperator",
    "sap/ui/model/Sorter",
    "sap/ui/core/Fragment",
    "sap/ui/core/BusyIndicator",
    "sap/m/MessageBox",
    "sap/m/MessageToast",
    "sap/m/library",
    "sap/base/strings/formatMessage",
    "ainvoicer/model/formatter",
    "ainvoicer/model/ksef/KsefContract",
    "ainvoicer/model/ksef/KsefDataAdapter",
    "ainvoicer/model/ksef/KsefActionRules",
    "ainvoicer/model/ksef/KsefFilterBuilder",
    "ainvoicer/model/ksef/KsefAiFilterAdapter"
], function (JSONModel, Filter, FilterOperator, Sorter, Fragment, BusyIndicator, MessageBox, MessageToast, mobileLibrary,
    formatMessage, formatter, KsefContract, KsefDataAdapter, KsefActionRules, KsefFilterBuilder, KsefAiFilterAdapter) {
    "use strict";

    var COLUMNS_STORAGE_KEY = "ainvoicer.ksef.columns.v1";
    var ACTIONS = ["refresh", "sync", "send", "checkStatus", "viewXml", "downloadUpo", "viewDetails", "history", "checkDuplicate"];
    var ACTION_LABEL_KEYS = {
        refresh: "ksefActionRefresh",
        sync: "ksefActionSync",
        send: "ksefActionSend",
        checkStatus: "ksefActionCheckStatus",
        viewXml: "ksefActionViewXml",
        downloadUpo: "ksefActionDownloadUpo",
        viewDetails: "ksefActionViewDetails",
        history: "ksefActionHistory",
        checkDuplicate: "ksefActionCheckDuplicate"
    };

    function readStoredColumns() {
        try {
            var sValue = window.localStorage.getItem(COLUMNS_STORAGE_KEY);
            return sValue ? JSON.parse(sValue) : null;
        } catch (oError) {
            return null;
        }
    }

    function storeColumns(mColumns) {
        try {
            window.localStorage.setItem(COLUMNS_STORAGE_KEY, JSON.stringify(mColumns));
        } catch (oError) {
            // Personalizacja jest wygodą — brak localStorage nie blokuje kokpitu.
        }
    }

    function toIsoDate(oDate) {
        if (!oDate) {
            return null;
        }
        var sMonth = String(oDate.getMonth() + 1).padStart(2, "0");
        var sDay = String(oDate.getDate()).padStart(2, "0");
        return oDate.getFullYear() + "-" + sMonth + "-" + sDay;
    }

    /**
     * Mixin kontrolera Main: kokpit faktur KSeF.
     * Logika domenowa jest w ainvoicer/model/ksef/* (czyste moduły); tu tylko stan widoku i interakcje.
     * Wszystkie akcje zmieniające stan idą przez CAP Java (KsefDataAdapter) — nigdy bezpośrednio do KSeF.
     */
    return {

        formatter: formatter,

        /** Formatter tekstów i18n z parametrami: parts: ['i18n>key', 'model>/value']. */
        formatMessage: function (sPattern) {
            return sPattern ? formatMessage(sPattern, Array.prototype.slice.call(arguments, 1)) : "";
        },

        // ------------------------------------------------------------------ inicjalizacja

        _initKsefCockpit: function () {
            var oComponent = this.getOwnerComponent();
            var oConfig = (oComponent && oComponent.getManifestEntry("/sap.ui5/config/ksefCockpit")) || {};
            var sDefaultView = KsefContract.ViewKey[oConfig.defaultView] || KsefContract.ViewKey.ALL;

            this._oKsefConfig = oConfig;
            // Tryb rap: named OData V4 model "rap" (manifest) = usługa RAP UI; CAP tylko stan integracji i akcje.
            var oRapModel = oConfig.dataMode === KsefDataAdapter.Mode.RAP && oComponent ? oComponent.getModel("rap") : null;
            this._oKsefAdapter = new KsefDataAdapter(oConfig, null, oRapModel);
            this._oKsefAi = null;
            this._oKsefSelected = null;

            var oKsefModel = new JSONModel({ invoices: [] });
            oKsefModel.setSizeLimit(5000);
            this.getView().setModel(oKsefModel, "ksef");

            var mColumns = Object.assign(KsefContract.getDefaultColumnVisibility(), readStoredColumns() || {});
            this.getView().setModel(new JSONModel({
                viewKey: sDefaultView,
                legacyMode: sDefaultView === KsefContract.ViewKey.LEGACY,
                mode: this._oKsefAdapter.getMode(),
                modeText: this._ksefModeText(),
                busy: false,
                error: "",
                integrationError: "",
                hasMore: false,
                hasPrevious: false,
                pageSkip: 0,
                rowCount: 0,
                counts: { ALL: 0, PURCHASE: 0, SALES: 0 },
                columns: mColumns,
                columnList: [],
                filters: KsefFilterBuilder.createEmptyState(),
                valueLists: { originProcesses: Object.keys(KsefContract.OriginProcess), companyCodes: [], ksefStatuses: [] },
                actions: {},
                actionList: []
            }), "ksefView");

            this.getView().setModel(new JSONModel({ invoice: {}, operations: [] }), "ksefDetails");

            this._ksefUpdateActions();
            return this._ksefLoad();
        },

        _ksefText: function (sKey, aArgs) {
            var oComponent = this.getOwnerComponent();
            var oModel = oComponent && oComponent.getModel("i18n");
            return oModel ? oModel.getResourceBundle().getText(sKey, aArgs) : sKey;
        },

        _ksefModeText: function () {
            if (this._oKsefAdapter.getMode() === KsefDataAdapter.Mode.RAP) {
                return this._ksefText("ksefModeRap", [(this._oKsefConfig.rap || {}).dataSource || "rap",
                    (this._oKsefConfig.cap || {}).serviceUrl || ""]);
            }
            return this._oKsefAdapter.getMode() === KsefDataAdapter.Mode.CAP
                ? this._ksefText("ksefModeCap", [(this._oKsefConfig.cap || {}).serviceUrl || ""])
                : this._ksefText("ksefModeMock");
        },

        _ksefViewModel: function () {
            return this.getView().getModel("ksefView");
        },

        _ksefTable: function () {
            return this.byId("ksefInvoiceTable");
        },

        /** @returns {boolean} czy aktywny jest nowy widok faktur (a nie SAP FI Legacy) */
        _isKsefMode: function () {
            return !this._ksefViewModel().getProperty("/legacyMode");
        },

        // ------------------------------------------------------------------ dane

        _isRapMode: function () {
            return this._oKsefAdapter.getMode() === KsefDataAdapter.Mode.RAP;
        },

        /** Zapytanie serwerowe dla RAP: filtry widoku/paska/AI (bez pól UI), sortowanie, strona. */
        _ksefServerQuery: function (iSkip) {
            if (!this._isRapMode()) {
                return undefined;
            }
            var oViewModel = this._ksefViewModel();
            var aFilters = KsefFilterBuilder.build(oViewModel.getProperty("/filters"), oViewModel.getProperty("/viewKey"));
            if (this._oKsefAi) {
                aFilters = aFilters.concat(this._oKsefAi.filters);
            }
            return {
                filters: KsefFilterBuilder.serverFilters(aFilters),
                sorters: KsefFilterBuilder.serverSorters(this._aKsefServerSorters || (this._oKsefAi && this._oKsefAi.sorter
                    ? [].concat(this._oKsefAi.sorter) : [new Sorter("IssueDate", true), new Sorter("InvoiceNumber", false)]))
                    .concat(new Sorter("InvoiceUUID", false)),
                skip: iSkip || 0,
                top: (this._oKsefConfig.rap || {}).pageSize || 50
            };
        },

        _ksefLoad: function (iSkip) {
            var oViewModel = this._ksefViewModel();
            var oKsefModel = this.getView().getModel("ksef");
            var sSelectedUUID = this._oKsefSelected && this._oKsefSelected.InvoiceUUID;
            iSkip = typeof iSkip === "number" ? iSkip : 0;
            oViewModel.setProperty("/busy", true);
            oViewModel.setProperty("/error", "");

            return this._oKsefAdapter.loadInvoices(this._ksefServerQuery(iSkip)).then(function (aPage) {
                var aRows = aPage;
                var oPage = this._oKsefAdapter.getLastPage();
                oViewModel.setProperty("/hasMore", !!(oPage && oPage.hasMore));
                oViewModel.setProperty("/hasPrevious", !!(oPage && oPage.skip > 0));
                oViewModel.setProperty("/pageSkip", oPage ? oPage.skip : 0);
                oViewModel.setProperty("/integrationError", oPage && oPage.integrationError
                    ? this._ksefText("ksefIntegrationUnavailable", [oPage.integrationError]) : "");
                oKsefModel.setProperty("/invoices", aRows);
                oViewModel.setProperty("/counts", KsefFilterBuilder.countByView(aRows));
                oViewModel.setProperty("/valueLists/companyCodes", KsefFilterBuilder.distinctValues(aRows, "CompanyCode"));
                oViewModel.setProperty("/valueLists/ksefStatuses", KsefFilterBuilder.distinctValues(aRows, "KsefStatus").map(function (sStatus) {
                    return { key: sStatus, text: formatter.ksefStatusText.call(this, sStatus) };
                }.bind(this)));
                this._ksefApplyFilters();
                this._ksefRestoreSelection(sSelectedUUID);
                return aRows;
            }.bind(this)).catch(function (oError) {
                this.getView().getModel("ksef").setProperty("/invoices", []);
                oViewModel.setProperty("/hasMore", false);
                oViewModel.setProperty("/counts", { ALL: 0, PURCHASE: 0, SALES: 0 });
                oViewModel.setProperty("/rowCount", 0);
                oViewModel.setProperty("/error", this._ksefText("ksefLoadError", [this._ksefErrorText(oError)]));
                this._ksefSetSelected(null);
                return [];
            }.bind(this)).finally(function () {
                oViewModel.setProperty("/busy", false);
            });
        },

        /** Tryb rap: tylko bieżąca strona w pamięci; stabilny porządek zawiera InvoiceUUID. */
        onKsefLoadMore: function () {
            var oPage = this._oKsefAdapter.getLastPage();
            return this._ksefLoad(oPage ? oPage.skip + oPage.top : 0);
        },

        onKsefPreviousPage: function () {
            var oPage = this._oKsefAdapter.getLastPage();
            return this._ksefLoad(oPage ? Math.max(0, oPage.skip - oPage.top) : 0);
        },

        /** Sortowanie kolumn danych RAP wysyłamy do serwera, zamiast sortować tylko pobraną stronę. */
        onKsefSort: function (oEvent) {
            if (!this._isRapMode()) { return; }
            var sPath = oEvent.getParameter("column").getSortProperty();
            if (!KsefFilterBuilder.serverSorters([new Sorter(sPath)]).length) { return; }
            oEvent.preventDefault();
            this._aKsefServerSorters = [new Sorter(sPath, oEvent.getParameter("sortOrder") === "Descending")];
            return this._ksefLoad();
        },

        _ksefErrorText: function (oError) {
            if (oError && oError.code === KsefDataAdapter.ErrorCode.BACKEND_MISSING) {
                return this._ksefText("ksefBackendMissing", [oError.gap]);
            }
            return (oError && oError.message) || String(oError);
        },

        // ------------------------------------------------------------------ widoki i filtry

        onKsefViewSelect: function (oEvent) {
            var sKey = oEvent.getParameter("key") || oEvent.getParameter("selectedKey");
            this._ksefSetView(sKey);
        },

        _ksefSetView: function (sKey) {
            var oViewModel = this._ksefViewModel();
            oViewModel.setProperty("/viewKey", sKey);
            oViewModel.setProperty("/legacyMode", sKey === KsefContract.ViewKey.LEGACY);
            if (sKey !== KsefContract.ViewKey.LEGACY) {
                if (this._isRapMode()) {
                    this._ksefLoad();
                } else {
                    this._ksefApplyFilters();
                }
            }
            this._ksefUpdateActions();
        },

        onKsefSearch: function () {
            if (this._isRapMode()) {
                // Filtry i sortowanie wykonuje RAP; klient filtruje jeszcze tylko pola UI (KsefStatus).
                return this._ksefLoad();
            }
            this._ksefApplyFilters();
        },

        onKsefClear: function () {
            this._ksefViewModel().setProperty("/filters", KsefFilterBuilder.createEmptyState());
            var oDateRange = this.byId("ksefIssueDateFilter");
            if (oDateRange) {
                oDateRange.setValue("");
            }
            this._oKsefAi = null;
            if (this._isRapMode()) {
                return this._ksefLoad();
            }
            this._ksefApplyFilters();
        },

        onKsefDateRangeChange: function (oEvent) {
            var oViewModel = this._ksefViewModel();
            var bValid = oEvent.getParameter("valid") !== false;
            oViewModel.setProperty("/filters/issueDateFrom", bValid ? toIsoDate(oEvent.getParameter("from")) : null);
            oViewModel.setProperty("/filters/issueDateTo", bValid ? toIsoDate(oEvent.getParameter("to")) : null);
        },

        /** Łączy filtry widoku, paska filtrów i (opcjonalnie) AI; dane filtrowane po stronie klienta. */
        _ksefApplyFilters: function () {
            var oTable = this._ksefTable();
            var oBinding = oTable && oTable.getBinding("rows");
            if (!oBinding) {
                return;
            }
            var oViewModel = this._ksefViewModel();
            var aFilters = KsefFilterBuilder.build(oViewModel.getProperty("/filters"), oViewModel.getProperty("/viewKey"));
            if (this._oKsefAi) {
                aFilters = aFilters.concat(this._oKsefAi.filters);
            }
            oBinding.filter(aFilters.length ? new Filter({ filters: aFilters, and: true }) : [], "Application");
            if (this._oKsefAi && this._oKsefAi.sorter) {
                oBinding.sort(this._oKsefAi.sorter);
            }
            if (this._oKsefAi && this._oKsefAi.limit && oBinding.getLength() > this._oKsefAi.limit) {
                var aUUIDs = oBinding.getContexts(0, this._oKsefAi.limit).map(function (oContext) {
                    return oContext.getProperty("InvoiceUUID");
                });
                aFilters.push(new Filter({
                    filters: aUUIDs.map(function (sUUID) {
                        return new Filter("InvoiceUUID", FilterOperator.EQ, sUUID);
                    }),
                    and: false
                }));
                oBinding.filter(new Filter({ filters: aFilters, and: true }), "Application");
            }
            oViewModel.setProperty("/rowCount", oBinding.getLength());
            this._ksefSyncSelection();
        },

        // ------------------------------------------------------------------ selekcja

        onKsefRowSelectionChange: function () {
            this._ksefSyncSelection();
        },

        _ksefSyncSelection: function () {
            var oTable = this._ksefTable();
            var iIndex = oTable ? oTable.getSelectedIndex() : -1;
            var oContext = iIndex >= 0 ? oTable.getContextByIndex(iIndex) : null;
            this._ksefSetSelected(oContext ? oContext.getObject() : null);
        },

        _ksefRestoreSelection: function (sInvoiceUUID) {
            var oTable = this._ksefTable();
            if (!sInvoiceUUID || !oTable) {
                this._ksefSyncSelection();
                return;
            }
            var oBinding = oTable.getBinding("rows");
            var aContexts = oBinding ? oBinding.getContexts(0, oBinding.getLength()) : [];
            for (var i = 0; i < aContexts.length; i++) {
                if (aContexts[i].getProperty("InvoiceUUID") === sInvoiceUUID) {
                    oTable.setSelectedIndex(i);
                    break;
                }
            }
            this._ksefSyncSelection();
        },

        _ksefSetSelected: function (oInvoice) {
            this._oKsefSelected = oInvoice || null;
            this._ksefUpdateActions();
        },

        /** Przelicza dostępność akcji i przyczyny (tooltip + popover) z czystych reguł. */
        _ksefUpdateActions: function () {
            var oViewModel = this._ksefViewModel();
            var mRules = KsefActionRules.evaluateAll(
                this._oKsefSelected,
                this._oKsefAdapter.getCapabilities(),
                this._oKsefConfig.targetEnvironment,
                oViewModel.getProperty("/viewKey"));
            var mActions = {};
            var aActionList = [];

            ACTIONS.forEach(function (sAction) {
                var oRule = mRules[sAction];
                var sLabel = this._ksefText(ACTION_LABEL_KEYS[sAction]);
                var sReason = oRule.enabled ? this._ksefText("ksefActionAvailable") : this._ksefText(oRule.reasonKey, oRule.reasonArgs);
                mActions[sAction] = {
                    enabled: oRule.enabled,
                    tooltip: oRule.enabled ? sLabel : sLabel + ": " + sReason,
                    reason: sReason
                };
                aActionList.push({ key: sAction, label: sLabel, enabled: oRule.enabled, reason: sReason });
            }, this);

            oViewModel.setProperty("/actions", mActions);
            oViewModel.setProperty("/actionList", aActionList);
        },

        _ksefRule: function (sAction) {
            return KsefActionRules.evaluateAll(
                this._oKsefSelected,
                this._oKsefAdapter.getCapabilities(),
                this._oKsefConfig.targetEnvironment,
                this._ksefViewModel().getProperty("/viewKey"))[sAction];
        },

        /** Obrona przed wywołaniem wyłączonej akcji (np. skrótem lub przez zmianę stanu w tle). */
        _ksefGuard: function (sAction) {
            var oRule = this._ksefRule(sAction);
            if (!oRule.enabled) {
                MessageBox.information(this._ksefText(oRule.reasonKey, oRule.reasonArgs), {
                    title: this._ksefText(ACTION_LABEL_KEYS[sAction])
                });
            }
            return oRule.enabled;
        },

        onKsefShowAvailability: function (oEvent) {
            var oSource = oEvent.getSource();
            this._ksefLoadFragment("_oKsefAvailabilityPopover", "ainvoicer.view.KsefActionAvailability").then(function (oPopover) {
                oPopover.openBy(oSource);
            });
        },

        // ------------------------------------------------------------------ akcje

        onKsefRefresh: function () {
            return this._ksefLoad().then(function () {
                if (!this._ksefViewModel().getProperty("/error")) {
                    MessageToast.show(this._ksefText("ksefRefreshed"));
                }
            }.bind(this));
        },

        onKsefSend: function () {
            if (!this._ksefGuard("send")) {
                return;
            }
            var oInvoice = this._oKsefSelected;
            var oSend = oInvoice.SendOperation;
            var sText = this._ksefText("ksefConfirmSend", [oInvoice.InvoiceNumber, this._oKsefConfig.targetEnvironment]);
            if (oSend && oSend.status === "REJECTED") {
                sText += this._ksefText("ksefConfirmResendRejected");
            }
            MessageBox.confirm(sText, {
                title: this._ksefText("ksefConfirmSendTitle"),
                emphasizedAction: MessageBox.Action.CANCEL,
                onClose: function (sAction) {
                    if (sAction !== MessageBox.Action.OK) {
                        return;
                    }
                    this._ksefRunAction(this._oKsefAdapter.requestSend(oInvoice.InvoiceUUID), "ksefSendRequested");
                }.bind(this)
            });
        },

        onKsefSync: function () {
            if (!this._ksefGuard("sync")) {
                return;
            }
            MessageBox.confirm(this._ksefText("ksefActionSync") + "?", {
                onClose: function (sAction) {
                    if (sAction === MessageBox.Action.OK) {
                        this._ksefRunAction(this._oKsefAdapter.requestSync(), "ksefSyncRequested");
                    }
                }.bind(this)
            });
        },

        /** Pokazuje wyłącznie status zwrócony przez serwer i odświeża listę; nie wnioskuje sukcesu KSeF. */
        _ksefRunAction: function (oPromise, sResultKey) {
            BusyIndicator.show(0);
            return oPromise.then(function (oResult) {
                var sStatus = (oResult && oResult.status) || "?";
                MessageBox.information(this._ksefText(sResultKey, [sStatus]));
                return this._ksefLoad();
            }.bind(this)).catch(function (oError) {
                MessageBox.error(this._ksefText("ksefActionError", [this._ksefErrorText(oError)]));
            }.bind(this)).finally(function () {
                BusyIndicator.hide();
            });
        },

        /** Odczyt bieżącego statusu operacji z CAP (CAP odpytuje KSeF w tle; UI tylko czyta). */
        onKsefCheckStatus: function () {
            if (!this._ksefGuard("checkStatus")) {
                return Promise.resolve();
            }
            var oInvoice = this._oKsefSelected;
            var sPrevious = oInvoice.LatestOperation && oInvoice.LatestOperation.status;
            BusyIndicator.show(0);
            return this._oKsefAdapter.loadOperations(oInvoice.InvoiceUUID).then(function (aOperations) {
                var oLatest = aOperations[0];
                if (!oLatest) {
                    MessageToast.show(this._ksefText("ksefActionReasonNoOperation"));
                    return null;
                }
                MessageToast.show(oLatest.status === sPrevious
                    ? this._ksefText("ksefStatusUnchanged", [formatter.ksefStatusText.call(this, oLatest.status)])
                    : this._ksefText("ksefStatusChecked", [formatter.ksefStatusText.call(this, oLatest.status),
                        formatter.dateTime(oLatest.modifiedAt || oLatest.createdAt)]));
                return oLatest.status === sPrevious ? oLatest : this._ksefLoad();
            }.bind(this)).catch(function (oError) {
                MessageBox.error(this._ksefText("ksefActionError", [this._ksefErrorText(oError)]));
            }.bind(this)).finally(function () {
                BusyIndicator.hide();
            });
        },

        onKsefViewXml: function () {
            this._ksefOpenDocument("viewXml", "xml");
        },

        onKsefDownloadUpo: function () {
            this._ksefOpenDocument("downloadUpo", "upo");
        },

        _ksefOpenDocument: function (sAction, sKind) {
            if (!this._ksefGuard(sAction)) {
                return;
            }
            try {
                mobileLibrary.URLHelper.redirect(this._oKsefAdapter.getDocumentUrl(sKind, this._oKsefSelected.InvoiceUUID), true);
            } catch (oError) {
                MessageBox.error(this._ksefText("ksefActionError", [this._ksefErrorText(oError)]));
            }
        },

        onKsefCheckDuplicate: function () {
            if (!this._ksefGuard("checkDuplicate")) {
                return;
            }
            this._ksefRunAction(this._oKsefAdapter.requestDuplicateCheck(this._oKsefSelected.InvoiceUUID), "ksefSyncRequested");
        },

        onKsefScan: function () {
            // Istniejący skaner tworzy rekord SAP FI (ZC_FI_ACDOCA) — dialog pokazuje to jawnie.
            this.onAddInvoice();
        },

        onKsefErrorClose: function () {
            this._ksefViewModel().setProperty("/error", "");
        },

        // ------------------------------------------------------------------ szczegóły i historia

        onKsefViewDetails: function () {
            if (this._ksefGuard("viewDetails")) {
                this._ksefOpenDetails(this._oKsefSelected, "header");
            }
        },

        onKsefHistory: function () {
            if (this._ksefGuard("history")) {
                this._ksefOpenDetails(this._oKsefSelected, "history");
            }
        },

        onKsefRowAction: function (oEvent) {
            var oRow = oEvent.getParameter("row");
            var oContext = oRow && oRow.getBindingContext("ksef");
            if (oContext) {
                this._ksefOpenDetails(oContext.getObject(), "header");
            }
        },

        _ksefOpenDetails: function (oInvoice, sTab) {
            var oDetailsModel = this.getView().getModel("ksefDetails");
            oDetailsModel.setData({
                invoice: oInvoice,
                operations: [],
                selectedTab: sTab,
                busy: true,
                historyBusy: true,
                historyError: ""
            });

            var oDetailsPromise = this._oKsefAdapter.loadInvoiceDetails(oInvoice.InvoiceUUID).then(function (oDetails) {
                oDetailsModel.setProperty("/invoice", oDetails);
            }).catch(function (oError) {
                // Lista ma już nagłówek; brak endpointu szczegółów (G-10) nie blokuje dialogu.
                MessageToast.show(this._ksefErrorText(oError));
            }.bind(this)).finally(function () {
                oDetailsModel.setProperty("/busy", false);
            });

            var oHistoryPromise = this._oKsefAdapter.loadOperations(oInvoice.InvoiceUUID).then(function (aOperations) {
                oDetailsModel.setProperty("/operations", aOperations);
            }).catch(function (oError) {
                oDetailsModel.setProperty("/historyError", this._ksefText("ksefDetailsHistoryError", [this._ksefErrorText(oError)]));
            }.bind(this)).finally(function () {
                oDetailsModel.setProperty("/historyBusy", false);
            });

            return this._ksefLoadFragment("_oKsefDetailsDialog", "ainvoicer.view.KsefInvoiceDetails").then(function (oDialog) {
                oDialog.open();
                return Promise.all([oDetailsPromise, oHistoryPromise]);
            });
        },

        onKsefDetailsClose: function () {
            this.byId("ksefDetailsDialog").close();
        },

        onKsefDetailsAfterClose: function () {
            this.getView().getModel("ksefDetails").setData({ invoice: {}, operations: [] });
        },

        // ------------------------------------------------------------------ personalizacja kolumn

        onKsefOpenColumns: function () {
            var oViewModel = this._ksefViewModel();
            var mColumns = oViewModel.getProperty("/columns");
            oViewModel.setProperty("/columnList", KsefContract.Columns.map(function (oColumn) {
                return {
                    key: oColumn.key,
                    label: this._ksefText("ksefCol" + oColumn.key),
                    source: oColumn.source,
                    visible: !!mColumns[oColumn.key]
                };
            }, this));
            this._ksefLoadFragment("_oKsefColumnsDialog", "ainvoicer.view.KsefColumns").then(function (oDialog) {
                oDialog.open();
            });
        },

        onKsefColumnsConfirm: function () {
            var oViewModel = this._ksefViewModel();
            var mColumns = {};
            oViewModel.getProperty("/columnList").forEach(function (oColumn) {
                mColumns[oColumn.key] = !!oColumn.visible;
            });
            // Kolumna identyfikująca fakturę zostaje zawsze widoczna.
            mColumns.InvoiceNumber = true;
            oViewModel.setProperty("/columns", mColumns);
            storeColumns(mColumns);
            this.byId("ksefColumnsDialog").close();
        },

        onKsefColumnsReset: function () {
            var mColumns = KsefContract.getDefaultColumnVisibility();
            var oViewModel = this._ksefViewModel();
            oViewModel.setProperty("/columns", mColumns);
            oViewModel.setProperty("/columnList", oViewModel.getProperty("/columnList").map(function (oColumn) {
                return Object.assign({}, oColumn, { visible: !!mColumns[oColumn.key] });
            }));
            storeColumns(mColumns);
        },

        _ksefLoadFragment: function (sMember, sName) {
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
        },

        // ------------------------------------------------------------------ AI Assistant

        /**
         * Stosuje odpowiedź istniejącej usługi /filter do tabeli KSeF przez bezpieczny adapter.
         * @returns {string} opis dla historii rozmowy (co zastosowano, co pominięto)
         */
        _applyAiResultToKsef: function (oData) {
            var oTranslation = KsefAiFilterAdapter.translate(oData);
            this._oKsefAi = oTranslation;
            if (this._isRapMode()) {
                this._ksefLoad();
            } else {
                this._ksefApplyFilters();
            }

            var aLines = [];
            if (oData && oData.description) {
                aLines.push(oData.description);
            }
            if (oTranslation.applied.length) {
                aLines.push(this._ksefText("aiKsefApplied", [oTranslation.applied.map(function (oApplied) {
                    var sValue = oApplied.operator === "BT" ? oApplied.value1 + ".." + oApplied.value2 : oApplied.value1;
                    var sText = oApplied.path + " " + oApplied.operator + " " + sValue;
                    return oApplied.approximate ? sText + " " + this._ksefText("aiKsefApproximate", [oApplied.mappedFrom]) : sText;
                }, this).join("; ")]));
            } else if (!oTranslation.sorter && !oTranslation.limit) {
                aLines.push(this._ksefText("aiKsefNothingApplied"));
            }
            if (oTranslation.ignored.length) {
                aLines.push(this._ksefText("aiKsefIgnored", [oTranslation.ignored.map(function (oIgnored) {
                    return oIgnored.path + " (" + oIgnored.reason + ")";
                }).join(", ")]));
            }
            return aLines.join("\n");
        }
    };
});
