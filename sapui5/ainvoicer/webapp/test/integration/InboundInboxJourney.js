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

	// BETA-01: konfiguracja Inbox jak w artefakcie `build:cf:inbox-beta` (viewXml tylko z --with-xml-download).
	function betaConfig(bXml) {
		return { dataMode: "cap", pageSize: 20, environments: ["TEST"],
			cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview", readTimeoutMs: 2000 },
			capabilities: { viewXml: bXml, checkDuplicate: false, linkSapDocument: false, approveAndPost: false } };
	}
	var BETA_XML_ID = "20000000-0000-4000-8000-000000000001";
	var BETA_NO_XML_ID = "20000000-0000-4000-8000-000000000002";
	var XSS_INVOICE = "<img src=x onerror=\"window.__inboxXss=1\">FV/XSS";
	var XSS_SELLER = "<script>window.__inboxXss=2</script>Seller";

	function betaDto(sId, sNumber, bXml, sSeller) {
		return { id: sId, environment: "TEST", ksefNumber: "1234567802-20261110-0000000000" + sId.slice(-2) + "-AA",
			invoiceNumber: sNumber, issueDate: "2026-11-09", acquisitionDate: "2026-11-10T10:00:00Z", sellerNip: "1234567802",
			sellerName: sSeller || "Beta supplier", buyerIdentifierType: "Nip", buyerIdentifierValue: "1234567802", currency: "PLN",
			grossAmount: "123.45", ingestionStatus: "STORED", parseStatus: "PARSED", integrityStatus: "VERIFIED",
			sapPostingStatus: "NOT_POSTED", xmlAvailable: bXml };
	}

	/** Atrapa backendu CAP/AppRoutera dla trybu beta: odpowiedź zależy od oContext.betaMode (bez sieci). */
	function betaRoute(sMode, sUrl) {
		var aRows = [betaDto(BETA_XML_ID, "FV/BETA/XML", true), betaDto(BETA_NO_XML_ID, "FV/BETA/NOXML", false)];
		if (sUrl.indexOf("InboundInvoiceXml(") !== -1) {
			return sMode === "xml403" ? { status: 403, body: { error: { message: "Forbidden" } } }
				: { status: 200, contentType: "application/xml", text: "<?xml version=\"1.0\"?><Faktura/>" };
		}
		if (sUrl.indexOf("InboundInvoiceOverview(") !== -1) {
			return { status: 200, body: aRows.filter(function (oRow) { return sUrl.indexOf(oRow.id) !== -1; })[0] };
		}
		switch (sMode) {
			case "401": return { status: 401 };
			case "403": return { status: 403, body: { error: { message: "Forbidden" } } };
			case "500": return { status: 500, body: { error: { message: "java.lang.IllegalStateException at com.example" } } };
			case "html": return { status: 200, contentType: "text/html", text: "<html>login</html>" };
			case "empty": return { status: 200, body: { "@odata.count": 0, value: [] } };
			case "xss": return { status: 200, body: { "@odata.count": 1, value: [betaDto(BETA_XML_ID, XSS_INVOICE, true, XSS_SELLER)] } };
			default: return { status: 200, body: { "@odata.count": 2, value: aRows } };
		}
	}

	function betaFetch(oContext) {
		return function (sUrl, oOptions) {
			oContext.betaCalls.push({ url: sUrl, method: oOptions.method });
			var oSpec = betaRoute(oContext.betaMode, sUrl);
			var sType = oSpec.contentType || "application/json";
			return Promise.resolve({
				ok: oSpec.status >= 200 && oSpec.status < 300,
				status: oSpec.status,
				headers: { get: function (sName) { return /content-type/i.test(sName) ? sType : null; } },
				json: function () { return oSpec.body ? Promise.resolve(oSpec.body) : Promise.reject(new SyntaxError("no json")); },
				blob: function () { return Promise.resolve(new Blob([oSpec.text || ""], { type: sType })); }
			});
		};
	}

	function setupBeta(oController, oContext, sMode, bXml) {
		oContext.betaCalls = [];
		oContext.saved = [];
		oContext.betaMode = sMode;
		oController._setupAdapter(betaConfig(bXml), betaFetch(oContext));
		oController._saveFile = function (oBlob, sName) {
			oContext.saved.push({ name: sName, size: oBlob.size });
		};
		oController._load(false);
	}

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
		// Mock/domyślny build: brak przycisku pobrania XML, jawny powód zamiast atrapy.
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDownloadXmlButton", false);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDownloadXmlInfo", "not available in this version");
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

	opaTest("BETA-01: CAP access states (401 sign-in, login page, 403, 5xx, empty) clear data and explain the cause", function (Given, When, Then) {
		When.onTheInboxPage.iCallController(function (oController) {
			setupBeta(oController, this.getContext(), "401", false);
		});
		Then.onTheInboxPage.iShouldSeeRows(0);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "session has expired");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxSignInAgainLink", true);
		Then.onTheInboxPage.iShouldSeeEmptyState("error");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDemoStrip", false);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxModeBadge", "Read-only");

		When.onTheInboxPage.iCallController(function (oController) {
			this.getContext().betaMode = "html";
			oController._load(false);
		});
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "Sign in again");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxSignInAgainLink", true);
		Then.onTheInboxPage.iShouldSeeRows(0);

		When.onTheInboxPage.iCallController(function (oController) {
			this.getContext().betaMode = "403";
			oController._load(false);
		});
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "not authorized to read the KSeF Inbox");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxSignInAgainLink", false);

		When.onTheInboxPage.iCallController(function (oController) {
			this.getContext().betaMode = "500";
			oController._load(false);
		});
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxErrorStrip", "temporarily unavailable (HTTP 500)");
		When.onTheInboxPage.iCallController(function (oController) {
			Opa5.assert.strictEqual(oController.getView().getModel("inbox").getProperty("/error").indexOf("java.lang"), -1,
				"no server internals shown for 5xx");
		});

		When.onTheInboxPage.iCallController(function (oController) {
			this.getContext().betaMode = "empty";
			oController.onRefresh();
		});
		Then.onTheInboxPage.iShouldSeeRows(0, 0);
		Then.onTheInboxPage.iShouldSeeEmptyState("noData");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxErrorStrip", false);
	});

	opaTest("BETA-01: invoice values from XML are rendered as text in the list", function (Given, When, Then) {
		When.onTheInboxPage.iCallController(function (oController) {
			setupBeta(oController, this.getContext(), "xss", false);
		});
		Then.onTheInboxPage.iShouldSeeRows(1, 1);
		Then.onTheInboxPage.iShouldSeeLiteralTextInTable(XSS_INVOICE);
		Then.onTheInboxPage.iShouldSeeLiteralTextInTable(XSS_SELLER);
	});

	opaTest("BETA-01: Download XML only with viewXml and xmlAvailable; URL from the UUID; errors stay in the dialog", function (Given, When, Then) {
		// Beta bez --with-xml-download: brak przycisku nawet dla xmlAvailable=true.
		When.onTheInboxPage.iCallController(function (oController) {
			setupBeta(oController, this.getContext(), "ok", false);
		});
		Then.onTheInboxPage.iShouldSeeRows(2, 2);
		When.onTheInboxPage.iOpenInvoice("FV/BETA/XML");
		Then.onTheInboxPage.iShouldSeeDetails("FV/BETA/XML");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDownloadXmlButton", false);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDownloadXmlInfo", "not available in this version");
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");

		// Beta z --with-xml-download.
		When.onTheInboxPage.iCallController(function (oController) {
			setupBeta(oController, this.getContext(), "ok", true);
		});
		Then.onTheInboxPage.iShouldSeeRows(2, 2);
		When.onTheInboxPage.iOpenInvoice("FV/BETA/NOXML");
		Then.onTheInboxPage.iShouldSeeDetails("FV/BETA/NOXML");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDownloadXmlButton", false);
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDownloadXmlInfo", "not stored");
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");

		When.onTheInboxPage.iOpenInvoice("FV/BETA/XML");
		Then.onTheInboxPage.iShouldSeeDetails("FV/BETA/XML");
		Then.onTheInboxPage.iShouldSeeControlVisible("inboxDownloadXmlButton", true);
		When.onTheInboxPage.iPressButton("inboxDownloadXmlButton");
		Then.onTheInboxPage.iShouldSeeDownloadedFile("KSeF-1234567802-20261110-000000000001-AA.xml",
			"/odata/v4/KsefService/InboundInvoiceXml(" + BETA_XML_ID + ")/content");

		When.onTheInboxPage.iRunInContext(function () {
			this.getContext().betaMode = "xml403";
		});
		When.onTheInboxPage.iPressButton("inboxDownloadXmlButton");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsErrorStrip", "could not be downloaded");
		Then.onTheInboxPage.iShouldSeeTextContaining("inboxDetailsErrorStrip", "not authorized");
		When.onTheInboxPage.iRunInContext(function () {
			Opa5.assert.strictEqual(this.getContext().saved.length, 1, "failed download saves no file");
		});
		When.onTheInboxPage.iPressButton("inboxDetailsCloseButton");

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
