/*global QUnit*/

sap.ui.define([
	"sap/ui/test/opaQunit",
	"./pages/Main"
], function (opaTest) {
	"use strict";

	var FILTER_RESPONSE = {
		description: "Showing invoices above 2900 PLN",
		filters: [
			{ path: "AmountDocument", operator: "GT", value1: "2900" },
			{ path: "Vendor", operator: "EQ", value1: "888888" }
		]
	};

	QUnit.module("AI Assistant and Scanner Journey");

	opaTest("Should keep the AI Assistant controls", function (Given, When, Then) {
		Given.iStartMyApp();
		When.onTheViewPage.iExpandPanel("aiCommandPanel");

		Then.onTheViewPage.iShouldSeeControlVisible("aiCommandPanel", true);
		Then.onTheViewPage.iShouldSeeControlVisible("aiCommandInput", true);
		Then.onTheViewPage.iShouldSeeControlVisible("contextSelector", true);
		Then.onTheViewPage.iShouldSeeControlVisible("commandHistoryList", true);
	});

	opaTest("Should apply the existing /filter result to the KSeF table through the safe adapter", function (Given, When, Then) {
		When.onTheViewPage.iStubAiServices({
			"/filter": { body: FILTER_RESPONSE },
			"/speech": { reject: "TTS disabled in tests" }
		});
		When.onTheViewPage.iAskTheAiAssistant("pokaż faktury powyżej 2900 zł");

		Then.onTheViewPage.iShouldSeeAiRequestCount(1);
		Then.onTheViewPage.iShouldSeeKsefRows(3);
		Then.onTheViewPage.iShouldSeeLatestAiAnswerContaining(["GrossAmount GT 2900", "approximate mapping from AmountDocument", "Vendor (unsupportedField)"]);
		Then.onTheViewPage.iShouldSeeNoOpenBusyDialog();

		When.onTheViewPage.iClearKsefFilters();
		Then.onTheViewPage.iShouldSeeKsefRows(8);
	});

	opaTest("Should keep the Legacy AI Search on ZC_FI_ACDOCA", function (Given, When, Then) {
		When.onTheViewPage.iSelectView("viewTabLegacy");
		When.onTheViewPage.iStubAiServices({
			"/filter": { body: { description: "Company 1000", filters: [{ path: "CompanyCode", operator: "EQ", value1: "1000" }] } },
			"/speech": { reject: "TTS disabled in tests" }
		});
		When.onTheViewPage.iAskTheAiAssistant("company 1000");

		Then.onTheViewPage.iShouldSeeLegacyTableFiltered();
		Then.onTheViewPage.iShouldSeeLatestAiAnswerContaining(["Company 1000"]);
		Then.onTheViewPage.iShouldSeeNoOpenBusyDialog();

		When.onTheViewPage.iSelectView("viewTabAll");
		Then.onTheViewPage.iShouldSeeKsefRows(8);
	});

	opaTest("Should close the busy dialog and explain unsupported contexts and service errors", function (Given, When, Then) {
		When.onTheViewPage.iSelectAiContext("help");
		When.onTheViewPage.iAskTheAiAssistant("how do I send an invoice?");
		Then.onTheViewPage.iShouldSeeLatestAiAnswerContaining(["not supported yet"]);
		Then.onTheViewPage.iShouldSeeNoOpenBusyDialog();

		When.onTheViewPage.iSelectAiContext("search");
		When.onTheViewPage.iStubAiServices({ "/filter": { status: 500, body: {} } });
		When.onTheViewPage.iAskTheAiAssistant("anything");
		Then.onTheViewPage.iShouldSeeLatestAiAnswerContaining(["could not be reached", "HTTP 500"]);
		Then.onTheViewPage.iShouldSeeNoOpenBusyDialog();
		Then.onTheViewPage.iShouldSeeKsefRows(8);

		When.onTheViewPage.iRestoreAiServices();
	});

	opaTest("Should open the existing Scanner dialog and map AI output to the Legacy FI form", function (Given, When, Then) {
		When.onTheViewPage.iPressButton("ksefScanButton");
		Then.onTheViewPage.iShouldSeeTheScannerDialog();

		// Ten sam kontrakt odpowiedzi /analyze co dotychczas (pola ZFI_ACDOCA).
		When.onTheViewPage.iCallController(function (oController) {
			oController.oBusyDialog = { close: function () {} };
			oController._fillFormWithAIData({
				CompanyCode: "1234", FiscalYear: "2024", DocumentNo: "1111", LineItem: "001",
				AmountDocument: "1230.00", AmountLocal: "1230.00", CurrencyCode: "PLN", Vendor: "888888"
			});
		});
		Then.onTheViewPage.iShouldSeeScannerFormValue("/DocumentNo", "1111");
		Then.onTheViewPage.iShouldSeeScannerFormValue("/AmountDocument", "1230.00");
		Then.onTheViewPage.iShouldSeeScannerFormValue("/DebitCredit", "D");

		When.onTheViewPage.iCallController(function (oController) {
			oController.onCancelDialog();
		});

		Then.iTeardownMyApp();
	});
});
