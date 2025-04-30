sap.ui.define([
    "sap/ui/core/mvc/Controller"
], (Controller) => {
    "use strict";

    return Controller.extend("ainvoicer.controller.Main", {
        onInit() {
            this.oView = this.getView();
            
        },

        onPressAddButton(oEvent)
        {
            console.log("xD");
        }
    });
});