sap.ui.define([
	"sap/ui/test/Opa5",
	"sap/ui/test/actions/Press",
	"sap/ui/test/actions/EnterText",
	"sap/m/InstanceManager"
], function (Opa5, Press, EnterText, InstanceManager) {
	"use strict";
	var sViewName = "InboundInbox";

	function rows(oTable) {
		return oTable.getModel("inbox").getProperty("/rows") || [];
	}

	/** Szpieg window.fetch: zapisuje URL-e żądań (bez treści), przepuszcza do oryginału. */
	function installFetchSpy(oContext) {
		var oWindow = Opa5.getWindow();
		var fnOriginal = oWindow.fetch;
		oContext.fetchCalls = [];
		oWindow.fetch = function (vUrl) {
			oContext.fetchCalls.push(String(vUrl && vUrl.url || vUrl));
			return fnOriginal.apply(this, arguments);
		};
		oContext.restoreFetch = function () { oWindow.fetch = fnOriginal; };
	}

	Opa5.createPageObjects({
		onTheInboxPage: {

			actions: {

				iRecordNetworkRequests: function () {
					return this.waitFor({
						success: function () {
							installFetchSpy(this.getContext());
						}
					});
				},

				iStopRecordingNetworkRequests: function () {
					return this.waitFor({
						success: function () {
							var oContext = this.getContext();
							if (oContext.restoreFetch) {
								oContext.restoreFetch();
							}
						}
					});
				},

				iPressButton: function (sId) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oButton) { return oButton.getEnabled(); },
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

				iEnterFilter: function (sId, sText) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						actions: new EnterText({ text: sText, pressEnterKey: true }),
						errorMessage: "Filter " + sId + " not found"
					});
				},

				iCallController: function (fnCallback) {
					return this.waitFor({
						id: "inboxPage",
						viewName: sViewName,
						success: function (oPage) {
							var oView = oPage;
							while (oView && !oView.getController) {
								oView = oView.getParent();
							}
							fnCallback.call(this, oView.getController());
						}
					});
				},

				/** Czeka na otwarty (modalny) dialog sortowania i zatwierdza wybór jak użytkownik. */
				iConfirmSortDialog: function (sField, bDescending) {
					return this.waitFor({
						id: "inboxPage",
						viewName: sViewName,
						visible: false,
						// ViewSettingsDialog nie ma isOpen(); jego wewnętrzny sap.m.Dialog jest w InstanceManager.
						check: function () {
							return InstanceManager.getOpenDialogs().length > 0;
						},
						success: function (oPage) {
							var sPrefix = oPage.getId().replace(/inboxPage$/, "");
							var oDialog = sap.ui.getCore().byId(sPrefix + "inboxSortDialog");
							Opa5.assert.ok(oDialog, "sort dialog is open");
							oDialog.setSelectedSortItem(sPrefix + "inboxSortItem-" + sField);
							oDialog.setSortDescending(bDescending);
							// Przycisk OK wewnętrznego dialogu: zamyka go i wywołuje confirm z bieżącym wyborem.
							new Press().executeOn(InstanceManager.getOpenDialogs()[0].getBeginButton());
						},
						errorMessage: "sort dialog did not open"
					});
				},

				iSetFilterAndSearch: function (sPath, vValue) {
					return this.iCallController(function (oController) {
						oController.getView().getModel("inbox").setProperty("/filters/" + sPath, vValue);
						oController.onSearch();
					});
				},

				iClearFilters: function () {
					return this.iCallController(function (oController) {
						oController.onClearFilters();
					});
				},

				iOpenInvoice: function (sInvoiceNumber) {
					return this.waitFor({
						id: "inboxTable",
						viewName: sViewName,
						check: function (oTable) { return !oTable.getBusy(); },
						success: function (oTable) {
							var oItem = oTable.getItems().filter(function (oCandidate) {
								return oCandidate.getBindingContext("inbox").getProperty("invoiceNumber") === sInvoiceNumber;
							})[0];
							Opa5.assert.ok(oItem, "Invoice " + sInvoiceNumber + " is listed");
							new Press().executeOn(oItem);
						}
					});
				},

				iOpenInvoiceWhere: function (fnPredicate, sLabel) {
					return this.waitFor({
						id: "inboxTable",
						viewName: sViewName,
						check: function (oTable) { return !oTable.getBusy(); },
						success: function (oTable) {
							var oItem = oTable.getItems().filter(function (oCandidate) {
								return fnPredicate(oCandidate.getBindingContext("inbox").getObject());
							})[0];
							Opa5.assert.ok(oItem, "Row found: " + sLabel);
							new Press().executeOn(oItem);
						}
					});
				}
			},

			assertions: {

				iShouldSeeTheInbox: function () {
					return this.waitFor({
						id: "inboxPage",
						viewName: sViewName,
						success: function () {
							Opa5.assert.ok(true, "KSeF Inbox page is displayed");
						}
					});
				},

				iShouldSeeRows: function (iCount, iTotal) {
					return this.waitFor({
						id: "inboxTable",
						viewName: sViewName,
						visible: false,
						check: function (oTable) {
							return !oTable.getBusy() && rows(oTable).length === iCount
								&& (iTotal === undefined || oTable.getModel("inbox").getProperty("/count") === iTotal);
						},
						success: function (oTable) {
							Opa5.assert.strictEqual(oTable.getItems().length, iCount, iCount + " rows rendered");
						},
						errorMessage: "Expected " + iCount + " inbox rows"
					});
				},

				iShouldSeeFirstInvoice: function (sInvoiceNumber) {
					return this.waitFor({
						id: "inboxTable",
						viewName: sViewName,
						check: function (oTable) { return !oTable.getBusy() && rows(oTable).length > 0; },
						success: function (oTable) {
							Opa5.assert.strictEqual(rows(oTable)[0].invoiceNumber, sInvoiceNumber, "first row");
						}
					});
				},

				iShouldSeeControlVisible: function (sId, bVisible) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oControl) {
							return oControl.getVisible() === bVisible && (!bVisible || !!oControl.getDomRef());
						},
						success: function () {
							Opa5.assert.ok(true, sId + (bVisible ? " is visible" : " is hidden"));
						},
						errorMessage: sId + " visibility is not " + bVisible
					});
				},

				iShouldSeeTextContaining: function (sId, sText) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oControl) {
							var sValue = oControl.getText ? oControl.getText()
								: oControl.getNumber ? oControl.getNumber() : oControl.getTitle();
							return String(sValue).indexOf(sText) !== -1;
						},
						success: function () {
							Opa5.assert.ok(true, sId + " contains '" + sText + "'");
						},
						errorMessage: sId + " does not contain '" + sText + "'"
					});
				},

				/** Kwota niezależnie od locale: wszystkie cyfry zachowane (brak zaokrąglenia przez double). */
				iShouldSeeAmountDigits: function (sId, sDigits) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						check: function (oControl) {
							return String(oControl.getNumber()).replace(/\D/g, "") === sDigits;
						},
						success: function (oControl) {
							Opa5.assert.ok(true, sId + " shows every digit: " + oControl.getNumber());
						},
						errorMessage: sId + " does not show the digits " + sDigits
					});
				},

				iShouldSeeButtonDisabled: function (sId) {
					return this.waitFor({
						id: sId,
						viewName: sViewName,
						visible: false,
						success: function (oButton) {
							Opa5.assert.strictEqual(oButton.getEnabled(), false, sId + " is disabled");
							Opa5.assert.ok(/not available/i.test(oButton.getTooltip()), sId + " explains why");
						}
					});
				},

				iShouldSeeEmptyState: function (sKind) {
					return this.waitFor({
						id: "inboxTable",
						viewName: sViewName,
						check: function (oTable) {
							return !oTable.getBusy() && oTable.getModel("inbox").getProperty("/empty/kind") === sKind;
						},
						success: function () {
							Opa5.assert.ok(true, "empty state " + sKind);
						},
						errorMessage: "empty state " + sKind + " not shown"
					});
				},

				iShouldSeeOnlySameOriginRequests: function (iMinimum) {
					return this.waitFor({
						success: function () {
							var oContext = this.getContext();
							var sOrigin = Opa5.getWindow().location.origin;
							var aForeign = oContext.fetchCalls.filter(function (sUrl) {
								return new URL(sUrl, Opa5.getWindow().location.href).origin !== sOrigin;
							});
							Opa5.assert.ok(oContext.fetchCalls.length >= iMinimum, oContext.fetchCalls.length + " request(s) recorded");
							Opa5.assert.deepEqual(aForeign, [], "no request leaves the application origin");
							Opa5.assert.ok(oContext.fetchCalls.every(function (sUrl) {
								return !/ksef\.mf\.gov\.pl/i.test(sUrl);
							}), "no direct KSeF call from the browser");
						}
					});
				},

				iShouldSeeNoNetworkRequests: function () {
					return this.waitFor({
						success: function () {
							Opa5.assert.deepEqual(this.getContext().fetchCalls, [], "no HTTP request");
						}
					});
				},

				iShouldSeeDetails: function (sInvoiceNumber) {
					return this.waitFor({
						id: "inboxDetailsDialog",
						viewName: sViewName,
						searchOpenDialogs: true,
						check: function (oDialog) {
							return oDialog.isOpen() && !oDialog.getBusy();
						},
						success: function (oDialog) {
							Opa5.assert.ok(oDialog.getTitle().indexOf(sInvoiceNumber) !== -1, "details for " + sInvoiceNumber);
						},
						errorMessage: "details dialog for " + sInvoiceNumber + " not open"
					});
				}
			}
		}
	});
});
