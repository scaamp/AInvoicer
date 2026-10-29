/*global QUnit*/

sap.ui.define([
	"ainvoicer/controller/Main.controller"
], function (Controller) {
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

});
