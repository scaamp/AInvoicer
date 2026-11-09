package customer.cap_java.ksef.inbound.fa3;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Dane biznesowe odczytane z otrzymanej faktury FA(3) (schemat MF 1-0E, namespace
 * {@value SecureFa3InvoiceParser#FA3_NAMESPACE}). Model OUTBOUND ({@code ksef.fa3.Fa3Invoice}) jest celowo
 * osobny: dokument przychodzący może zawierać konstrukcje, których generator AInvoicer nie wytwarza.
 *
 * <p>Numer KSeF nie jest częścią XML — identyfikator dokumentu pochodzi z metadanych KSeF.
 *
 * <p><b>Sumy:</b> FA(3) nie ma pola „suma netto” ani „suma VAT”. Parser zwraca rozbicie z dokumentu bez
 * wyliczeń: {@code netAmounts} (P_13_x), {@code vatAmounts} (P_14_x, waluta faktury), {@code vatAmountsPln}
 * (P_14_xW, przeliczenie dla walut obcych) oraz {@code grossAmount} (P_15). Wyliczanie sum (procedura marży,
 * korekty ze znakiem ujemnym, zaliczki) należy do przyszłej logiki stagingu/FI, nie do parsera.
 *
 * @param systemCode    {@code KodFormularza/@kodSystemowy} = „FA (3)”
 * @param schemaVersion {@code KodFormularza/@wersjaSchemy} = „1-0E”
 * @param formVariant   {@code WariantFormularza} = „3”
 * @param invoiceNumber P_2
 * @param issueDate     P_1
 * @param currency      KodWaluty (ISO 4217)
 * @param invoiceKind   RodzajFaktury: VAT, KOR, ZAL, ROZ, UPR, KOR_ZAL, KOR_ROZ
 * @param seller        Podmiot1/DaneIdentyfikacyjne
 * @param buyer         Podmiot2/DaneIdentyfikacyjne
 * @param netAmounts    P_13_1 … P_13_11 obecne w dokumencie (klucz = nazwa elementu)
 * @param vatAmounts    P_14_1 … P_14_5 obecne w dokumencie
 * @param vatAmountsPln P_14_1W … P_14_4W obecne w dokumencie
 * @param grossAmount   P_15 — kwota należności ogółem
 * @param lines         FaWiersz w kolejności dokumentu (może być pusta, np. dla zaliczki)
 */
public record Fa3InboundInvoice(
        String systemCode,
        String schemaVersion,
        String formVariant,
        String invoiceNumber,
        LocalDate issueDate,
        String currency,
        String invoiceKind,
        Party seller,
        Party buyer,
        Map<String, BigDecimal> netAmounts,
        Map<String, BigDecimal> vatAmounts,
        Map<String, BigDecimal> vatAmountsPln,
        BigDecimal grossAmount,
        List<Line> lines) {

    public Fa3InboundInvoice {
        netAmounts = Map.copyOf(netAmounts);
        vatAmounts = Map.copyOf(vatAmounts);
        vatAmountsPln = Map.copyOf(vatAmountsPln);
        lines = List.copyOf(lines);
    }

    /** Rodzaj identyfikatora strony zgodny z wyborem w TPodmiot1/TPodmiot2 XSD. */
    public enum IdentifierType {
        /** Element NIP. */
        NIP,
        /** KodUE + NrVatUE. */
        VAT_UE,
        /** Opcjonalny KodKraju + NrID. */
        OTHER,
        /** BrakID = 1. */
        NONE
    }

    /**
     * @param identifierType typ identyfikatora
     * @param countryCode    KodUE (VAT_UE) albo KodKraju (OTHER); null dla NIP/NONE lub gdy nieobecny
     * @param identifier     NIP / NrVatUE / NrID; null dla NONE
     * @param name           Nazwa (opcjonalna dla nabywcy)
     */
    public record Party(IdentifierType identifierType, String countryCode, String identifier, String name) {
    }

    /**
     * Wiersz FaWiersz w zakresie pól potwierdzonych w XSD FA(3). Pola opcjonalne w XSD mogą być null.
     *
     * @param number         NrWierszaFa
     * @param uuid           UU_ID
     * @param name           P_7
     * @param unit           P_8A
     * @param quantity       P_8B (TIlosci, do 6 miejsc po przecinku)
     * @param unitNetPrice   P_9A (TKwotowy2, do 8 miejsc)
     * @param unitGrossPrice P_9B
     * @param netValue       P_11
     * @param grossValue     P_11A
     * @param vatAmount      P_11Vat
     * @param vatRate        P_12 (wartość słownikowa, np. „23”, „zw”, „0 WDT”)
     */
    public record Line(long number, String uuid, String name, String unit, BigDecimal quantity,
            BigDecimal unitNetPrice, BigDecimal unitGrossPrice, BigDecimal netValue, BigDecimal grossValue,
            BigDecimal vatAmount, String vatRate) {
    }
}
