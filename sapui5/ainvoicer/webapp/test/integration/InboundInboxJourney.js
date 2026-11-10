/*global QUnit*/

sap.ui.define([
	"sap/ui/test/opaQunit",
	"sap/ui/test/Opa5",
	"./pages/Main",
	"./pages/InboundInbox"
], function (opaTest, Opa5) {
	"use strict";

	var CAP_FUTURE = { dataMode: "cap", pageSize: 20, cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview", readTimeoutMs: 300 } };
	var LONG_INVOICE = "FV/2026/11/SUPPLIER-GAMMA/BRANCH-KRAKOW/PROJECT-0001-EXTENDED-REFERENCE-NUMBER-FOR-LONG-LAYOUT-TEST/ANNEX-A";

	/** fetch, który nigdy nie odpowiada (symulacja braku odpowiedzi serwera); respektuje abort. */
	function hangingFetch(aCalls) {
		return function (sUrl, oOptions) {
			aCalls.push(sUrl);
			return new Promise(function (resolve, reject) {
				oOptions.signal.addEventListener("abort", function () { reject(new Error("aborted")); });
			});
		};
	}

	QUnit.module("KSeF Inbox Journey");

	opaTest("Should open the KSeF Inbox from the cockpit with clearly marked demo data", function (Given, When, Then) {
		Given.iStartMyApp();
		Then.onTheViewPage.iShouldSeeKsefRows(8);

		When.onTheInboxPage.iRecordNetworkRequests();
		When.onTheViewPage.iPressButton("ksefInboxButton");

		Then.onTheInboxPage.iShouldSeeTheInbox();
		Then.onTheInboxPage.iShouldSeeRows(20, 26);
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDemoStrip", true);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDemoStrip", "DEMO DATA");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxModeBadge", "Demo data");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxScopeStrip", "different list than the Purchase Invoices tab");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxTableTitle", "(20 of 26)");
		Then.onTheInboxPage.iShouldSeeOnlySameOriginRequests(1);
		When.onTheInboxPage.iStopRecordingNetworkRequests();
	});

	opaTest("Should load more pages progressively", function (Given, When, Then) {
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxLoadMoreButton", true);
		When.onTheInboxPage.iPressButton("inboxLoadMoreButton");
		Then.onTheInboxPage.iShouldSeeRows(26, 26);
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxLoadMoreBar", false);
	});

	opaTest("Should filter, validate filters and show the no-match state", function (Given, When, Then) {
		When.onTheInboxPage.iEnterFilter("inboxInvoiceNumberFilter", "kor/");
		Then.onTheInboxPage.iShouldSeeRows(1, 1);
		Then.onTheInboxPage.iShouldSeeFirstInvoice("KOR/7/2026");

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iSetFilterAndSearch("integrityStatuses", ["CONFLICT", "MISMATCH"]);
		Then.onTheInboxPage.iShouldSeeRows(2, 2);

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iSetFilterAndSearch("environment", "DEMO");
		Then.onTheInboxPage.iShouldSeeRows(2, 2);

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iEnterFilter("inboxSellerNipFilter", "abc");
		Then.onTheInboxPage.iShouldSeeRows(0);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "Seller NIP may contain only digits");

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iEnterFilter("inboxInvoiceNumberFilter", "NO-SUCH-INVOICE");
		Then.onTheInboxPage.iShouldSeeRows(0, 0);
		Then.onTheInboxPage.iShouldSeeEmptyState("noMatch");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxErrorStrip", false);

		When.onTheInboxPage.iClearFilters();
		Then.onTheInboxPage.iShouldSeeRows(20, 26);
	});

	opaTest("Should sort on the full decimal amount and through the sort dialog", function (Given, When, Then) {
		When.onTheInboxPage.iCallController(function (oController) {
			oController._applySort("grossAmount", true);
		});
		Then.onTheInboxPage.iShouldSeeFirstInvoice(LONG_INVOICE);

		When.onTheInboxPage.iPressButton("inboxSortButton");
		When.onTheInboxPage.iConfirmSortDialog("invoiceNumber", false);
		Then.onTheInboxPage.iShouldSeeFirstInvoice("FV/100/2026");
	});

	opaTest("Should show details with statuses, conflicts and disabled future actions", function (Given, When, Then) {
		When.onTheInboxPage.iSetFilterAndSearch("integrityStatuses", ["CONFLICT"]);
		When.onTheInboxPage.iOpenInvoice("FV/105/2026");
		Then.onTheInboxPage.iShouldSeeDetails("FV/105/2026");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDetailsConflictStrip", true);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsIntegrity", "Integrity conflict");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsSap", "Not in SAP");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsSapDocument", "No SAP document reference");
		Then.onTheInboxPage.iShouldSeeButtonDisabled("inboxViewXmlButton");
		Then.onTheInboxPage.iShouldSeeButtonDisabled("inboxCheckDuplicateButton");
		Then.onTheInboxPage.iShouldSeeButtonDisabled("inboxLinkSapDocumentButton");
		Then.onTheInboxPage.iShouldSeeButtonDisabled("inboxApproveAndPostButton");
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iSetFilterAndSearch("ingestionStatuses", ["DISCOVERED"]);
		When.onTheInboxPage.iOpenInvoice("FV/110/2026");
		Then.onTheInboxPage.iShouldSeeDetails("FV/110/2026");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDetailsIssuesStrip", true);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsIssuesStrip", "Gross amount is missing");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsGrossAmount", "—");
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iEnterFilter("inboxKsefNumberFilter", "000000000022");
		When.onTheInboxPage.iOpenInvoiceWhere(function (oRow) { return oRow.invoiceNumber === null; }, "invoice without number");
		Then.onTheInboxPage.iShouldSeeDetails("—");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsIssuesStrip", "Invoice number is missing");
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");

		When.onTheInboxPage.iClearFilters();
		When.onTheInboxPage.iEnterFilter("inboxKsefNumberFilter", "4444444444-20261107");
		When.onTheInboxPage.iOpenInvoice(LONG_INVOICE);
		Then.onTheInboxPage.iShouldSeeDetails("SUPPLIER-GAMMA");
		Then.onTheInboxPage.iShouldSeeAmountDigits("inboxDetailsGrossAmount", "9876543210987654");
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");
		When.onTheInboxPage.iClearFilters();
	});

	opaTest("Should report read errors and missing server response without stale or substitute data", function (Given, When, Then) {
		When.onTheInboxPage.iCallController(function (oController) {
			this.getContext().originalLoadPage = oController._oAdapter.loadPage;
			oController._oAdapter.loadPage = function () {
				var oError = new Error("INBOX_NETWORK_ERROR");
				oError.code = "NETWORK";
				return Promise.reject(oError);
			};
		});
		When.onTheInboxPage.iPressButton("inboxRefreshButton");
		Then.onTheInboxPage.iShouldSeeRows(0);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "cannot be reached");
		Then.onTheInboxPage.iShouldSeeEmptyState("error");

		When.onTheInboxPage.iCallController(function (oController) {
			oController._oAdapter.loadPage = this.getContext().originalLoadPage;
		});
		When.onTheInboxPage.iPressButton("inboxRefreshButton");
		Then.onTheInboxPage.iShouldSeeRows(20, 26);
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxErrorStrip", false);

		// Przyszły CAP bez odpowiedzi: timeout, brak przejścia na mock.
		When.onTheInboxPage.iCallController(function (oController) {
			this.getContext().hangingCalls = [];
			oController._setupAdapter(CAP_FUTURE, hangingFetch(this.getContext().hangingCalls));
			oController._load(false);
		});
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "did not respond in time");
		Then.onTheInboxPage.iShouldSeeRows(0);
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDemoStrip", false);

		// CAP bez skonfigurowanej encji (stan przed INT-02): jawna informacja, bez HTTP i bez danych demo.
		When.onTheInboxPage.iCallController(function (oController) {
			var aCalls = this.getContext().hangingCalls = [];
			oController._setupAdapter({ dataMode: "cap", cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: null } }, hangingFetch(aCalls));
			oController._load(false);
		});
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "INBOX-OVERVIEW");
		Then.onTheInboxPage.iShouldSeeRows(0);
		When.onTheInboxPage.iCallController(function () {
			Opa5.assert.deepEqual(this.getContext().hangingCalls, [], "no request without a configured entity set");
		});
	});

	opaTest("Should keep the Inbox disabled without HTTP when no backend is configured (Cloud Foundry variant)", function (Given, When, Then) {
		When.onTheInboxPage.iRecordNetworkRequests();
		When.onTheInboxPage.iCallController(function (oController) {
			oController._setupAdapter({ dataMode: "disabled", mock: { invoicesPath: "ainvoicer/localService/ksef/inbound/InboundInvoices.json" } });
			oController._onRouteMatched();
			oController.onRefresh();
		});
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxUnavailable", true);
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxContent", false);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxModeBadge", "Not available");
		Then.onTheInboxPage.iShouldSeeNoNetworkRequests();
		When.onTheInboxPage.iStopRecordingNetworkRequests();

		When.onTheInboxPage.iCallController(function (oController) {
			oController._setupAdapter(oController.getOwnerComponent().getManifestEntry("/sap.ui5/config/ksefInbox"));
			oController._load(false);
		});
		Then.onTheInboxPage.iShouldSeeRows(20, 26);
	});

	opaTest("Should navigate back to the cockpit with its state intact", function (Given, When, Then) {
		When.onTheInboxPage.iPressButton("inboxUnavailableBackButton");
		Then.onTheViewPage.iShouldSeeThePageView();
		Then.onTheViewPage.iShouldSeeKsefRows(8);
		Then.iTeardownMyApp();
	});
});
