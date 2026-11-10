sap.ui.define([], function () {
    "use strict";

    /**
     * Frontendowy kontrakt KSeF Inbox (UI5-10) — faktury zakupowe ODEBRANE z KSeF, które mogą jeszcze
     * nie istnieć w SAP. To inna domena niż zakładka PURCHASE kokpitu (faktury z modelu SAP RAP).
     *
     * Jawny read-only CAP OData `KsefService.InboundInvoiceOverview` jest lokalnie zweryfikowany w INT-02.
     * Edm.Decimal jest negocjowany jako tekst (IEEE754Compatible=true), CompanyCode nie jest polem dokumentu.
     * Wszystkie pola poza `id` są opcjonalne; UI obsługuje `null`/brak jawnie i niczego nie zgaduje.
     */

    /** Tryby danych. Brak lub nieznana wartość = DISABLED (fail-closed, bez HTTP i bez mocków). */
    var Mode = Object.freeze({ MOCK: "mock", CAP: "cap", DISABLED: "disabled" });

    var Environment = Object.freeze({ TEST: "TEST", DEMO: "DEMO", PROD: "PROD" });

    /**
     * Słowniki statusów (wartości DTO). `state` = sap.ui.core.ValueState do prezentacji.
     * Wartość spoza słownika jest wyświetlana dosłownie ze stanem neutralnym ("None").
     */
    var IngestionStatus = Object.freeze({
        PENDING: { state: "Information", icon: "sap-icon://pending" },
        QUARANTINED: { state: "Error", icon: "sap-icon://error" },
        STORAGE_FAILED: { state: "Error", icon: "sap-icon://error" },
        DISCOVERED: { state: "Information", icon: "sap-icon://search" },      // tylko metadane, XML jeszcze nie pobrany
        STORED: { state: "Success", icon: "sap-icon://download" },              // XML pobrany i trwale zapisany w CAP
        DOWNLOAD_FAILED: { state: "Error", icon: "sap-icon://error" }           // pobranie nieudane (backend ponowi wg polityki)
    });

    var ParseStatus = Object.freeze({
        NOT_PARSED: { state: "Information", icon: "sap-icon://pending" },
        PENDING: { state: "Information", icon: "sap-icon://pending" },
        PARSED: { state: "Success", icon: "sap-icon://accept" },
        UNSUPPORTED_SCHEMA: { state: "Warning", icon: "sap-icon://message-warning" }, // np. FA(2) — zapisany, nieprzetworzony
        INVALID: { state: "Error", icon: "sap-icon://error" }                    // XML odrzucony przez bezpieczny parser
    });

    var IntegrityStatus = Object.freeze({
        FAILED: { state: "Error", icon: "sap-icon://error" },
        PENDING: { state: "Information", icon: "sap-icon://pending" },
        VERIFIED: { state: "Success", icon: "sap-icon://shield" },               // SHA-256 bajtów = invoiceHash z metadanych
        MISMATCH: { state: "Error", icon: "sap-icon://error" },                  // skrót niezgodny z metadanymi
        CONFLICT: { state: "Error", icon: "sap-icon://alert" }                   // ta sama tożsamość KSeF, inna treść
    });

    var SapPostingStatus = Object.freeze({
        NOT_POSTED: { state: "None", icon: "sap-icon://pending" },
        POSTED: { state: "Success", icon: "sap-icon://accept" },                 // przewidywany; brak numeru dokumentu w kontrakcie
        POSTING_FAILED: { state: "Error", icon: "sap-icon://error" }             // przewidywany
    });

    var BuyerIdentifierType = Object.freeze({ Nip: "Nip", VatUe: "VatUe", Other: "Other", None: "None" });

    /** Pola DTO (whitelist $select). Kolejność = kolejność w dokumentacji kontraktu. */
    var Fields = Object.freeze([
        "id", "environment", "ksefNumber", "invoiceNumber", "issueDate", "acquisitionDate",
        "sellerNip", "sellerName", "buyerIdentifierType", "buyerIdentifierValue", "buyerName",
        "currency", "grossAmount", "invoiceType", "formCode",
        "ingestionStatus", "parseStatus", "integrityStatus", "sapPostingStatus", "xmlAvailable"
    ]);

    /** Pola, po których można sortować (lista biała dla $orderby i sortowania mock). */
    var SortableFields = Object.freeze({
        acquisitionDate: "datetime",
        issueDate: "date",
        invoiceNumber: "string",
        ksefNumber: "string",
        sellerNip: "string",
        sellerName: "string",
        grossAmount: "decimal",
        currency: "string"
    });

    var DefaultSort = Object.freeze({ field: "acquisitionDate", descending: true });

    /** Maksymalne długości tekstów wejściowych filtrów i pól DTO (zgodne z KSEF_INBOUND_ARCHITECTURE §12). */
    var MaxLength = Object.freeze({
        invoiceNumber: 256, ksefNumber: 64, sellerNip: 10, sellerName: 512, buyerIdentifierValue: 256,
        buyerName: 512, currency: 3, status: 32, formCode: 64, invoiceType: 32
    });

    /**
     * Akcje przyszłe (podgląd XML, duplikaty, powiązanie FI/MM, zatwierdzanie/księgowanie).
     * Backend nie ma tych operacji — adapter zwraca je zawsze jako niedostępne, niezależnie od manifestu.
     */
    var FutureActions = Object.freeze(["viewXml", "checkDuplicate", "linkSapDocument", "approveAndPost"]);

    return {
        Mode: Mode,
        Environment: Environment,
        IngestionStatus: IngestionStatus,
        ParseStatus: ParseStatus,
        IntegrityStatus: IntegrityStatus,
        SapPostingStatus: SapPostingStatus,
        BuyerIdentifierType: BuyerIdentifierType,
        Fields: Fields,
        SortableFields: SortableFields,
        DefaultSort: DefaultSort,
        MaxLength: MaxLength,
        FutureActions: FutureActions,

        /**
         * Bezpieczne sprawdzenie klucza słownika: tylko własne pola (np. "constructor"/"__proto__" z wejścia
         * użytkownika lub backendu nie mogą „trafić” w prototyp Object).
         */
        has: function (mDictionary, sKey) {
            return !!mDictionary && typeof sKey === "string" && Object.prototype.hasOwnProperty.call(mDictionary, sKey);
        },

        /** Słownik statusów dla nazwy pola DTO (np. "integrityStatus"). */
        statusDictionary: function (sField) {
            return {
                ingestionStatus: IngestionStatus,
                parseStatus: ParseStatus,
                integrityStatus: IntegrityStatus,
                sapPostingStatus: SapPostingStatus
            }[sField] || null;
        }
    };
});
