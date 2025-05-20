sap.ui.define([
    "sap/ui/core/mvc/Controller",
    "sap/ui/model/json/JSONModel",
    "sap/m/MessageBox",
    "sap/m/MessageToast",
    "sap/ui/core/Fragment",
    "sap/ui/core/format/DateFormat",
    "sap/ui/core/BusyIndicator",
    "sap/m/CustomListItem",
    "sap/m/VBox",
    "sap/m/Text",
    "sap/m/Link",
    "sap/m/BusyDialog",
    'sap/ui/model/Filter',
    'sap/ui/model/FilterOperator',
], (Controller, JSONModel, MessageBox, MessageToast, Fragment, DateFormat, BusyIndicator, CustomListItem, VBox, Text, Link, BusyDialog, Filter, FilterOperator) => {
    "use strict";

    return Controller.extend("ainvoicer.controller.Main", {
        onInit: function () {
            // Inicjalizacja modelu
            var oFileModel = new JSONModel({
                fileSelected: false,
                status: "Gotowy do wyboru pliku",
                resultVisible: false,
                result: ""
            });
            this.getView().setModel(oFileModel, "file");

            // Model do przechowywania historii zapytań i odpowiedzi
            var oHistoryModel = new JSONModel({
                conversations: [],
                currentContext: "search",
                expanded: false
            });
            this.getView().setModel(oHistoryModel, "history");

            // Model do przechowywania stanu wyszukiwania
            var oSearchModel = new JSONModel({
                lastQuery: "",
                lastSql: "",
                isFiltered: false
            });
            this.getView().setModel(oSearchModel, "search");

            this.oTable = this.getView().byId("invoiceTable");
            this.oFilterBar = this.getView().byId("filterbar");

        },

        onSearch: function () {
            var aTableFilters = this.oFilterBar.getFilterGroupItems().reduce(function (aResult, oFilterGroupItem) {
                var oControl = oFilterGroupItem.getControl(),
                    aSelectedKeys = oControl.getSelectedKeys(),
                    aFilters = aSelectedKeys.map(function (sSelectedKey) {
                        return new Filter({
                            path: oFilterGroupItem.getName(),
                            operator: FilterOperator.Contains,
                            value1: sSelectedKey
                        });
                    });

                if (aSelectedKeys.length > 0) {
                    aResult.push(new Filter({
                        filters: aFilters,
                        and: false
                    }));
                }

                return aResult;
            }, []);

            this.oTable.getBinding("rows").filter(aTableFilters);
            this.oTable.setShowOverlay(false);
        },


        // Obsługa zmiany kontekstu
        onContextChange: function (oEvent) {
            var sSelectedKey = oEvent.getSource().getSelectedKey();
            var oHistoryModel = this.getView().getModel("history");

            oHistoryModel.setProperty("/currentContext", sSelectedKey);

            // Można dodać dodatkową logikę dla różnych kontekstów
            MessageToast.show("Context changed to: " + sSelectedKey);
        },

        // Wykonywanie poleceń AI
        onAiCommandExecute: function (oEvent) {
            var sQuery = oEvent.getParameter("query");
            if (!sQuery) {
                return;
            }

            var oHistoryModel = this.getView().getModel("history");
            var sCurrentContext = oHistoryModel.getProperty("/currentContext");

            // Zapisanie zapytania w historii
            var aConversations = oHistoryModel.getProperty("/conversations");
            var oNewConversation = {
                query: sQuery,
                timestamp: new Date(),
                context: sCurrentContext,
                response: null,
                sql: null,
                isProcessed: false
            };

            aConversations.unshift(oNewConversation);
            oHistoryModel.setProperty("/conversations", aConversations);

            // Pokazanie wskaźnika zajętości
            var oBusyDialog = new BusyDialog({
                title: "Processing",
                text: "Analyzing your request..."
            });
            this.oBusyDialog = oBusyDialog;
            this.oBusyDialog.open();

            this._processAiQuery(sQuery, sCurrentContext, 0);
            // Jeśli historia nie jest widoczna, pokazujemy ją
            this.byId("aiCommandHistory").setVisible(true);

            // Czyścimy pole wprowadzania po wykonaniu
            this.byId("aiCommandInput").setValue("");
        },

        // Symulacja przetwarzania zapytania przez LLM
        _processAiQuery: function (sQuery, sContext, iIndex) {
            var that = this;
            var oHistoryModel = this.getView().getModel("history");
            var aConversations = oHistoryModel.getProperty("/conversations");
            var oQuery = {
                queryText: sQuery
            };

            // // Przwrócenie domyślnego modelu za każym razem
            // this.oTable.setModel(this.getOwnerComponent().getModel());

            return new Promise(function (resolve, reject) {
                if (sContext === "search") {
                    fetch("https://python-app-grouchy-platypus-jj.cfapps.us10-001.hana.ondemand.com/filter", {
                        method: "POST",
                        headers: {
                            "Content-Type": "application/json"
                        },
                        body: JSON.stringify(oQuery)
                    })
                        .then(res => res.json())
                        .then(data => {

                            const oODataModel = that.getOwnerComponent().getModel(); // lub inny sposób uzyskania modelu głównego
                            that.oTable.setModel(oODataModel);
                            that.oTable.bindRows({ path: "/ZC_FI_ACDOCA" });

                            // Limit – przycinamy dane na modelu view, jeśli trzeba
                            if (data.limit) {
                                if (data.limit && Number.isInteger(data.limit)) {
                                    const oBinding = that.oTable.getBinding("rows");
                                    if (!oBinding) {
                                        MessageToast.show("Brak danych w tabeli.");
                                        return;
                                    }

                                    // Nasłuchuj na zakończenie ładowania danych
                                    oBinding.attachEventOnce("dataReceived", function () {
                                        // Odpinamy stare bindowanie przed ustawieniem nowego modelu
                                        // that.oTable.unbindRows();

                                        const aContexts = oBinding.getContexts(0, oBinding.getLength());
                                        const aFullData = aContexts.map(oCtx => oCtx.getObject());

                                        // Limit
                                        const iLimit = data.limit || 10;
                                        const aLimitedData = aFullData.slice(0, iLimit);

                                        // Tymczasowy model
                                        const oTempModel = new sap.ui.model.json.JSONModel({ ZC_FI_ACDOCA: aLimitedData });
                                        that.oTable.setModel(oTempModel);
                                        that.oTable.bindRows("/ZC_FI_ACDOCA");


                                    });
                                }

                            }

                            // Filtrowanie
                            if (data.filters) {
                                const aFilters = data.filters.map(f => {
                                    if (f.operator === "BT") {
                                        return new sap.ui.model.Filter(f.path, sap.ui.model.FilterOperator.BT, f.value1, f.value2);
                                    } else {
                                        return new sap.ui.model.Filter(f.path, sap.ui.model.FilterOperator[f.operator], f.value1);
                                    }
                                });

                                // Domyślnie przypisanie waluty jeśli user jej nie poda / model jej nie zwróci
                                if (data.filters.some(f => f.path === "AmountDocument") && !data.filters.some(f => f.path === "CurrencyCode")) {
                                    aFilters.push(new sap.ui.model.Filter("CurrencyCode", sap.ui.model.FilterOperator.EQ, "PLN"));
                                }

                                that.oTable.getBinding("rows").filter(aFilters);

                            }


                            // Sortowanie
                            if (data.sortBy) {
                                const oSorter = new sap.ui.model.Sorter(
                                    data.sortBy.path,
                                    data.sortBy.descending // true = malejąco
                                );
                                that.oTable.getBinding("rows").sort(oSorter);
                            }

                            // wszystko co zależy od odpowiedzi
                            aConversations[iIndex].response = data.description;
                            aConversations[iIndex].filters = data.filters;
                            aConversations[iIndex].oConversationData = data;
                            aConversations[iIndex].isProcessed = true;
                            oHistoryModel.setProperty("/conversations", aConversations);
                            that._updateHistoryList();

                            that.oTable.invalidate();
                            resolve(data); // na końcu
                            that.oBusyDialog.close();
                        })
                }
                else {
                    sResponse = "Konktekst jeszcze nie obsługiwany."
                }
            }).catch(function (error) {
                // reject(error);
            });
        },

        /**
        * Generates a user-friendly summary of applied filters, sorting, and row limit.
        * @param {object} data - The query data object containing filters, sortBy, and limit.
        * @returns {string} A formatted string summarizing the query for display purposes.
        */
        _formatQuerySummary(data) {
            var that = this;
            const summary = [];
            let count = 1;

            // Filters
            if (Array.isArray(data.filters)) {
                data.filters.forEach(filter => {
                    const field = that._getFieldLabel(filter.path);
                    const operator = that._getOperatorText(filter.operator);
                    const value = filter.operator === "BT" ? `${filter.value1} and ${filter.value2}` : filter.value1;
                    summary.push(`${count++}. Filter: ${field} ${operator} ${value}`);
                });
            }

            // Sort
            if (data.sortBy && data.sortBy.path) {
                const field = that._getFieldLabel(data.sortBy.path);
                const order = data.sortBy.descending ? "descending" : "ascending";
                summary.push(`${count++}. Sort: by ${field} in ${order} order`);
            }

            // Limit
            if (Number.isInteger(data.limit)) {
                summary.push(`${count++}. Limit: showing maximum ${data.limit} record${data.limit === 1 ? "" : "s"}`);
            }

            return summary.join("\n");
        },

        /**
        * Maps a technical filter path to a human-readable label.
        * Example: "DocumentNo" → "Document Number"
        * @param {string} path - Technical path name from the filter
        * @returns {string} - User-friendly field label
        */
        _getFieldLabel(path) {
            const labelMap = {
                DocumentNo: "Document Number",
                PostingDate: "Posting Date",
                CompanyCode: "Company Code",
                AmountDocument: "Amount",
                CurrencyCode: "Currency",
                Vendor: "Vendor",
                Customer: "Customer",
                Account: "G/L Account",
                FiscalYear: "Fiscal Year"
                // Add more if needed
            };

            return labelMap[path] || path;
        },

        /**
        * Converts a technical filter operator (e.g. "EQ", "BT") to a descriptive English phrase.
        * @param {string} operator - The OData filter operator.
        * @returns {string} A readable phrase describing the operation.
        */
        _getOperatorText(operator) {
            const operatorMap = {
                "EQ": "equals",
                "NE": "does not equal",
                "GT": "is greater than",
                "GE": "is greater than or equal to",
                "LT": "is less than",
                "LE": "is less than or equal to",
                "BT": "is between",
                "Contains": "contains",
                "StartsWith": "starts with",
                "EndsWith": "ends with"
            };
            return operatorMap[operator] || operator;
        },

        // Aktualizacja listy historii
        _updateHistoryList: function () {
            var oHistoryList = this.byId("commandHistoryList");
            var oChatDialogList = this.byId("chatDialogList");

            oHistoryList.removeAllItems();
            oChatDialogList.removeAllItems();

            var aConversations = this.getView().getModel("history").getProperty("/conversations");

            // Dodanie elementów do listy historii w panelu
            aConversations.forEach(function (oConv, index) {
                // Tworzenie elementu listy dla panelu
                var oListItem = new CustomListItem({
                    content: [
                        new VBox({
                            items: [
                                new Text({
                                    text: "You: " + oConv.query,
                                    wrapping: true
                                }).addStyleClass("sapUiTinyMarginBottom sapMTextMaxWidth"),

                                new Text({
                                    text: oConv.isProcessed ? "Asisstant: " + oConv.response : "Przetwarzanie...",
                                    wrapping: true
                                }).addStyleClass("sapUiTinyMarginBottom sapMTextMaxWidth"),

                                new Link({
                                    text: "View filters",
                                    press: this.onShowFiltersDialog.bind(this, index),
                                    visible: oConv.isProcessed // && oConv.sql
                                })
                            ]
                        }).addStyleClass("sapUiTinyMargin")
                    ]
                });

                oHistoryList.addItem(oListItem);

                // Tworzenie elementu listy dla dialogu
                var oChatItem = new CustomListItem({
                    content: [
                        new VBox({
                            items: [
                                new Text({
                                    text: "You: " + oConv.query,
                                    wrapping: true
                                }).addStyleClass("sapUiTinyMarginBottom sapMTextMaxWidth userQuery"),

                                new Text({
                                    text: oConv.isProcessed ? "Asisstant: " + oConv.response : "Przetwarzanie...",
                                    wrapping: true
                                }).addStyleClass("sapUiTinyMarginBottom sapMTextMaxWidth assistantResponse"),

                                new Link({
                                    text: "Zobacz SQL",
                                    press: this.onShowFiltersDialog.bind(this, index),
                                    visible: oConv.isProcessed //&& oConv.sql
                                })
                            ]
                        }).addStyleClass("sapUiTinyMargin")
                    ]
                });

                oChatDialogList.addItem(oChatItem);
            }.bind(this));
        },

        // Pokazanie/ukrycie historii zapytań
        onToggleHistory: function () {
            var oHistoryPanel = this.byId("aiCommandHistory");
            var bVisible = oHistoryPanel.getVisible();

            oHistoryPanel.setVisible(!bVisible);
        },

        // Otwarcie dialogu chat
        onOpenChatDialog: function () {
            var oDialog = this.byId("aiChatDialog");
            oDialog.open();
        },

        // Zamknięcie dialogu chat
        onCloseChatDialog: function () {
            var oDialog = this.byId("aiChatDialog");
            oDialog.close();
        },

        // Show filters dialog
        onShowFiltersDialog: function (iIndex) {
            var oHistoryModel = this.getView().getModel("history");
            var aConversations = oHistoryModel.getProperty("/conversations");
            var oConversationData = aConversations[iIndex].oConversationData;
            var sMessage = this._formatQuerySummary(oConversationData);

            MessageBox.information(sMessage, {
                title: "Generated condiions"
            });
        },

        onClear: function (oEvent) {
            // this.oView.byId("CompanyCodeFilter").removeAllSelectedItems();

            // Przejdź po wszystkich filtrach w FilterBarze
            this.oFilterBar.getFilterGroupItems().forEach(oGroupItem => {
                var oControl = oGroupItem.getControl();

                if (!oControl) return;

                if (oControl.removeAllSelectedItems) {
                    // np. MultiComboBox
                    oControl.removeAllSelectedItems();
                } else if (oControl.setValue) {
                    // np. Input, DatePicker
                    oControl.setValue("");
                } else if (oControl.setSelectedKey) {
                    // np. Select
                    oControl.setSelectedKey("");
                } else if (oControl.setSelected) {
                    // np. CheckBox
                    oControl.setSelected(false);
                }
            });
        },

        onVoiceInput: function () {
            // MessageToast.show("Funkcja wprowadzania głosowego nie jest jeszcze dostępna.");

            const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            if (!SpeechRecognition) {
                MessageToast.show("Your browser does not support speech recognition.");
                return;
            }

            const recognition = new SpeechRecognition();
            recognition.lang = "pl-PL"; // lub "en-US"
            recognition.interimResults = false;
            recognition.maxAlternatives = 1;

            recognition.start();

            recognition.onresult = (event) => {
                const transcript = event.results[0][0].transcript;
                sap.m.MessageToast.show("Rozpoznano: " + transcript);
                this.byId("aiCommandInput").setValue(transcript);
                // this.onAiCommandExecute(transcript); // np. automatyczne wykonanie
            };

            recognition.onerror = (event) => {
                MessageToast.show("Speech recognition error: " + event.error);
            };
        },


        onAddInvoice() {
            var oView = this.getView();

            // Create a local JSON model first (for the dialog)
            var oDialogModel = new JSONModel({
                CompanyCode: "",
                FiscalYear: "",
                DocumentNo: "",
                LineItem: "",
                // PostingDate: null,
                // DocumentDate: null,
                // EntryDate: null,
                DocumentType: "",
                Reference: "",
                DebitCredit: "D", // Default value
                Account: "",
                Vendor: "",
                Customer: "",
                CostCenter: "",
                ProfitCenter: "",
                Segment: "",
                CurrencyCode: "",
                AmountDocument: "0.00",
                AmountLocal: "0.00",
                PaymentTerms: "",
                PaymentMethod: "",
                // DueDate: null,
                ClearingDoc: "",
                // ClearingDate: null
            });

            // Otwarcie dialogu
            if (!this._oAddDialog) {
                Fragment.load({
                    id: oView.getId(),
                    name: "ainvoicer.view.AddInvoice",
                    controller: this
                }).then(function (oDialog) {
                    this._oAddDialog = oDialog;
                    oView.addDependent(this._oAddDialog);
                    this._oAddDialog.setModel(oDialogModel);
                    this._oAddDialog.open();
                }.bind(this));
            } else {
                this._oAddDialog.open();
            }
        },



        onCancelDialog: function () {
            this._oAddDialog.close();
        },

        onSaveDocument: function () {
            // Walidacja danych formularza przed zapisem
            if (!this._validateInvoiceForm()) {
                return;
            }

            // Pokaż wskaźnik zajętości
            BusyIndicator.show();

            // Wywołaj funkcję tworzącą encję - przekazujemy:
            // 1. Nazwę zbioru encji w OData (np. "InvoiceSet")
            // 2. ID dialogu, który zawiera dane
            // 3. Nazwę modelu JSON w dialogu (jeśli pusta, użyje domyślnego modelu)
            this.onCreateODataEntity("/ZC_FI_ACDOCA", "addInvoiceDialog", "")
                .then(function (oResult) {
                    // Sukces
                    BusyIndicator.hide();

                    // Pokaż informację o sukcesie
                    // MessageToast.show(oResult.message);

                    // Zamknij dialog
                    this.byId("addInvoiceDialog").close();

                    // Odśwież widok (jeśli potrzeba)
                    this._refreshInvoiceList();

                }.bind(this))
                .catch(function (oError) {
                    // Błąd
                    BusyIndicator.hide();

                    // Pokaż informację o błędzie
                    // MessageBox.error(oError.message);
                });
        },

        /**
         * Funkcja tworząca encję w OData v4 na podstawie danych z JSONModel dialogu
         * @param {string} sEntitySetName - nazwa encji w serwisie OData, np. "InvoiceSet"
         * @param {string} sDialogId - ID dialogu zawierającego JSONModel z danymi
         * @param {string} [sJSONModelName=""] - opcjonalna nazwa modelu JSON (jeśli nie jest to domyślny model dialogu)
         * @returns {Promise} - Promise zawierający rezultat operacji
         */
        onCreateODataEntity: async function (sEntitySetName, sDialogId, sJSONModelName) {
            // Pobierz dialog
            const oDialog = this.byId(sDialogId);
            if (!oDialog) {
                return Promise.reject("Dialog o ID " + sDialogId + " nie został znaleziony");
            }

            // Pobierz JSONModel z danymi formularza
            const oJSONModel = sJSONModelName ?
                oDialog.getModel(sJSONModelName) :
                oDialog.getModel();

            // Sprawdź czy model istnieje
            if (!oJSONModel) {
                return Promise.reject("Model JSON nie został znaleziony");
            }

            // Pobierz dane z modelu JSON
            const oFormData = oJSONModel.getData();

            // Pobierz główny model OData v4
            const oODataModel = this.getView().getModel();
            if (!oODataModel) {
                return Promise.reject("Model OData v4 nie został znaleziony");
            }

            // Przygotuj dane do wysłania - usuń niepotrzebne pola i sformatuj wartości
            const oCleanData = this._prepareDataForODataRequest(oFormData);

            console.log("Dane wysyłane do OData:", oCleanData);

            // try {
            try {

                // 1. Utwórz binding do listy encji
                const oListBinding = oODataModel.bindList(sEntitySetName, undefined, [], [], {
                    $$updateGroupId: "draftGroup"
                });

                // 2. Utwórz nową encję w trybie draft
                const oContext = oListBinding.create(oCleanData);

                // 3. Zapisz encję do backendu
                await oODataModel.submitBatch("draftGroup");

                // 4. Poczekaj aż encja przestanie być transient
                while (oContext.isTransient && oContext.isTransient()) {
                    await new Promise(resolve => setTimeout(resolve, 100));
                }

                // 5. Pobierz ETag
                await oContext.requestObject(); // załaduj dane z backendu
                const sEtag = oContext.getObject()["@odata.etag"];

                // 6. Utwórz binding do operacji `draftActivate`
                const oActivationBinding = oODataModel.bindContext(
                    "com.sap.gateway.srvd.zui_fi_acdoca_o4.v0001.Activate(...)",
                    oContext,
                    { $$groupId: "activationGroup" }
                );

                // 7. Wykonaj operację aktywacji z konkretnym groupId i parameterami
                try {
                    await oActivationBinding.execute("$auto", undefined, {
                        "If-Match": sEtag
                    });

                    return "Success";
                }
                catch (oError) {
                    console.error("Błąd podczas aktywacji:", oError);
                    throw oError;
                }
            } catch (error) {
                console.error("Błąd podczas tworzenia lub aktywacji encji:", error);
                throw error;
            }

        },

        /**
        * Przygotowuje dane z formularza do wysłania do OData
        * @private
        * @param {object} oData - dane z formularza JSONModel
        * @returns {object} - przygotowane dane do wysłania
        */
        _prepareDataForODataRequest: function (oData) {
            // Tworzymy kopię obiektu, żeby nie modyfikować oryginału
            const oCleanData = JSON.parse(JSON.stringify(oData));

            // Lista pól, które nie powinny być wysyłane do serwera
            const aFieldsToRemove = ["fileSelected"];

            // Usuń niepotrzebne pola
            aFieldsToRemove.forEach(function (sField) {
                delete oCleanData[sField];
            });

            // // Konwertuj daty z formatu string na obiekt Date dla OData
            // const aDateFields = ["PostingDate", "DocumentDate", "EntryDate", "DueDate", "ClearingDate"];

            // aDateFields.forEach(function (sField) {
            //     if (oCleanData[sField]) {
            //         // Sprawdź czy wartość to string czy obiekt Date
            //         if (typeof oCleanData[sField] === "string") {
            //             // Przekształć yyyy-MM-dd na obiekt Date
            //             const aParts = oCleanData[sField].split("-");
            //             if (aParts.length === 3) {
            //                 const oDate = new Date(parseInt(aParts[0], 10),
            //                     parseInt(aParts[1], 10) - 1, // miesiące w JS są 0-11
            //                     parseInt(aParts[2], 10));
            //                 oCleanData[sField] = oDate;
            //             }
            //         }
            //     }
            // });

            // Konwertuj liczby z formatu string na liczby
            // const aNumberFields = ["AmountDocument", "AmountLocal", "FiscalYear", "LineItem"];

            // aNumberFields.forEach(function (sField) {
            //     if (oCleanData[sField]) {
            //         // Usuń wszystkie spacje i zamień przecinek na kropkę
            //         let sValue = oCleanData[sField].toString().replace(/\s/g, '').replace(',', '.');

            //         // Konwertuj na liczbę (jeśli zawiera kropkę - float, w przeciwnym razie - int)
            //         if (sValue.includes('.')) {
            //             oCleanData[sField] = parseFloat(sValue);
            //         } else {
            //             oCleanData[sField] = parseInt(sValue, 10);
            //         }
            //     }
            // });
            return oCleanData;
        },

        /**
        * Walidacja danych formularza przed zapisem
        * @private
        * @returns {boolean} - czy formularz jest poprawny
        */
        _validateInvoiceForm: function () {
            // Pobierz dialog
            const oDialog = this.byId("addInvoiceDialog");

            // Pobierz dane z modelu
            const oData = oDialog.getModel().getData();

            // Lista wymaganych pól
            const aRequiredFields = ["CompanyCode", "FiscalYear", "DocumentNo"];

            // Sprawdź wymagane pola
            for (let i = 0; i < aRequiredFields.length; i++) {
                const sField = aRequiredFields[i];
                if (!oData[sField]) {
                    MessageBox.error("Pole '" + sField + "' jest wymagane.");
                    return false;
                }
            }

            return true;
        },

        /**
         * Odświeżenie listy faktur (jeśli istnieje)
         * @private
         */
        _refreshInvoiceList: function () {
            // Przykład odświeżenia bindingu listy
            const oTable = this.byId("invoiceTable");
            if (oTable) {
                const oBinding = oTable.getBinding("rows");
                if (oBinding) {
                    oBinding.refresh();
                }
            }
        },

        handleFileChange: function (oEvent) {
            var oFileModel = this.getView().getModel("file");
            var oFileUploader = this.byId("fileUploader");

            if (oFileUploader.getValue()) {
                oFileModel.setProperty("/fileSelected", true);
                oFileModel.setProperty("/status", "Plik wybrany: " + oFileUploader.getValue());
            } else {
                oFileModel.setProperty("/fileSelected", false);
                oFileModel.setProperty("/status", "Gotowy do wyboru pliku");
            }
        },

        handleAnalyzeInvoice: function () {
            var that = this;
            var oFileModel = this.getView().getModel("file");
            var oFileUploader = this.byId("fileUploader");
            var oFile = oFileUploader.oFileUpload.files[0];
            var oParsed;

            if (!oFile) {
                MessageToast.show("Proszę najpierw wybrać plik");
                return;
            }

            oFileModel.setProperty("/status", "Wczytywanie pliku...");

            // Wczytanie pliku
            this._readFile(oFile)
                .then(function (fileData) {
                    oFileModel.setProperty("/status", "Plik wczytany, przygotowanie do wysłania...");
                    return that._sendToNodeJs(fileData, oFile.type); //odkom
                    // return that._sendToOpenAI(fileData, oFile.type);

                    // oParsed = that._getSimulatedAIResponse();

                })
                .then(function (response) {
                    // Usuń otoczkę ```json i ``` z początku i końca
                    var cleaned = response.replace(/^```json\s*/, "").replace(/```$/, ""); //odkom
                    try {
                        oParsed = JSON.parse(cleaned); //odkom

                        // (1) Poprawka na polskie przecinki w liczbach
                        oParsed.AmountDocument = oParsed.AmountDocument.replace(",", ".");
                        oParsed.AmountLocal = oParsed.AmountLocal.replace(",", ".");


                        // oParsed = that._getSimulatedAIResponse();
                        if (oParsed) that._fillFormWithAIData(oParsed);
                    }
                    catch (err) {
                        console.error("Błąd parsowania JSON z odpowiedzi AI:", err);
                    }

                    oFileModel.setProperty("/status", "Analiza zakończona");
                    oFileModel.setProperty("/resultVisible", true);
                    oFileModel.setProperty("/result", JSON.stringify(response, null, 2));
                })
                .catch(function (error) {
                    oFileModel.setProperty("/status", "Błąd: " + error.message);
                    MessageBox.error("Wystąpił błąd podczas analizy faktury: " + error.message);
                });
        },

        onAfterClear: function (oEvent) {
            console.log("Przycisk Wyczyść został kliknięty");

            // Reszta kodu czyszczenia
            // ...
        },

        /**
         * Symulacja odpowiedzi z API OpenAI (w rzeczywistej aplikacji to byłoby zastąpione wywołaniem API)
         * @private
         * @returns {Object} Dane z odpowiedzi API
         */
        _getSimulatedAIResponse: function () {
            return {
                "FiscalYear": "2024",
                "DocumentNo": "1111",
                "LineItem": "001",
                "AmountDocument": "1230.00",
                "AmountLocal": "1230.00",
                "CompanyCode": "1234",
                "CurrencyCode": "PLN",
                "Customer": "777777",
                "DebitCredit": "D",
                "DocumentDate": "2024-12-11",
                "PostingDate": "2024-12-11",
                "EntryDate": "2024-12-11",
                "PaymentMethod": "P",
                "PaymentTerms": "14D",
                "Vendor": "888888",
                "Account": "300000",
                "DocumentType": "",
                "Reference": "",
                "CostCenter": "",
                "ProfitCenter": "",
                "Segment": "",
                "ClearingDoc": ""
            }

        },

        _fillFormWithAIData(oParsed) {
            var oDialogJSONModel = new sap.ui.model.json.JSONModel(oParsed);
            var oDateFormat = DateFormat.getDateInstance({ pattern: "yyyy-mm-dd" });

            if (oParsed) {
                // oDialogJSONModel.setProperty("/Client", oParsed.Client || "");
                oDialogJSONModel.setProperty("/CompanyCode", oParsed.CompanyCode || "");
                oDialogJSONModel.setProperty("/FiscalYear", oParsed.FiscalYear || "");
                oDialogJSONModel.setProperty("/DocumentNo", oParsed.DocumentNo || "");
                oDialogJSONModel.setProperty("/LineItem", oParsed.LineItem || "");

                oDialogJSONModel.setProperty("/AmountDocument", oParsed.AmountDocument || "0.00");
                oDialogJSONModel.setProperty("/AmountLocal", oParsed.AmountLocal || "0.00");

                // if (oParsed.PostingDate) {
                //     try {
                //         var oPostingDate = oDateFormat.parse(oParsed.PostingDate);
                //         oDialogJSONModel.setProperty("/PostingDate", oPostingDate);
                //     } catch (e) { }
                // }

                // if (oParsed.DocumentDate) {
                //     try {
                //         var oDocumentDate = oDateFormat.parse(oParsed.DocumentDate);
                //         oDialogJSONModel.setProperty("/DocumentDate", oDocumentDate);
                //     } catch (e) { }
                // }

                // if (oParsed.EntryDate) {
                //     try {
                //         var oEntryDate = oDateFormat.parse(oParsed.EntryDate);
                //         oDialogJSONModel.setProperty("/EntryDate", oEntryDate);
                //     } catch (e) { }
                // }

                // if (oParsed.DueDate) {
                //     try {
                //         var oDueDate = oDateFormat.parse(oParsed.DueDate);
                //         oDialogJSONModel.setProperty("/DueDate", oDueDate);
                //     } catch (e) { }
                // }

                // if (oParsed.ClearingDate) {
                //     try {
                //         var oClearingDate = oDateFormat.parse(oParsed.ClearingDate);
                //         oDialogJSONModel.setProperty("/ClearingDate", oClearingDate);
                //     } catch (e) { }
                // }

                oDialogJSONModel.setProperty("/DocumentType", oParsed.DocumentType || "");
                oDialogJSONModel.setProperty("/Reference", oParsed.Reference || "");
                oDialogJSONModel.setProperty("/DebitCredit", oParsed.DebitCredit || "D");
                oDialogJSONModel.setProperty("/Account", oParsed.Account || "");
                oDialogJSONModel.setProperty("/Vendor", oParsed.Vendor || "");
                oDialogJSONModel.setProperty("/Customer", oParsed.Customer || "");
                oDialogJSONModel.setProperty("/CostCenter", oParsed.CostCenter || "");
                oDialogJSONModel.setProperty("/ProfitCenter", oParsed.ProfitCenter || "");
                oDialogJSONModel.setProperty("/Segment", oParsed.Segment || "");
                oDialogJSONModel.setProperty("/CurrencyCode", oParsed.CurrencyCode || "PLN");
                oDialogJSONModel.setProperty("/PaymentTerms", oParsed.PaymentTerms || "");
                oDialogJSONModel.setProperty("/PaymentMethod", oParsed.PaymentMethod || "");
                oDialogJSONModel.setProperty("/ClearingDoc", oParsed.ClearingDoc || "");
            }

            this.oView.byId("addInvoiceDialog").setModel(oDialogJSONModel);
            // oDialogJSONModel.refresh(true);

            // 4. Ważne - upewnij się, że formularz też ma dostęp do modelu
            var oForm = this.oView.byId("invoiceForm");
            if (oForm) {
                oForm.setModel(oDialogJSONModel);
            }

            // 5. Odśwież model i wymuszenie aktualizacji UI
            oDialogJSONModel.refresh(true);

            // 6. Sprawdź czy kontrolki są prawidłowo powiązane z modelem
            console.log("Model został ustawiony:", oDialogJSONModel.getData());
            this.oBusyDialog.close();
        },

        _readFile: function (file) {
            return new Promise(function (resolve, reject) {
                var reader = new FileReader();

                reader.onload = function (e) {
                    var arrayBuffer = e.target.result;
                    resolve(arrayBuffer);
                };

                reader.onerror = function (e) {
                    reject(new Error("Błąd odczytu pliku: " + e.target.error));
                };

                if (file.type === "application/pdf") {
                    reader.readAsArrayBuffer(file);
                } else {
                    // Dla obrazów
                    reader.readAsDataURL(file);
                }
            });
        },

        _sendToNodeJs(fileData, fileType) {
            var that = this;
            var oFileModel = this.getView().getModel("file");

            oFileModel.setProperty("/status", "Wysyłanie do backendu...");

            // Pokazanie wskaźnika zajętości
            var oBusyDialog = new BusyDialog({
                title: "Processing",
                text: "Analyzing your request..."
            });
            this.oBusyDialog = oBusyDialog;
            this.oBusyDialog.open();

            return new Promise(function (resolve, reject) {
                // Konwertuj plik do base64 jeśli nie jest jeszcze zakodowany
                var blob;
                if (typeof fileData === "string" && fileData.startsWith("data:")) {
                    var base64Data = fileData.split(",")[1];
                    var mimeType = fileData.split(",")[0].split(":")[1].split(";")[0];
                    blob = that._base64ToBlob(base64Data, mimeType);
                } else {
                    blob = new Blob([fileData], { type: fileType });
                }

                that._fileToBase64(blob)
                    .then(function (base64Data) {
                        // Przygotuj payload do wysłania do backendu
                        var requestPayload = {
                            filename: "invoice." + (fileType.includes("pdf") ? "pdf" : "jpg"),
                            mimeType: fileType,
                            base64: base64Data
                        };

                        var requestImageUrl = { image_url: "" };
                        requestImageUrl.image_url = requestPayload.base64;

                        fetch("https://node-app-stellar-mouse-fl.cfapps.us10-001.hana.ondemand.com/analyze", {
                            method: "POST",
                            headers: {
                                "Content-Type": "application/json"
                            },
                            body: JSON.stringify(requestImageUrl)
                        })
                            .then(res => res.json())
                            .then(data => resolve(data.result));
                    });
            })
                .catch(function (error) {
                    reject(error);
                });


        },

        _sendToOpenAI: function (fileData, fileType) {
            var that = this;
            var oFileModel = this.getView().getModel("file");
            const prompt = `
            Przeanalizuj załączoną fakturę (plik PDF/JPG/PNG) i zwróć dane w formacie JSON zgodnym ze strukturą tabeli SAP \`zfi_acdoca\`.

            Zwróć **wyłącznie JSON**, zawierający poniższe pola jako klucze. Jeśli pole nie występuje w dokumencie lub nie da się go rozpoznać, przypisz pusty string \`""\`.

            Oto lista wymaganych pól:
            - client
            - company_code
            - fiscal_year
            - document_no
            - line_item
            - posting_date
            - document_date
            - entry_date
            - document_type
            - reference
            - debit_credit
            - account
            - vendor
            - customer
            - cost_center
            - profit_center
            - segment
            - currency_code
            - amount_document
            - amount_local
            - payment_terms
            - payment_method
            - due_date
            - clearing_doc
            - clearing_date
            - created_by
            - created_at
            - changed_by
            - changed_at

            Przykład odpowiedzi:
            {
            "client": "100",
            "company_code": "2000",
            "fiscal_year": "2024",
            "document_no": "5100001234",
            ...
            "created_by": "",
            "created_at": "",
            "changed_by": "",
            "changed_at": ""
            }

            Nie dodawaj żadnego opisu, komentarza, ani dodatkowych tekstów. Zwróć **tylko JSON**.
            `;

            oFileModel.setProperty("/status", "Wysyłanie do OpenAI API...");

            return new Promise(function (resolve, reject) {
                // Przygotowanie danych do wysłania
                var formData = new FormData();

                // Konwersja danych pliku na właściwy format
                var blob;
                if (typeof fileData === "string" && fileData.startsWith("data:")) {
                    // Dla obrazów odczytanych jako DataURL
                    var base64Data = fileData.split(",")[1];
                    var mimeType = fileData.split(",")[0].split(":")[1].split(";")[0];
                    blob = that._base64ToBlob(base64Data, mimeType);
                } else {
                    // Dla plików PDF
                    blob = new Blob([fileData], { type: fileType });
                }

                formData.append("file", blob);
                formData.append("model", "gpt-4o");

                var requestData = {
                    model: "gpt-4o",
                    messages: [
                        {
                            role: "user",
                            content: [
                                {
                                    type: "text",
                                    text: prompt
                                },
                                {
                                    type: "image_url",
                                    image_url: {
                                        url: "data:image/jpeg;base64,..." // Ten URL zostanie zastąpiony przez rzeczywiste dane w kodzie poniżej
                                    }
                                }
                            ]
                        }
                    ],
                    max_tokens: 1000
                };

                // Pobierz bazę danych jako Base64
                that._fileToBase64(blob)
                    .then(function (base64Data) {
                        // Aktualizacja URL obrazu w zapytaniu
                        requestData.messages[0].content[1].image_url.url =
                            fileType.startsWith("image") ?
                                "data:" + fileType + ";base64," + base64Data :
                                "data:application/pdf;base64," + base64Data;


                        // Wysłanie zapytania do OpenAI
                        $.ajax({
                            url: "https://api.openai.com/v1/chat/completions",
                            type: "POST",
                            data: JSON.stringify(requestData),
                            contentType: "application/json",
                            headers: {
                                "Authorization": "Bearer sk"
                            },
                            success: function (response) {
                                resolve(response);
                            },
                            error: function (jqXHR, textStatus, errorThrown) {
                                reject(new Error("Błąd API: " + errorThrown));
                            }
                        });
                    })
                    .catch(function (error) {
                        reject(error);
                    });
            });
        },

        _fileToBase64: function (blob) {
            return new Promise(function (resolve, reject) {
                var reader = new FileReader();
                reader.onload = function () {
                    var base64 = reader.result.split(",")[1];
                    resolve(base64);
                };
                reader.onerror = function (error) {
                    reject(error);
                };
                reader.readAsDataURL(blob);
            });
        },

        _base64ToBlob: function (base64, mimeType) {
            var byteCharacters = atob(base64);
            var byteArrays = [];

            for (var i = 0; i < byteCharacters.length; i += 512) {
                var slice = byteCharacters.slice(i, i + 512);

                var byteNumbers = new Array(slice.length);
                for (var j = 0; j < slice.length; j++) {
                    byteNumbers[j] = slice.charCodeAt(j);
                }

                var byteArray = new Uint8Array(byteNumbers);
                byteArrays.push(byteArray);
            }

            return new Blob(byteArrays, { type: mimeType });
        },
    });
})
