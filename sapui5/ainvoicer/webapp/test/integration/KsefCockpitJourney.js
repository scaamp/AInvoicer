/*global QUnit*/

sap.ui.define([
	"sap/ui/test/opaQunit",
	"./pages/Main"
], function (opaTest) {
	"use strict";

	QUnit.module("KSeF Cockpit Journey");

	opaTest("Should open the KSeF view by default with demo data and keep Legacy hidden", function (Given, When, Then) {
		Given.iStartMyApp();

		Then.onTheViewPage.iShouldSeeKsefRows(8);
		Then.onTheViewPage.iShouldSeeControlVisible("invoiceTable", false);
		Then.onTheViewPage.iShouldSeeControlVisible("filterbar", false);
		Then.onTheViewPage.iShouldSeeControlVisible("ksefFilterBar", true);
		Then.onTheViewPage.iShouldSeeTextContaining("ksefModeStrip", "Demo mode");
		Then.onTheViewPage.iShouldSeeTextContaining("ksefTableTitle", "(8)");
	});

	opaTest("Should switch between All, Purchase, Sales and SAP FI (Legacy)", function (Given, When, Then) {
		When.onTheViewPage.iSelectView("viewTabPurchase");
		Then.onTheViewPage.iShouldSeeKsefRows(3).and.iShouldSeeOnlyDirection("INBOUND");

		When.onTheViewPage.iSelectView("viewTabSales");
		Then.onTheViewPage.iShouldSeeKsefRows(5).and.iShouldSeeOnlyDirection("OUTBOUND");
		Then.onTheViewPage.iShouldSeeButtonState("ksefSyncButton", false, "Synchronization downloads purchase invoices");

		When.onTheViewPage.iSelectView("viewTabLegacy");
		Then.onTheViewPage.iShouldSeeControlVisible("invoiceTable", true);
		Then.onTheViewPage.iShouldSeeControlVisible("ksefInvoiceTable", false);
		Then.onTheViewPage.iShouldSeeControlVisible("filterbar", true);
		Then.onTheViewPage.iShouldSeeControlVisible("ksefFilterBar", false);
		Then.onTheViewPage.iShouldSeeTextContaining("ksefModeStrip", "ZC_FI_ACDOCA");

		When.onTheViewPage.iSelectView("viewTabAll");
		Then.onTheViewPage.iShouldSeeKsefRows(8);
	});

	opaTest("Should filter and sort the KSeF table", function (Given, When, Then) {
		When.onTheViewPage.iExpandPanel("filterPanel");
		When.onTheViewPage.iSearchKsef("DEMO");
		Then.onTheViewPage.iShouldSeeKsefRows(4);

		When.onTheViewPage.iSelectView("viewTabSales");
		Then.onTheViewPage.iShouldSeeKsefRows(3);

		When.onTheViewPage.iClearKsefFilters();
		Then.onTheViewPage.iShouldSeeKsefRows(5);

		When.onTheViewPage.iSelectView("viewTabAll");
		When.onTheViewPage.iSortKsefBy("ksefColGrossAmount", true);
		Then.onTheViewPage.iShouldSeeFirstKsefInvoice("AINV-DEMO-2026-SD-BILL-0002");
		When.onTheViewPage.iSortKsefBy("ksefColIssueDate", false);
		Then.onTheViewPage.iShouldSeeFirstKsefInvoice("AINV-TEST-SEED-2026-FI-AP");
	});

	opaTest("Should derive action availability from direction, status and backend", function (Given, When, Then) {
		Then.onTheViewPage.iShouldSeeButtonState("ksefSendButton", false, "Select an invoice first");
		Then.onTheViewPage.iShouldSeeButtonState("ksefDetailsButton", false);
		Then.onTheViewPage.iShouldSeeButtonState("ksefSyncButton", false, "G-04");

		// OUTBOUND bez operacji: kwalifikuje się, ale CAP nie wystawia jeszcze akcji wysyłki.
		When.onTheViewPage.iSelectInvoice("AINV-TEST-SEED-2026-FI-AR");
		Then.onTheViewPage.iShouldSeeButtonState("ksefSendButton", false, "G-02");
		Then.onTheViewPage.iShouldSeeButtonState("ksefCheckStatusButton", false, "No CAP operation");
		Then.onTheViewPage.iShouldSeeButtonState("ksefDetailsButton", true);
		Then.onTheViewPage.iShouldSeeButtonState("ksefHistoryButton", true);

		// OUTBOUND z operacją NEW: wysyłka w toku, kontrola statusu (odczyt CAP) dostępna.
		When.onTheViewPage.iSelectInvoice("AINV-TEST-SEED-2026-SD-BILL");
		Then.onTheViewPage.iShouldSeeButtonState("ksefSendButton", false, "in progress");
		Then.onTheViewPage.iShouldSeeButtonState("ksefCheckStatusButton", true);
		When.onTheViewPage.iPressButton("ksefCheckStatusButton");
		Then.onTheViewPage.iShouldSeeKsefRows(8);

		// Zaakceptowana: brak ponownej wysyłki; UPO czeka na kontrakt CAP.
		When.onTheViewPage.iSelectInvoice("AINV-DEMO-2026-FI-AR-0002");
		Then.onTheViewPage.iShouldSeeButtonState("ksefSendButton", false, "already has KSeF number");
		Then.onTheViewPage.iShouldSeeButtonState("ksefDownloadUpoButton", false, "G-06");
		Then.onTheViewPage.iShouldSeeButtonState("ksefViewXmlButton", false, "G-05");

		// INBOUND: nigdy nie wysyłamy.
		When.onTheViewPage.iSelectInvoice("AINV-TEST-SEED-2026-MM-IV");
		Then.onTheViewPage.iShouldSeeButtonState("ksefSendButton", false, "OUTBOUND");
	});

	opaTest("Should show invoice details with items, references and operation history", function (Given, When, Then) {
		When.onTheViewPage.iSelectInvoice("AINV-DEMO-2026-FI-AR-0003");
		When.onTheViewPage.iPressButton("ksefDetailsButton");
		Then.onTheViewPage.iShouldSeeTheDetailsDialog("AINV-DEMO-2026-FI-AR-0003", 1, 1);
		Then.onTheViewPage.iShouldSeeDetailsTab("header");
		When.onTheViewPage.iPressButton("ksefDetailsCloseButton");

		When.onTheViewPage.iSelectInvoice("AINV-TEST-SEED-2026-FI-AR");
		When.onTheViewPage.iPressButton("ksefHistoryButton");
		Then.onTheViewPage.iShouldSeeTheDetailsDialog("AINV-TEST-SEED-2026-FI-AR", 3, 0);
		Then.onTheViewPage.iShouldSeeDetailsTab("history");
		When.onTheViewPage.iPressButton("ksefDetailsCloseButton");
	});

	opaTest("Should personalize visible columns", function (Given, When, Then) {
		Then.onTheViewPage.iShouldSeeColumnVisible("ksefColKsefEnvironment", false);
		When.onTheViewPage.iCallController(function (oController) {
			oController.onKsefOpenColumns();
			var aColumns = oController.getView().getModel("ksefView").getProperty("/columnList");
			aColumns.forEach(function (oColumn) {
				if (oColumn.key === "KsefEnvironment") {
					oColumn.visible = true;
				}
			});
			oController.getView().getModel("ksefView").setProperty("/columnList", aColumns);
		});
		When.onTheViewPage.iPressButton("ksefColumnsOk");
		Then.onTheViewPage.iShouldSeeColumnVisible("ksefColKsefEnvironment", true);
		When.onTheViewPage.iCallController(function (oController) {
			oController.onKsefColumnsReset();
		});
		Then.onTheViewPage.iShouldSeeColumnVisible("ksefColKsefEnvironment", false);
	});

	opaTest("Should report load errors instead of showing stale or fake data", function (Given, When, Then) {
		When.onTheViewPage.iCallController(function (oController) {
			this.getContext().originalLoad = oController._oKsefAdapter.loadInvoices;
			oController._oKsefAdapter.loadInvoices = function () {
				var oError = new Error("Backend contract G-01 is not available");
				oError.code = "BACKEND_MISSING";
				oError.gap = "G-01";
				return Promise.reject(oError);
			};
		});
		When.onTheViewPage.iPressButton("ksefRefreshButton");
		Then.onTheViewPage.iShouldSeeKsefRows(0);
		Then.onTheViewPage.iShouldSeeControlVisible("ksefErrorStrip", true);
		Then.onTheViewPage.iShouldSeeTextContaining("ksefErrorStrip", "G-01");

		When.onTheViewPage.iCallController(function (oController) {
			oController._oKsefAdapter.loadInvoices = this.getContext().originalLoad;
		});
		When.onTheViewPage.iPressButton("ksefRefreshButton");
		Then.onTheViewPage.iShouldSeeKsefRows(8);
		Then.onTheViewPage.iShouldSeeControlVisible("ksefErrorStrip", false);

		Then.iTeardownMyApp();
	});
});
