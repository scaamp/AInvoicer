sap.ui.define([], function () {
    "use strict";

    /**
     * Frontendowy kontrakt kokpitu KSeF.
     *
     * Źródła pól (Source):
     *  - RAP: SAP H01 ZAPI_AINV_INV_H_O4/0001, encja ZC_AINV_INV_H (+ _Items, _References),
     *         zweryfikowane z cap-java/srv/external/AinvoicerH01.edmx (import 2026-10-28).
     *  - CAP: CAP Java KsefService.KsefOperations / KsefInvoices (cap-java/srv/ksef-service.cds).
     *  - UI:  wartości wyliczane wyłącznie w przeglądarce (np. pochodny status KSeF).
     */
    var Source = Object.freeze({ RAP: "RAP", CAP: "CAP", UI: "UI" });

    var Direction = Object.freeze({ INBOUND: "INBOUND", OUTBOUND: "OUTBOUND" });

    var OriginProcess = Object.freeze({ FI_AP: "FI_AP", FI_AR: "FI_AR", MM_IV: "MM_IV", SD_BILL: "SD_BILL" });

    /** Klucze widoków przełącznika All / Purchase / Sales / Legacy. */
    var ViewKey = Object.freeze({ ALL: "ALL", PURCHASE: "PURCHASE", SALES: "SALES", LEGACY: "LEGACY" });

    /**
     * Statusy operacji KSeF. "confirmed" = stała występująca w kodzie CAP Java (KsefOperationsHandler,
     * SapKsefResultWriteback); "anticipated" = wartość oczekiwana od pracy Agenta A, jeszcze nie w kodzie.
     * Nieznany status jest pokazywany bez zmian ze stanem neutralnym — UI niczego nie zgaduje.
     */
    var OperationStatus = Object.freeze({
        NEW: { state: "Information", terminal: false, origin: "confirmed" },
        QUEUED: { state: "Information", terminal: false, origin: "anticipated" },
        PROCESSING: { state: "Warning", terminal: false, origin: "anticipated" },
        SUBMITTED: { state: "Warning", terminal: false, origin: "anticipated" },
        ACCEPTED: { state: "Success", terminal: true, origin: "confirmed" },
        REJECTED: { state: "Error", terminal: true, origin: "anticipated" },
        FAILED: { state: "Error", terminal: true, origin: "anticipated" },
        RECEIVED: { state: "Success", terminal: true, origin: "anticipated" }
    });

    /** Statusy wyliczane w UI, gdy CAP nie zwraca operacji dla faktury. */
    var DerivedStatus = Object.freeze({
        // RAP ma numer KSeF zapisany akcją ApplyKsefResult, a CAP nie zwrócił operacji (np. inny NIP operatora).
        RECORDED_IN_SAP: "RECORDED_IN_SAP",
        // Brak operacji CAP i brak numeru KSeF w SAP.
        NO_OPERATION: "NO_OPERATION"
    });

    var OperationType = Object.freeze({
        SEND_INVOICE: "SEND_INVOICE",
        CHECK_STATUS: "CHECK_STATUS",
        RECEIVE_INVOICE: "RECEIVE_INVOICE"
    });

    /**
     * Kolumny nowej tabeli. "defaultVisible" = zestaw domyślny; reszta w personalizacji.
     * "future" oznacza pole, którego obecny backend nie udostępnia publicznie dla UI.
     */
    var Columns = Object.freeze([
        { key: "InvoiceNumber", source: Source.RAP, defaultVisible: true },
        { key: "Direction", source: Source.RAP, defaultVisible: true },
        { key: "OriginProcess", source: Source.RAP, defaultVisible: true },
        { key: "CompanyCode", source: Source.RAP, defaultVisible: false },
        { key: "Seller", source: Source.RAP, defaultVisible: true },
        { key: "Buyer", source: Source.RAP, defaultVisible: true },
        { key: "IssueDate", source: Source.RAP, defaultVisible: true },
        { key: "GrossAmount", source: Source.RAP, defaultVisible: true },
        { key: "CurrencyCode", source: Source.RAP, defaultVisible: false },
        { key: "InvoiceStatus", source: Source.RAP, defaultVisible: true },
        { key: "KsefStatus", source: Source.CAP, defaultVisible: true },
        { key: "KsefNumber", source: Source.RAP, defaultVisible: true },
        { key: "KsefEnvironment", source: Source.RAP, defaultVisible: false },
        { key: "OperationReference", source: Source.CAP, defaultVisible: false },
        { key: "LastUpdate", source: Source.UI, defaultVisible: false }
    ]);

    /**
     * Pola dozwolone w filtrach generowanych dla nowej tabeli (lista biała; dotyczy także AI).
     * type służy do walidacji i konwersji wartości.
     */
    var FilterableFields = Object.freeze({
        InvoiceNumber: "string",
        CompanyCode: "string",
        IssueDate: "date",
        PostingDate: "date",
        GrossAmount: "number",
        CurrencyCode: "string",
        SellerNip: "string",
        BuyerNip: "string",
        SellerName: "string",
        BuyerName: "string",
        Direction: "string",
        OriginProcess: "string",
        KsefStatus: "string",
        KsefNumber: "string",
        InvoiceStatus: "string"
    });

    return {
        Source: Source,
        Direction: Direction,
        OriginProcess: OriginProcess,
        ViewKey: ViewKey,
        OperationStatus: OperationStatus,
        DerivedStatus: DerivedStatus,
        OperationType: OperationType,
        Columns: Columns,
        FilterableFields: FilterableFields,

        /** @returns {boolean} czy status operacji jest końcowy (znany i terminalny) */
        isTerminalStatus: function (sStatus) {
            var oInfo = OperationStatus[sStatus];
            return !!(oInfo && oInfo.terminal);
        },

        /** @returns {Object<string, boolean>} mapa klucz kolumny → widoczność domyślna */
        getDefaultColumnVisibility: function () {
            return Columns.reduce(function (oResult, oColumn) {
                oResult[oColumn.key] = oColumn.defaultVisible;
                return oResult;
            }, {});
        }
    };
});
