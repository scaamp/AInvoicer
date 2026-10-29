sap.ui.define([
	"sap/ui/test/Opa5",
	"sap/ui/test/actions/Press",
	"sap/ui/test/actions/EnterText",
	"sap/ui/core/Element"
], function (Opa5, Press, EnterText, Element) {
	"use strict";
	var sViewName = "Main";

	/** Liczba wierszy aktualnie widocznych w tabeli (po filtrach). */
	function rowCount(oTable) {
		var oBinding = oTable.getBinding("rows");
		return oBinding ? oBinding.getLength() : -1;
	}

	function invoiceNumbers(oTable) {
		var oBinding = oTable.getBinding("rows");
		return oBinding.getContexts(0, oBinding.getLength()).map(function (oContext) {
			return oContext.getProperty("InvoiceNumber");
		});
	}

	/** Atrapa window.fetch dla zewnętrznych usług AI; zwraca funkcję przywracającą oryginał. */
	function stubFetch(mRoutes) {
		var fnOriginal = Opa5.getWindow().fetch;
		var aCalls = [];
		Opa5.getWindow().fetch = function (sUrl, oOptions) {
			aCalls.push({ url: String(sUrl), body: oOptions && oOptions.body });
			var sKey = Object.keys(mRoutes).filter(function (sPart) { return String(sUrl).indexOf(sPart) !== -1; })[0];
			if (!sKey) {
				return fnOriginal.apply(this, arguments);
			}
			var oSpec = mRoutes[sKey];
			if (oSpec.reject) {
				return Promise.reject(new TypeError(oSpec.reject));
			}
			return Promise.resolve({
				ok: (oSpec.status || 200) < 300,
				status: oSpec.status || 200,
				json: function () { return Promise.resolve(oSpec.body); }
			});
		};
		return { calls: aCalls, restore: function () { Opa5.getWindow().fetch = fnOriginal; } };
	}

	Opa5.createPageObjects({
		onTheViewPage: {

			actions: {

				iSelectView: function (sTabId) {
					return this.waitFor({
						id: sTabId,
						viewName: sViewName,
						actions: new Press(),
						errorMessage: "View tab " + sTabId + " not found"
					});
				},

				/** Przyciski mogą być w menu przepełnienia OverflowToolbar (nierenderowane) — wtedy firePress. */
				iPressButton: function (sId) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oButton) {
							return oButton.getEnabled();
						},
						success: function (oButton) {
							if (oButton.getDomRef()) {
								new Press().executeOn(oButton);
							} else {
								oButton.firePress();
							}
						},
						errorMessage: "Button " + sId + " not found or not enabled"
					});
				},

				iExpandPanel: function (sId) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						success: function (oPanel) {
							oPanel.setExpanded(true);
						}
					});
				},

				iSearchKsef: function (sText) {
					return this.waitFor({
						id: "ksefSearchField",
						viewName: sViewName,
						actions: new EnterText({ text: sText, pressEnterKey: true }),
						errorMessage: "KSeF search field not found"
					});
				},

				iClearKsefFilters: function () {
					return this.iCallController(function (oController) {
						oController.onKsefClear();
					});
				},

				iSelectInvoice: function (sInvoiceNumber) {
					return this.waitFor({
						id: "ksefInvoiceTable",
						viewName: sViewName,
						success: function (oTable) {
							var iIndex = invoiceNumbers(oTable).indexOf(sInvoiceNumber);
							Opa5.assert.ok(iIndex >= 0, "Invoice " + sInvoiceNumber + " is in the table");
							oTable.setSelectedIndex(iIndex);
						}
					});
				},

				iSortKsefBy: function (sColumnId, bDescending) {
					return this.waitFor({
						id: "ksefInvoiceTable",
						viewName: sViewName,
						success: function (oTable) {
							var oColumn = oTable.getColumns().filter(function (oCandidate) {
								return oCandidate.getId().slice(-sColumnId.length) === sColumnId;
							})[0];
							oTable.sort(oColumn, bDescending ? "Descending" : "Ascending");
						}
					});
				},

				iCallController: function (fnCallback) {
					return this.waitFor({
						id: "page",
						viewName: sViewName,
						// Strona może być za otwartym dialogiem — wywołanie kontrolera nie wymaga interakcji.
						autoWait: false,
						visible: false,
						success: function (oPage) {
							var oView = oPage.getParent();
							while (oView && !oView.getController) {
								oView = oView.getParent();
							}
							fnCallback.call(this, oView.getController(), oView);
						}
					});
				},

				iStubAiServices: function (mRoutes) {
					return this.waitFor({
						success: function () {
							if (this.getContext().fetchStub) {
								this.getContext().fetchStub.restore();
							}
							this.getContext().fetchStub = stubFetch(mRoutes);
						}
					});
				},

				iRestoreAiServices: function () {
					return this.waitFor({
						success: function () {
							if (this.getContext().fetchStub) {
								this.getContext().fetchStub.restore();
								this.getContext().fetchStub = null;
							}
						}
					});
				},

				iAskTheAiAssistant: function (sQuery) {
					return this.waitFor({
						id: "aiCommandInput",
						viewName: sViewName,
						actions: new EnterText({ text: sQuery, pressEnterKey: true }),
						errorMessage: "AI command input not found"
					});
				},

				iSelectAiContext: function (sKey) {
					return this.waitFor({
						id: "contextSelector",
						viewName: sViewName,
						success: function (oComboBox) {
							oComboBox.setSelectedKey(sKey);
							oComboBox.fireChange({ value: oComboBox.getValue() });
						}
					});
				}
			},

			assertions: {

				iShouldSeeThePageView: function () {
					return this.waitFor({
						id: "page",
						viewName: sViewName,
						success: function () {
							Opa5.assert.ok(true, "The " + sViewName + " view is displayed");
						},
						errorMessage: "Did not find the " + sViewName + " view"
					});
				},

				iShouldSeeKsefRows: function (iExpected) {
					return this.waitFor({
						id: "ksefInvoiceTable",
						viewName: sViewName,
						check: function (oTable) {
							return oTable.getVisible() && rowCount(oTable) === iExpected;
						},
						success: function () {
							Opa5.assert.ok(true, "KSeF table shows " + iExpected + " invoices");
						},
						errorMessage: "KSeF table does not show " + iExpected + " invoices"
					});
				},

				iShouldSeeFirstKsefInvoice: function (sInvoiceNumber) {
					return this.waitFor({
						id: "ksefInvoiceTable",
						viewName: sViewName,
						check: function (oTable) {
							return invoiceNumbers(oTable)[0] === sInvoiceNumber;
						},
						success: function () {
							Opa5.assert.ok(true, "First invoice is " + sInvoiceNumber);
						},
						errorMessage: "First invoice is not " + sInvoiceNumber
					});
				},

				iShouldSeeOnlyDirection: function (sDirection) {
					return this.waitFor({
						id: "ksefInvoiceTable",
						viewName: sViewName,
						check: function (oTable) {
							var oBinding = oTable.getBinding("rows");
							return oBinding.getContexts(0, oBinding.getLength()).every(function (oContext) {
								return oContext.getProperty("Direction") === sDirection;
							});
						},
						success: function () {
							Opa5.assert.ok(true, "Only " + sDirection + " invoices are listed");
						}
					});
				},

				iShouldSeeControlVisible: function (sId, bVisible) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oControl) {
							return oControl.getVisible() === bVisible;
						},
						success: function () {
							Opa5.assert.ok(true, sId + (bVisible ? " is visible" : " is hidden"));
						},
						errorMessage: sId + " visibility is not " + bVisible
					});
				},

				iShouldSeeButtonState: function (sId, bEnabled, sTooltipPart) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						enabled: false,
						visible: false,
						check: function (oButton) {
							return oButton.getEnabled() === bEnabled
								&& (!sTooltipPart || String(oButton.getTooltip()).indexOf(sTooltipPart) !== -1);
						},
						success: function (oButton) {
							Opa5.assert.ok(true, sId + " enabled=" + bEnabled + (sTooltipPart ? " (" + oButton.getTooltip() + ")" : ""));
						},
						errorMessage: sId + " is not enabled=" + bEnabled + " with tooltip containing '" + sTooltipPart + "'"
					});
				},

				iShouldSeeTextContaining: function (sId, sText) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oControl) {
							return String(oControl.getText()).indexOf(sText) !== -1;
						},
						success: function () {
							Opa5.assert.ok(true, sId + " contains '" + sText + "'");
						},
						errorMessage: sId + " does not contain '" + sText + "'"
					});
				},

				iShouldSeeTheDetailsDialog: function (sInvoiceNumber, iItems, iOperations) {
					return this.waitFor({
						id: "ksefDetailsDialog",
						viewName: sViewName,
						check: function (oDialog) {
							var oModel = oDialog.getModel("ksefDetails");
							return oDialog.isOpen() && !oModel.getProperty("/busy") && !oModel.getProperty("/historyBusy")
								&& oModel.getProperty("/invoice/InvoiceNumber") === sInvoiceNumber
								&& oModel.getProperty("/invoice/Items").length === iItems
								&& oModel.getProperty("/operations").length === iOperations;
						},
						success: function () {
							Opa5.assert.ok(true, "Details of " + sInvoiceNumber + ": " + iItems + " items, " + iOperations + " operations");
						},
						errorMessage: "Details dialog for " + sInvoiceNumber + " not shown as expected"
					});
				},

				iShouldSeeDetailsTab: function (sKey) {
					return this.waitFor({
						id: "ksefDetailsTabs",
						viewName: sViewName,
						check: function (oTabs) {
							return oTabs.getSelectedKey() === sKey;
						},
						success: function () {
							Opa5.assert.ok(true, "Details tab " + sKey + " is selected");
						}
					});
				},

				iShouldSeeColumnVisible: function (sColumnId, bVisible) {
					return this.waitFor({
						id: sColumnId,
						viewName: sViewName,
						visible: false,
						check: function (oColumn) {
							return oColumn.getVisible() === bVisible;
						},
						success: function () {
							Opa5.assert.ok(true, sColumnId + " visible=" + bVisible);
						}
					});
				},

				iShouldSeeLatestAiAnswerContaining: function (aParts) {
					return this.waitFor({
						id: "page",
						viewName: sViewName,
						check: function (oPage) {
							var aConversations = oPage.getModel("history").getProperty("/conversations");
							var sResponse = aConversations.length && aConversations[0].isProcessed ? String(aConversations[0].response) : "";
							return aParts.every(function (sPart) { return sResponse.indexOf(sPart) !== -1; });
						},
						success: function (oPage) {
							Opa5.assert.ok(true, "AI answer: " + oPage.getModel("history").getProperty("/conversations")[0].response);
						},
						errorMessage: "Latest AI answer does not contain " + aParts.join(", ")
					});
				},

				iShouldSeeNoOpenBusyDialog: function () {
					return this.waitFor({
						autoWait: false,
						check: function () {
							return Element.registry.filter(function (oElement) {
								return oElement.isA("sap.m.Dialog") && oElement.isOpen() && oElement.getParent()
									&& oElement.getParent().isA && oElement.getParent().isA("sap.m.BusyDialog");
							}).length === 0;
						},
						success: function () {
							Opa5.assert.ok(true, "No BusyDialog left open");
						}
					});
				},

				iShouldSeeLegacyTableFiltered: function () {
					return this.waitFor({
						id: "invoiceTable",
						viewName: sViewName,
						check: function (oTable) {
							var oBinding = oTable.getBinding("rows");
							return oTable.getVisible() && oBinding && oBinding.getFilters("Application").length > 0;
						},
						success: function () {
							Opa5.assert.ok(true, "Legacy FI table received the AI filters");
						},
						errorMessage: "Legacy table was not filtered by the AI result"
					});
				},

				iShouldSeeAiRequestCount: function (iCount) {
					return this.waitFor({
						success: function () {
							var aCalls = (this.getContext().fetchStub || { calls: [] }).calls.filter(function (oCall) {
								return oCall.url.indexOf("/filter") !== -1;
							});
							Opa5.assert.strictEqual(aCalls.length, iCount, iCount + " request(s) to the existing /filter contract");
						}
					});
				},

				iShouldSeeTheScannerDialog: function () {
					return this.waitFor({
						id: "addInvoiceDialog",
						viewName: sViewName,
						check: function (oDialog) {
							return oDialog.isOpen();
						},
						success: function (oDialog) {
							var oView = oDialog.getParent();
							Opa5.assert.ok(oView.byId("fileUploader"), "FileUploader is present");
							Opa5.assert.deepEqual(oView.byId("fileUploader").getFileType(), ["pdf", "jpg", "jpeg", "png"], "PDF/JPG/PNG accepted");
							Opa5.assert.ok(oView.byId("scanLegacyNotice").getVisible(), "Legacy FI notice is shown");
						},
						errorMessage: "Scanner dialog did not open"
					});
				},

				iShouldSeeScannerFormValue: function (sPath, sValue) {
					return this.waitFor({
						id: "addInvoiceDialog",
						viewName: sViewName,
						check: function (oDialog) {
							return oDialog.getModel().getProperty(sPath) === sValue;
						},
						success: function () {
							Opa5.assert.ok(true, "Scanner form " + sPath + " = " + sValue);
						}
					});
				}
			}
		}
	});
});
