sap.ui.define([
    "sap/ui/core/mvc/Controller",
    "sap/ui/model/json/JSONModel",
    "sap/m/MessageBox",
    "sap/m/MessageToast",
    "sap/ui/core/Fragment",
    "sap/ui/core/format/DateFormat"
], (Controller, JSONModel, MessageBox, MessageToast, Fragment, DateFormat) => {
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

            // Twój klucz API OpenAI
            this._openAIApiKey = ""; // To powinno być pobierane z bezpiecznego źródła
        },

        onAddButton() {
            var oView = this.getView();

            // Tworzenie nowego modelu dla formularza
            // var oNewDocumentModel = new JSONModel(this._createEmptyDocument());
            // this.getView().setModel(oNewDocumentModel, "newDocument");

            // Otwarcie dialogu
            if (!this._oAddDialog) {
                Fragment.load({
                    id: oView.getId(),
                    name: "ainvoicer.view.AddInvoice",
                    controller: this
                }).then(function (oDialog) {
                    this._oAddDialog = oDialog;
                    oView.addDependent(this._oAddDialog);
                    this._oAddDialog.open();

                    // Ustawienie kontekstu modelu na dialogu
                    // this._oAddDialog.bindElement({
                    //     path: "/"
                    // });
                }.bind(this));
            } else {
                this._oAddDialog.open();
            }
        },

        onCancelDialog: function () {
            this._oAddDialog.close();
        },

        onSaveDocument: function () {
            var oView = this.getView();
            var oDataModel = oView.getModel();
            var oNewDocument = oView.getModel("newDocument").getData();

            // Ustawienie wskaźnika zajętości
            this.oViewModel.setProperty("/busy", true);

            // Utworzenie wpisu
            var oContext = oDataModel.createEntry("/ZC_FI_ACDOCA'", {
                properties: oNewDocument,
                groupId: "changes"
            });

            // Wykonanie żądania na serwerze
            oDataModel.submitChanges({
                groupId: "changes",
                success: function (oData) {
                    this.oViewModel.setProperty("/busy", false);

                    // Sprawdzenie, czy zapis się powiódł
                    var oResponse = oData.__batchResponses[0].__changeResponses;
                    if (oResponse && oResponse.length > 0 && oResponse[0].statusCode >= 200 && oResponse[0].statusCode < 300) {
                        MessageBox.success("Dokument został dodany pomyślnie.", {
                            onClose: function () {
                                this._oAddDialog.close();
                                oDataModel.refresh(true);
                            }.bind(this)
                        });
                    } else {
                        MessageBox.error("Wystąpił błąd podczas dodawania dokumentu.");
                    }
                }.bind(this),
                error: function (oError) {
                    this.oViewModel.setProperty("/busy", false);
                    MessageBox.error("Wystąpił błąd podczas dodawania dokumentu: " + oError.message);
                }.bind(this)
            });
        },

        /**
         * Pomocnicza funkcja tworząca pusty dokument z domyślnymi wartościami
         * @private
         * @returns {Object} Nowy pusty dokument
         */
        _createEmptyDocument: function () {
            return {
                client: "100", // Domyślny klient
                company_code: "",
                fiscal_year: new Date().getFullYear().toString(),
                document_no: "",
                line_item: "",
                posting_date: new Date(),
                document_date: new Date(),
                entry_date: new Date(),
                document_type: "",
                reference: "",
                debit_credit: "D", // Domyślnie Debit
                account: "",
                vendor: "",
                customer: "",
                cost_center: "",
                profit_center: "",
                segment: "",
                currency_code: "PLN", // Domyślna waluta
                amount_document: "0.00",
                amount_local: "0.00",
                payment_terms: "",
                payment_method: "",
                due_date: null,
                clearing_doc: "",
                clearing_date: null
            };
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
                    return that._sendToNodeJs(fileData, oFile.type);
                    // return that._sendToOpenAI(fileData, oFile.type);
                })
                .then(function (response) {
                    // var rawContent = response.choices[0].message.content;

                    // Usuń otoczkę ```json i ``` z początku i końca
                    var cleaned = response.replace(/^```json\s*/, "").replace(/```$/, "");
                    try {
                        oParsed = JSON.parse(cleaned);

                        // (1) Poprawka na polskie przecinki w liczbach
                        oParsed.amount_document = oParsed.amount_document.replace(",", ".");
                        oParsed.amount_local = oParsed.amount_local.replace(",", ".");
                        // oParsed = this._getSimulatedAIResponse();
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

        /**
         * Symulacja odpowiedzi z API OpenAI (w rzeczywistej aplikacji to byłoby zastąpione wywołaniem API)
         * @private
         * @returns {Object} Dane z odpowiedzi API
         */
        _getSimulatedAIResponse: function () {
            return {
                client: "",
                company_code: "",
                fiscal_year: "2024",
                document_no: "51952/12/2024",
                line_item: "",
                amount_document: "1230,00",
                amount_local: "",
                changed_at: "",
                changed_by: "",
                clearing_date: "",
                clearing_doc: "",
                client: "",
                company_code: "",
                cost_center: "",
                created_at: "",
                created_by: "",
                currency_code: "PLN",
                customer: "ABC INFO Andrzej Kowalski",
                debit_credit: "",
                document_date: "13-12-2024",
                document_no: "51952/12/2024",
                document_type: "",
                due_date: "",
                entry_date: "",
                fiscal_year: "2024",
                line_item: "",
                payment_method: "przelew",
                payment_terms: "14 dni",
                posting_date: "13-12-2024",
                profit_center: "",
                reference: "",
                segment: "",
                vendor: "Usługi Informatyczne Jan Nowak"
            };
        },

        _fillFormWithAIData(oParsed) {
            // var oModel = this.getView().getModel("newDocument");
            var oDialogJSONModel = new sap.ui.model.json.JSONModel(oParsed);
            var oDateFormat = DateFormat.getDateInstance({ pattern: "dd-MM-yyyy" });

            // Mapowanie pól z odpowiedzi AI do modelu formularza
            if (oParsed) {
                // Najpierw ustawiamy główne pola
                oDialogJSONModel.setProperty("/client", oParsed.client || "");
                oDialogJSONModel.setProperty("/company_code", oParsed.company_code || "");
                oDialogJSONModel.setProperty("/fiscal_year", oParsed.fiscal_year || "");
                oDialogJSONModel.setProperty("/document_no", oParsed.document_no || "");
                oDialogJSONModel.setProperty("/line_item", oParsed.line_item || "");

                // Kwoty
                oDialogJSONModel.setProperty("/amount_document", oParsed.amount_document || "0.00");
                oDialogJSONModel.setProperty("/amount_local", oParsed.amount_local || "0.00");

                // Daty - konwersja z formatu tekstowego na obiekt Date
                if (oParsed.posting_date) {
                    try {
                        var oPostingDate = oDateFormat.parse(oParsed.posting_date);
                        oDialogJSONModel.setProperty("/posting_date", oPostingDate);
                    } catch (e) {
                        // W przypadku błędu formatu daty, zachowujemy obecną wartość
                    }
                }

                if (oParsed.document_date) {
                    try {
                        var oDocumentDate = oDateFormat.parse(oParsed.document_date);
                        oDialogJSONModel.setProperty("/document_date", oDocumentDate);
                    } catch (e) {
                        // W przypadku błędu formatu daty, zachowujemy obecną wartość
                    }
                }

                if (oParsed.entry_date) {
                    try {
                        var oEntryDate = oDateFormat.parse(oParsed.entry_date);
                        oDialogJSONModel.setProperty("/entry_date", oEntryDate);
                    } catch (e) {
                        // W przypadku błędu formatu daty, zachowujemy obecną wartość
                    }
                }

                if (oParsed.due_date) {
                    try {
                        var oDueDate = oDateFormat.parse(oParsed.due_date);
                        oDialogJSONModel.setProperty("/due_date", oDueDate);
                    } catch (e) {
                        // W przypadku błędu formatu daty, zachowujemy obecną wartość
                    }
                }

                if (oParsed.clearing_date) {
                    try {
                        var oClearingDate = oDateFormat.parse(oParsed.clearing_date);
                        oDialogJSONModel.setProperty("/clearing_date", oClearingDate);
                    } catch (e) {
                        // W przypadku błędu formatu daty, zachowujemy obecną wartość
                    }
                }

                // Pozostałe pola
                oDialogJSONModel.setProperty("/document_type", oParsed.document_type || "");
                oDialogJSONModel.setProperty("/reference", oParsed.reference || "");
                oDialogJSONModel.setProperty("/debit_credit", oParsed.debit_credit || "D");
                oDialogJSONModel.setProperty("/account", oParsed.account || "");
                oDialogJSONModel.setProperty("/vendor", oParsed.vendor || "");
                oDialogJSONModel.setProperty("/customer", oParsed.customer || "");
                oDialogJSONModel.setProperty("/cost_center", oParsed.cost_center || "");
                oDialogJSONModel.setProperty("/profit_center", oParsed.profit_center || "");
                oDialogJSONModel.setProperty("/segment", oParsed.segment || "");
                oDialogJSONModel.setProperty("/currency_code", oParsed.currency_code || "PLN");
                oDialogJSONModel.setProperty("/payment_terms", oParsed.payment_terms || "");
                oDialogJSONModel.setProperty("/payment_method", oParsed.payment_method || "");
                oDialogJSONModel.setProperty("/clearing_doc", oParsed.clearing_doc || "");
            }

            var oDialog = this.oView.byId("addInvoiceDialog").setModel(oDialogJSONModel);
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

                        // $.ajax({
                        //     url: "https://node-app-stellar-mouse-fl.cfapps.us10-001.hana.ondemand.com/analyze", // backend endpoint
                        //     type: "POST",
                        //     data: JSON.stringify(requestImageUrl),
                        //     contentType: "application/json",
                        //     success: function (response) {
                        //         resolve(response);
                        //     },
                        //     error: function (jqXHR, textStatus, errorThrown) {
                        //         reject(new Error("Błąd backendu: " + errorThrown));
                        //     }
                        // });

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

        // onPressAddButton(oEvent) {
        //     this.byId("fileInput").getDomRef().click();
        //     // fetch("https://python-app-grouchy-platypus-jj.cfapps.us10-001.hana.ondemand.com/predict", {
        //     //     method: "POST",
        //     //     headers: {
        //     //       "Content-Type": "application/json"
        //     //     },
        //     //     body: JSON.stringify({
        //     //       amount: 1000,
        //     //       currency: "PLN"
        //     //     })
        //     //   })
        //     //   .then(res => res.json())
        //     //   .then(data => console.log(data));

        // },
    });
})
