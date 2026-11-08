/*global QUnit*/

sap.ui.define([
	"ainvoicer/controller/Main.controller",
	"sap/ui/model/json/JSONModel"
], function (Controller, JSONModel) {
	"use strict";

	QUnit.module("Main Controller");

	QUnit.test("I should test the Main controller", function (assert) {
		// onInit wymaga widoku i komponentu — pełną inicjalizację sprawdzają testy OPA.
		var oAppController = new Controller();
		assert.ok(oAppController);
	});

	QUnit.test("keeps all legacy AI, voice, scanner and FI handlers next to the KSeF cockpit", function (assert) {
		var oController = new Controller();
		[
			// AI Assistant + historia + voice input
			"onAiCommandExecute", "_processAiQuery", "onContextChange", "onToggleHistory", "onVoiceInput", "onShowFiltersDialog",
			"_updateHistoryList", "onPlayTTS",
			// Legacy FI: filtry, Add/Copy/Delete
			"onSearch", "onClear", "onAddInvoice", "onSaveDocument", "onCopyInvoice", "onDeleteInvoice",
			// Scanner
			"handleFileChange", "handleAnalyzeInvoice", "_sendToNodeJs", "_fillFormWithAIData",
			// Kokpit KSeF
			"onKsefViewSelect", "onKsefSearch", "onKsefRefresh", "onKsefSend", "onKsefCheckStatus", "onKsefViewDetails",
			"onKsefHistory", "onKsefOpenColumns", "onKsefScan", "_applyAiResultToKsef"
		].forEach(function (sMethod) {
			assert.strictEqual(typeof oController[sMethod], "function", sMethod);
		});
	});

	QUnit.test("synchronous RAP filter validation clears busy and reports the error without HTTP", function (assert) {
		var controller = new Controller();
		var view = new JSONModel({ busy: false });
		var data = new JSONModel({ invoices: [{ InvoiceUUID: "old" }] });
		controller._ksefViewModel = function () { return view; };
		controller.getView = function () { return { getModel: function () { return data; } }; };
		controller._ksefServerQuery = function () { throw new Error("Currency is required"); };
		controller._ksefText = function (key, args) { return args[0]; };
		controller._ksefSetSelected = function () {};
		controller._oKsefAdapter = { loadInvoices: function () { assert.ok(false, "HTTP must not be attempted"); } };
		return controller._ksefLoad(0).then(function () {
			assert.strictEqual(view.getProperty("/busy"), false);
			assert.strictEqual(view.getProperty("/error"), "Currency is required");
			assert.deepEqual(data.getProperty("/invoices"), []);
		});
	});

	QUnit.test("Legacy view stays unavailable when the deployment has no Legacy route (legacy.enabled=false)", function (assert) {
		var controller = new Controller();
		var view = new JSONModel({ viewKey: "ALL", legacyMode: false, legacyAvailable: false });
		var applied = 0;
		controller._ksefViewModel = function () { return view; };
		controller._isRapMode = function () { return false; };
		controller._ksefApplyFilters = function () { applied++; };
		controller._ksefUpdateActions = function () {};
		controller._ksefSetView("LEGACY");
		assert.strictEqual(view.getProperty("/viewKey"), "ALL");
		assert.strictEqual(view.getProperty("/legacyMode"), false, "Legacy table never becomes visible");
		assert.strictEqual(applied, 1, "KSeF list is shown instead");

		view.setProperty("/legacyAvailable", true);
		controller._ksefSetView("LEGACY");
		assert.strictEqual(view.getProperty("/legacyMode"), true, "development default keeps Legacy");
	});

});
