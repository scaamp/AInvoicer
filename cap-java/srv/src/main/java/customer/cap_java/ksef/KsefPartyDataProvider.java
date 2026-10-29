package customer.cap_java.ksef;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import customer.cap_java.ksef.fa3.Fa3Invoice.Address;
import customer.cap_java.ksef.fa3.SapInvoiceFa3Mapper.PartyData;

/**
 * Źródło danych FA(3) spoza modelu RAP (ADR-0005 Q5 / audyt Q6: „konfiguracja CAP per NIP na start”).
 * Adres sprzedawcy po NIP sprzedawcy, a flagi JST/GV i opcjonalny adres nabywcy po NIP nabywcy —
 * wyłącznie z konfiguracji serwera ({@code ainv.ksef.party-data}), nigdy z przeglądarki.
 * Brak wpisu = brak danych; nic nie jest zgadywane ani uzupełniane domyślnie.
 * {@code DataWytworzeniaFa} = zegar serwera w chwili przygotowania.
 */
@Component
public class KsefPartyDataProvider {
    public record SellerEntry(String countryCode, String line1, String line2) { }

    public record BuyerEntry(String countryCode, String line1, String line2,
            Boolean jstSubordinateUnit, Boolean vatGroupMember) { }

    public record Settings(Map<String, SellerEntry> sellers, Map<String, BuyerEntry> buyers) { }

    private final Settings settings;
    private final Clock clock;

    public KsefPartyDataProvider(Environment environment, Clock clock) {
        this.settings = Binder.get(environment).bind("ainv.ksef.party-data", Bindable.of(Settings.class))
                .orElse(new Settings(Map.of(), Map.of()));
        this.clock = clock;
    }

    public Optional<PartyData> forInvoice(Map<String, Object> header) {
        SellerEntry seller = lookup(settings.sellers(), header.get("SellerNip"));
        BuyerEntry buyer = lookup(settings.buyers(), header.get("BuyerNip"));
        if (seller == null || buyer == null) {
            return Optional.empty();
        }
        Address buyerAddress = buyer.line1() == null ? null
                : new Address(buyer.countryCode(), buyer.line1(), buyer.line2());
        return Optional.of(new PartyData(clock.instant(),
                new Address(seller.countryCode(), seller.line1(), seller.line2()), buyerAddress,
                buyer.jstSubordinateUnit(), buyer.vatGroupMember()));
    }

    private static <T> T lookup(Map<String, T> entries, Object nip) {
        return entries == null || nip == null ? null : entries.get(nip.toString());
    }
}
