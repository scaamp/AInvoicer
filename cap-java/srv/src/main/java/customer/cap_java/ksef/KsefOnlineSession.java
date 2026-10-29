package customer.cap_java.ksef;

import java.util.Base64;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pl.akmf.ksef.sdk.api.builders.session.OpenOnlineSessionRequestBuilder;
import pl.akmf.ksef.sdk.api.builders.session.SendInvoiceOnlineSessionRequestBuilder;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.session.EncryptionData;
import pl.akmf.ksef.sdk.client.model.session.FormCode;
import pl.akmf.ksef.sdk.client.model.session.SchemaVersion;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;
import pl.akmf.ksef.sdk.client.model.session.SessionValue;
import pl.akmf.ksef.sdk.client.model.session.SystemCode;

/** Adapter oficjalnego SDK, bez własnego protokołu/kryptografii i bez wspólnego AES/IV. */
@Component
public class KsefOnlineSession {
    private final KSeFClient client;
    private final DefaultCryptographyService crypto;

    public KsefOnlineSession(KSeFClient client, @Lazy DefaultCryptographyService crypto) {
        this.client = client;
        this.crypto = crypto;
    }

    /** Materiał szyfrowania należy wyłącznie do jednego wykonania; nie jest zapisywany w DB. */
    public EncryptionData encryption() {
        try { return crypto.getEncryptionData(); }
        catch (Exception e) { throw KsefFailure.from(e, false, "CRYPTO"); }
    }

    /** FA(3)/1-0E/FA; brak ponowienia POST po awarii transportowej. */
    public String open(EncryptionData encryption, KsefAuthentication.Tokens tokens) {
        try {
            var request = new OpenOnlineSessionRequestBuilder()
                    .withFormCode(new FormCode(SystemCode.FA_3, SchemaVersion.VERSION_1_0E, SessionValue.FA))
                    .withEncryptionInfo(encryption.encryptionInfo()).build();
            return required(client.openOnlineSession(request, tokens.accessToken()).getReferenceNumber());
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.from(e, true, "OPEN"); }
    }

    /** Szyfruje dokładne bajty, które otrzymała migawka. Nie dekoduje i nie serializuje XML ponownie. */
    public String send(String session, byte[] xml, EncryptionData encryption, KsefAuthentication.Tokens tokens) {
        try {
            byte[] encrypted = crypto.encryptBytesWithAES256(xml, encryption.cipherKey(), encryption.cipherIv());
            var plainMetadata = crypto.getMetaData(xml);
            var encryptedMetadata = crypto.getMetaData(encrypted);
            var request = new SendInvoiceOnlineSessionRequestBuilder()
                    .withInvoiceHash(plainMetadata.getHashSHA()).withInvoiceSize(plainMetadata.getFileSize())
                    .withEncryptedInvoiceHash(encryptedMetadata.getHashSHA())
                    .withEncryptedInvoiceSize(encryptedMetadata.getFileSize())
                    .withEncryptedInvoiceContent(Base64.getEncoder().encodeToString(encrypted)).build();
            return required(client.onlineSessionSendInvoice(session, request, tokens.accessToken()).getReferenceNumber());
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.from(e, true, "SEND"); }
    }

    /** Bezpieczny odczyt; 100/150 to oczekiwanie, dopiero 200 + numer + hash dowodzą przyjęcia. */
    public SessionInvoiceStatusResponse status(String session, String invoice, KsefAuthentication.Tokens tokens) {
        try { return client.getSessionInvoiceStatus(session, invoice, tokens.accessToken()); }
        catch (Exception e) { throw KsefFailure.from(e, false, "STATUS"); }
    }

    /** Rekoncyliacja utraconej odpowiedzi send w istniejącej sesji; nie wysyła dokumentu ponownie. */
    public SessionInvoiceStatusResponse findByHash(String session, String hashBase64, KsefAuthentication.Tokens tokens) {
        try {
            String continuation = null;
            SessionInvoiceStatusResponse found = null;
            for (int page = 0; page < 100; page++) {
                var result = client.getSessionInvoices(session, continuation, 100, tokens.accessToken());
                if (result.getInvoices() != null) {
                    for (var invoice : result.getInvoices()) {
                        if (hashBase64.equals(invoice.getInvoiceHash())) {
                            if (found != null) { throw KsefFailure.invalid("KSEF_RECONCILIATION_MULTIPLE_MATCHES"); }
                            found = invoice;
                        }
                    }
                }
                continuation = result.getContinuationToken();
                if (continuation == null || continuation.isBlank()) { return found; }
            }
            throw KsefFailure.invalid("KSEF_RECONCILIATION_PAGE_LIMIT");
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.from(e, false, "RECONCILE"); }
    }

    /** Zamknięcie jest osobnym efektem; niejednoznacznego close nie powtarzamy. */
    public void close(String session, KsefAuthentication.Tokens tokens) {
        try { client.closeOnlineSession(session, tokens.accessToken()); }
        catch (Exception e) { throw KsefFailure.from(e, true, "CLOSE"); }
    }

    public int sessionStatus(String session, KsefAuthentication.Tokens tokens) {
        try { return client.getSessionStatus(session, tokens.accessToken()).getStatus().getCode(); }
        catch (Exception e) { throw KsefFailure.from(e, false, "SESSION_STATUS"); }
    }

    /** Końcowy status sesji i gotowe strony UPO są odrębnymi warunkami. */
    public boolean upoReady(String session, KsefAuthentication.Tokens tokens) {
        try {
            var response = client.getSessionStatus(session, tokens.accessToken());
            return response.getStatus().getCode() == 200 && response.getUpo() != null
                    && response.getUpo().getPages() != null && !response.getUpo().getPages().isEmpty();
        } catch (Exception e) { throw KsefFailure.from(e, false, "UPO_STATUS"); }
    }

    /** SDK pobiera UPO przez autoryzowany endpoint, bez przekazywania JWT do podpisanego URL. */
    public byte[] upo(String session, String invoice, KsefAuthentication.Tokens tokens) {
        try { return client.getSessionInvoiceUpoByReferenceNumber(session, invoice, tokens.accessToken()); }
        catch (Exception e) { throw KsefFailure.from(e, false, "UPO"); }
    }

    private static String required(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new KsefFailure("KSEF_EFFECT_RESPONSE_INVALID", true, false, null);
        }
        return reference;
    }
}
