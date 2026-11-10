sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundContract"
], function (InboundContract) {
    "use strict";

    var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    // Postać KSeF 2.0 (35 znaków): NIP-RRRRMMDD-12 HEX-2 HEX CRC. UI tylko oznacza format — CRC weryfikuje backend.
    var KSEF_NUMBER_PATTERN = /^\d{10}-\d{8}-[0-9A-F]{12}-[0-9A-F]{2}$/;
    var ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
    var ISO_DATETIME_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;
    // Edm.Decimal jako tekst (IEEE754Compatible=true): bez wykładnika, separatora tysięcy i przecinka.
    var DECIMAL_PATTERN = /^-?\d{1,18}(\.\d{1,6})?$/;
    var STATUS_PATTERN = /^[A-Z][A-Z0-9_]{0,39}$/;
    var CURRENCY_PATTERN = /^[A-Z]{3}$/;

    /** Kody problemów danych wiersza; UI pokazuje je w szczegółach (bez zgadywania brakujących wartości). */
    var Issue = Object.freeze({
        MISSING_INVOICE_NUMBER: "MISSING_INVOICE_NUMBER",
        MISSING_KSEF_NUMBER: "MISSING_KSEF_NUMBER",
        NONSTANDARD_KSEF_NUMBER: "NONSTANDARD_KSEF_NUMBER",
        MISSING_ISSUE_DATE: "MISSING_ISSUE_DATE",
        MISSING_SELLER_NIP: "MISSING_SELLER_NIP",
        MISSING_AMOUNT: "MISSING_AMOUNT",
        INVALID_AMOUNT: "INVALID_AMOUNT",
        MISSING_CURRENCY: "MISSING_CURRENCY",
        INVALID_CURRENCY: "INVALID_CURRENCY",
        INVALID_DATE: "INVALID_DATE"
    });

    /** Tekst przycięty do limitu; inne typy = null (np. liczba w polu tekstowym nie jest „naprawiana”). */
    function text(vValue, iMax) {
        if (typeof vValue !== "string") {
            return null;
        }
        var sValue = vValue.trim();
        return sValue ? sValue.substring(0, iMax) : null;
    }

    function isRealDate(sDate) {
        var aMatch = ISO_DATE_PATTERN.exec(sDate);
        if (!aMatch) {
            return false;
        }
        var oDate = new Date(Date.UTC(+aMatch[1], +aMatch[2] - 1, +aMatch[3]));
        return oDate.getUTCFullYear() === +aMatch[1] && oDate.getUTCMonth() === +aMatch[2] - 1 && oDate.getUTCDate() === +aMatch[3];
    }

    function status(vValue) {
        return typeof vValue === "string" && STATUS_PATTERN.test(vValue) ? vValue : null;
    }

    /**
     * Skrót wizualny numeru KSeF: początek (NIP) + koniec (część techniczna + CRC). Pełna wartość zostaje
     * w modelu (tooltip, szczegóły, kopiowanie) — skrót nigdy nie jest używany jako identyfikator.
     */
    function shortenKsefNumber(sKsefNumber) {
        if (typeof sKsefNumber !== "string") {
            return "";
        }
        return sKsefNumber.length <= 24 ? sKsefNumber
            : sKsefNumber.substring(0, 11) + "…" + sKsefNumber.substring(sKsefNumber.length - 9);
    }

    /**
     * Porównanie dwóch kwot dziesiętnych zapisanych tekstem, bez konwersji na Number (brak utraty precyzji).
     * @returns {number} <0, 0, >0
     */
    function compareDecimal(sLeft, sRight) {
        function parts(sValue) {
            var bNegative = sValue.charAt(0) === "-";
            var aSplit = sValue.replace(/^-/, "").split(".");
            var sInt = aSplit[0].replace(/^0+(?=\d)/, "");
            var sFrac = (aSplit[1] || "").replace(/0+$/, "");
            var bZero = /^0$/.test(sInt) && !sFrac;
            return { negative: bNegative && !bZero, int: sInt, frac: sFrac };
        }
        var oLeft = parts(sLeft);
        var oRight = parts(sRight);
        if (oLeft.negative !== oRight.negative) {
            return oLeft.negative ? -1 : 1;
        }
        var iSign = oLeft.negative ? -1 : 1;
        if (oLeft.int.length !== oRight.int.length) {
            return iSign * (oLeft.int.length - oRight.int.length);
        }
        if (oLeft.int !== oRight.int) {
            return iSign * (oLeft.int < oRight.int ? -1 : 1);
        }
        var iLength = Math.max(oLeft.frac.length, oRight.frac.length);
        var sLeftFrac = oLeft.frac.padEnd(iLength, "0");
        var sRightFrac = oRight.frac.padEnd(iLength, "0");
        return sLeftFrac === sRightFrac ? 0 : iSign * (sLeftFrac < sRightFrac ? -1 : 1);
    }

    /**
     * DTO roboczego kontraktu (KsefService.InboundInvoiceOverview) → wiersz modelu widoku.
     * Zwraca null dla rekordu bez poprawnego `id` (nie da się go bezpiecznie wskazać ani otworzyć).
     * Wartości nie są „naprawiane”: brak = null, nieprawidłowy format = wartość surowa + kod problemu.
     */
    function toRow(oDto) {
        if (!oDto || typeof oDto !== "object" || !UUID_PATTERN.test(String(oDto.id || ""))) {
            return null;
        }
        var M = InboundContract.MaxLength;
        var aIssues = [];
        var oRow = {
            id: String(oDto.id).toLowerCase(),
            environment: status(oDto.environment),
            ksefNumber: text(oDto.ksefNumber, M.ksefNumber),
            invoiceNumber: text(oDto.invoiceNumber, M.invoiceNumber),
            issueDate: null,
            acquisitionDate: null,
            // Bez przycinania do 10 znaków: niepoprawna wartość z backendu ma być widoczna, nie „naprawiona”.
            sellerNip: text(oDto.sellerNip, 20),
            sellerName: text(oDto.sellerName, M.sellerName),
            buyerIdentifierType: text(oDto.buyerIdentifierType, 20),
            buyerIdentifierValue: text(oDto.buyerIdentifierValue, M.buyerIdentifierValue),
            buyerName: text(oDto.buyerName, M.buyerName),
            currency: text(oDto.currency, 10),
            grossAmount: null,
            grossAmountValid: false,
            invoiceType: text(oDto.invoiceType, M.invoiceType),
            formCode: text(oDto.formCode, M.formCode),
            ingestionStatus: status(oDto.ingestionStatus),
            parseStatus: status(oDto.parseStatus),
            integrityStatus: status(oDto.integrityStatus),
            sapPostingStatus: status(oDto.sapPostingStatus),
            // Tylko jawne true: brak informacji nie oznacza dostępności XML.
            xmlAvailable: oDto.xmlAvailable === true
        };

        if (!oRow.invoiceNumber) {
            aIssues.push(Issue.MISSING_INVOICE_NUMBER);
        }
        if (!oRow.ksefNumber) {
            aIssues.push(Issue.MISSING_KSEF_NUMBER);
        } else if (!KSEF_NUMBER_PATTERN.test(oRow.ksefNumber)) {
            aIssues.push(Issue.NONSTANDARD_KSEF_NUMBER);
        }

        if (typeof oDto.issueDate === "string" && isRealDate(oDto.issueDate)) {
            oRow.issueDate = oDto.issueDate;
        } else {
            aIssues.push(oDto.issueDate ? Issue.INVALID_DATE : Issue.MISSING_ISSUE_DATE);
        }
        if (typeof oDto.acquisitionDate === "string" && ISO_DATETIME_PATTERN.test(oDto.acquisitionDate)
                && !isNaN(new Date(oDto.acquisitionDate).getTime())) {
            oRow.acquisitionDate = oDto.acquisitionDate;
        } else if (oDto.acquisitionDate) {
            aIssues.push(Issue.INVALID_DATE);
        }

        if (!oRow.sellerNip) {
            aIssues.push(Issue.MISSING_SELLER_NIP);
        }

        // Kwota: wyłącznie tekst dziesiętny. Liczba JSON mogła już utracić precyzję — pokazujemy ją, ale oznaczamy.
        if (typeof oDto.grossAmount === "string" && DECIMAL_PATTERN.test(oDto.grossAmount.trim())) {
            oRow.grossAmount = oDto.grossAmount.trim();
            oRow.grossAmountValid = true;
        } else if (oDto.grossAmount === null || oDto.grossAmount === undefined || oDto.grossAmount === "") {
            aIssues.push(Issue.MISSING_AMOUNT);
        } else {
            oRow.grossAmount = String(oDto.grossAmount).substring(0, 40);
            aIssues.push(Issue.INVALID_AMOUNT);
        }

        if (!oRow.currency) {
            aIssues.push(Issue.MISSING_CURRENCY);
        } else if (!CURRENCY_PATTERN.test(oRow.currency)) {
            aIssues.push(Issue.INVALID_CURRENCY);
        }

        oRow.ksefNumberShort = shortenKsefNumber(oRow.ksefNumber);
        oRow.issues = aIssues;
        oRow.incomplete = aIssues.length > 0;
        return oRow;
    }

    /** @returns {{rows: object[], rejected: number}} wiersze poprawne i liczba odrzuconych rekordów (bez id) */
    function toRows(aDtos) {
        var aRows = [];
        var iRejected = 0;
        (Array.isArray(aDtos) ? aDtos : []).forEach(function (oDto) {
            var oRow = toRow(oDto);
            if (oRow) {
                aRows.push(oRow);
            } else {
                iRejected++;
            }
        });
        return { rows: aRows, rejected: iRejected };
    }

    /**
     * Stan wiersza do podświetlenia w tabeli: błędy integralności/pobrania/parsowania = Error,
     * nieobsługiwany schemat lub niepełne metadane = Warning.
     */
    function rowHighlight(oRow) {
        if (!oRow) {
            return "None";
        }
        if (oRow.integrityStatus === "MISMATCH" || oRow.integrityStatus === "CONFLICT"
                || oRow.ingestionStatus === "DOWNLOAD_FAILED" || oRow.parseStatus === "INVALID") {
            return "Error";
        }
        return oRow.parseStatus === "UNSUPPORTED_SCHEMA" || oRow.incomplete ? "Warning" : "None";
    }

    return {
        Issue: Issue,
        KSEF_NUMBER_PATTERN: KSEF_NUMBER_PATTERN,
        UUID_PATTERN: UUID_PATTERN,
        DECIMAL_PATTERN: DECIMAL_PATTERN,
        toRow: toRow,
        toRows: toRows,
        shortenKsefNumber: shortenKsefNumber,
        compareDecimal: compareDecimal,
        rowHighlight: rowHighlight,
        isRealDate: isRealDate
    };
});
