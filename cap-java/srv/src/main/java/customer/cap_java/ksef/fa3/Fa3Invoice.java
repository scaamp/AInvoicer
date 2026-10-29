package customer.cap_java.ksef.fa3;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Model wejściowy podstawowej krajowej faktury sprzedażowej VAT (RodzajFaktury = VAT) dla FA(3).
 * Nie zna XML: nazwy pól są biznesowe, a mapowanie na elementy P_* wykonuje {@link Fa3XmlWriter}.
 * Kwoty i sumy są deklarowane przez wywołującego i sprawdzane przez {@link Fa3DomainValidator},
 * bo XSD nie weryfikuje arytmetyki. Pola mogą być null — brak zgłasza walidator, a nie konstruktor.
 *
 * @param createdAt DataWytworzeniaFa; podawany jawnie, aby te same dane dawały te same bajty
 * @param systemInfo opcjonalna nazwa systemu (SystemInfo)
 * @param issueDate P_1, data wystawienia
 * @param issuePlace opcjonalne P_1M, miejsce wystawienia
 * @param invoiceNumber P_2, numer faktury
 * @param saleDate opcjonalne P_6, data dostawy/wykonania usługi, gdy różni się od daty wystawienia
 * @param currency KodWaluty; obsługiwane wyłącznie PLN
 * @param vatTotals podsumowania per stawka (P_13_x / P_14_x)
 * @param grossTotal P_15, kwota należności ogółem
 */
public record Fa3Invoice(
        Instant createdAt,
        String systemInfo,
        Seller seller,
        Buyer buyer,
        String currency,
        LocalDate issueDate,
        String issuePlace,
        String invoiceNumber,
        LocalDate saleDate,
        List<Line> lines,
        List<VatRateTotal> vatTotals,
        BigDecimal grossTotal) {

    public Fa3Invoice {
        // Kopia chroni przed zmianą listy po walidacji; elementy null zgłasza walidator.
        lines = lines == null ? null : Collections.unmodifiableList(new ArrayList<>(lines));
        vatTotals = vatTotals == null ? null : Collections.unmodifiableList(new ArrayList<>(vatTotals));
    }

    /** Podmiot1 — sprzedawca; adres jest obowiązkowy w FA(3). */
    public record Seller(String nip, String name, Address address) {
    }

    /**
     * Podmiot2 — nabywca z polskim NIP. Flagi JST i GV są w XSD obowiązkowe (1 = tak, 2 = nie);
     * null oznacza brak decyzji i jest błędem walidacji, a nie domyślnym „nie”.
     */
    public record Buyer(String nip, String name, Address address, Boolean jstSubordinateUnit,
            Boolean vatGroupMember) {
    }

    /** TAdres: kod kraju (np. PL) i maksymalnie dwie linie adresu. */
    public record Address(String countryCode, String line1, String line2) {
    }

    /**
     * FaWiersz. Numer wiersza (NrWierszaFa) wynika z kolejności na liście.
     *
     * @param lineId opcjonalny UU_ID wiersza, np. ItemUUID z SAP (maks. 50 znaków)
     * @param unit P_8A, miara (tekst, np. „szt.”)
     * @param quantity P_8B
     * @param unitNetPrice P_9A, cena jednostkowa netto
     * @param netAmount P_11, wartość netto = round(quantity × unitNetPrice, 2)
     */
    public record Line(String lineId, String description, String unit, BigDecimal quantity,
            BigDecimal unitNetPrice, BigDecimal netAmount, VatRate rate) {
    }

    /** Podsumowanie stawki: netAmount = Σ P_11 wierszy, vatAmount = round(netAmount × stawka, 2). */
    public record VatRateTotal(VatRate rate, BigDecimal netAmount, BigDecimal vatAmount) {
    }

    /**
     * Obsługiwane stawki krajowe. {@code group} wskazuje pola P_13_{group} / P_14_{group} w XSD FA(3):
     * 1 = stawka podstawowa, 2 = pierwsza obniżona, 3 = druga obniżona.
     */
    public enum VatRate {
        RATE_23("23", new BigDecimal("23"), 1),
        RATE_8("8", new BigDecimal("8"), 2),
        RATE_5("5", new BigDecimal("5"), 3);

        private final String xmlCode;
        private final BigDecimal percent;
        private final int group;

        VatRate(String xmlCode, BigDecimal percent, int group) {
            this.xmlCode = xmlCode;
            this.percent = percent;
            this.group = group;
        }

        /** Wartość P_12 (TStawkaPodatku). */
        public String xmlCode() {
            return xmlCode;
        }

        public BigDecimal percent() {
            return percent;
        }

        public int group() {
            return group;
        }
    }
}
