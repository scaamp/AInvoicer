sap.ui.define([
	"sap/ui/test/Opa5"
], function (Opa5) {
	"use strict";

	return Opa5.extend("integration.arrangements.Startup", {

		iStartMyApp: function (oOptionsParameter) {
			var oOptions = oOptionsParameter || {};

			// start the app with a minimal delay to make tests fast but still async to discover basic timing issues
			oOptions.delay = oOptions.delay || 50;

			// Każdy start z domyślną personalizacją kolumn kokpitu KSeF.
			try {
				window.localStorage.removeItem("ainvoicer.ksef.columns.v1");
			} catch (oError) {
				// brak localStorage nie wpływa na test
			}

			// start the app UI component
			this.iStartMyUIComponent({
				componentConfig: {
					name: "ainvoicer",
					async: true
				},
				hash: oOptions.hash,
				autoWait: oOptions.autoWait
			});
		}
	});
});
