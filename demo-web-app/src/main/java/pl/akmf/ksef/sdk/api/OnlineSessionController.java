package pl.akmf.ksef.sdk.api;


import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.akmf.ksef.sdk.api.builders.session.OpenOnlineSessionRequestBuilder;
import pl.akmf.ksef.sdk.api.builders.session.SendInvoiceOnlineSessionRequestBuilder;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.client.model.ApiException;
import pl.akmf.ksef.sdk.client.model.session.EncryptionData;
import pl.akmf.ksef.sdk.client.model.session.FormCode;
import pl.akmf.ksef.sdk.client.model.session.SchemaVersion;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;
import pl.akmf.ksef.sdk.client.model.session.SessionStatusResponse;
import pl.akmf.ksef.sdk.client.model.session.SessionValue;
import pl.akmf.ksef.sdk.client.model.session.SystemCode;
import pl.akmf.ksef.sdk.client.model.session.online.OpenOnlineSessionResponse;
import pl.akmf.ksef.sdk.client.model.session.online.SendInvoiceResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import java.util.Map;

import static pl.akmf.ksef.sdk.client.Headers.AUTHORIZATION;

@RequiredArgsConstructor
@RestController
public class OnlineSessionController {
    private final DefaultKsefClient ksefClient;
    private final DefaultCryptographyService defaultCryptographyService;
    private EncryptionData encryptionData;

    @Value("${sdk.config.base-uri}")
    private String baseUri;
    @Value("${sdk.config.suffix-uri}")
    private String suffixUri;

    /** Efektywna konfiguracja działającej JVM, bez sekretów. Node sprawdza ją
     * przed auth/send: sam plik YAML nie wykrywa nadpisania parametrem startowym. */
    @GetMapping("/e2e/config")
    public Map<String, String> getE2eConfig() {
        return Map.of("baseUri", baseUri, "suffixUri", suffixUri,
                "environment", "https://api-test.ksef.mf.gov.pl".equals(baseUri) && "v2".equals(suffixUri) ? "TEST" : "OTHER");
    }

    /** REST -> SDK -> GET /v2/sessions/{referenceNumber}.
     * Po close kod 170 oznacza oczekiwanie, a 200 przetworzoną sesję.
     * UPO sesji jest udostępniane asynchronicznie w polu upo.pages. */
    @GetMapping("/session-status")
    public SessionStatusResponse getSessionStatus(@RequestParam String referenceNumber,
                                                  @RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException {
        return ksefClient.getSessionStatus(referenceNumber, authToken);
    }

    /**
     * Otwarcie sesji interaktywnej
     *
     * @return OpenOnlineSessionResponse
     * @throws ApiException if fails to make API call
     */
    @PostMapping(value = "/open-session")
    public OpenOnlineSessionResponse openSession(@RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException {
        encryptionData = defaultCryptographyService.getEncryptionData();

        //stworzenie zapytania
        var request = new OpenOnlineSessionRequestBuilder()
                .withFormCode(new FormCode(SystemCode.FA_3, SchemaVersion.VERSION_1_0E, SessionValue.FA))
                .withEncryptionInfo(encryptionData.encryptionInfo())
                .build();

        //otwarcie sesji interaktywnej
        return ksefClient.openOnlineSession(request, authToken);
    }

    /**
     * Wysłanie przykładowej faktury wypełnionej danymi
     *
     * @param referenceNumber numer referencyjny otwartej sesji
     * @return OpenOnlineSessionResponse
     * @throws ApiException if fails to make API call
     */
    @PostMapping(value = "/send-invoice/{referenceNumber}/{contextIdentifier}")
    public SendInvoiceResponse sendInvoiceOnlineSession(@PathVariable String referenceNumber,
                                                        @PathVariable String contextIdentifier,
                                                        @RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException, IOException {
        //read example invoice
        // Zasób classpath działa zarówno w bootRun, jak i wewnątrz zbudowanego JAR-a.
        String invoiceTemplate = new ClassPathResource("xml/invoices/sample/invoice-template_v3.xml")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("#nip#", contextIdentifier)
                .replace("#invoicing_date#", LocalDate.of(2025, 6, 15).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .replace("#invoice_number#", UUID.randomUUID().toString());
        var invoice = invoiceTemplate.getBytes(StandardCharsets.UTF_8);

        //encrypt invoice
        var encryptedInvoice = defaultCryptographyService.encryptBytesWithAES256(invoice,
                encryptionData.cipherKey(),
                encryptionData.cipherIv());

        var invoiceMetadata = defaultCryptographyService.getMetaData(invoice);
        var encryptedInvoiceMetadata = defaultCryptographyService.getMetaData(encryptedInvoice);

        //prepare request
        var sendInvoiceRequest = new SendInvoiceOnlineSessionRequestBuilder()
                .withInvoiceHash(invoiceMetadata.getHashSHA())
                .withInvoiceSize(invoiceMetadata.getFileSize())
                .withEncryptedInvoiceHash(encryptedInvoiceMetadata.getHashSHA())
                .withEncryptedInvoiceSize(encryptedInvoiceMetadata.getFileSize())
                .withEncryptedInvoiceContent(Base64.getEncoder().encodeToString(encryptedInvoice))
                .build();

        //send invoice
        return ksefClient.onlineSessionSendInvoice(referenceNumber, sendInvoiceRequest, authToken);
    }

    /**
     * Wysłanie faktury online
     *
     * @param referenceNumber numer referencyjny otwartej sesji
     * @return OpenOnlineSessionResponse
     * @throws ApiException if fails to make API call
     */
    @PostMapping(value = "/send-invoice/{referenceNumber}/{contextIdentifier}/{hashOfCorrectedInvoice}")
    public SendInvoiceResponse sendTechnicalCorrection(@PathVariable String referenceNumber,
                                                       @PathVariable String contextIdentifier,
                                                       @PathVariable String hashOfCorrectedInvoice,
                                                       @RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException, IOException {
        //read example invoice
        String invoiceTemplate = new ClassPathResource("xml/invoices/sample/invoice-template.xml")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("#nip#", contextIdentifier)
                .replace("#invoicing_date#", LocalDate.of(2025, 6, 15).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .replace("#invoice_number#", UUID.randomUUID().toString());
        var invoice = invoiceTemplate.getBytes(StandardCharsets.UTF_8);

        //encrypt invoice
        var encryptedInvoice = defaultCryptographyService.encryptBytesWithAES256(invoice,
                encryptionData.cipherKey(),
                encryptionData.cipherIv());

        var invoiceMetadata = defaultCryptographyService.getMetaData(invoice);
        var encryptedInvoiceMetadata = defaultCryptographyService.getMetaData(encryptedInvoice);

        //prepare request
        var sendInvoiceRequest = new SendInvoiceOnlineSessionRequestBuilder()
                .withInvoiceHash(invoiceMetadata.getHashSHA())
                .withInvoiceSize(invoiceMetadata.getFileSize())
                .withEncryptedInvoiceHash(encryptedInvoiceMetadata.getHashSHA())
                .withEncryptedInvoiceSize(encryptedInvoiceMetadata.getFileSize())
                .withEncryptedInvoiceContent(Base64.getEncoder().encodeToString(encryptedInvoice))
                .withOfflineMode(true)
                .withHashOfCorrectedInvoice(hashOfCorrectedInvoice)
                .build();

        //send invoice
        return ksefClient.onlineSessionSendInvoice(referenceNumber, sendInvoiceRequest, authToken);
    }

    /**
     * Zamknięcie sesji interaktywnej
     *
     * @throws ApiException if fails to make API call
     */
    @PostMapping(value = "/close-session/{referenceNumber}")
    public void sessionClose(@PathVariable String referenceNumber,
                             @RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException {
        ksefClient.closeOnlineSession(referenceNumber, authToken);
    }

    /**
     * Unieważnienie tokenu
     *
     * @throws ApiException if fails to make API call
     */
    @DeleteMapping("/revoke/current")
    public void revokeCurrentSession(@RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException {
        ksefClient.revokeCurrentSession(authToken);
    }

    /**
     * Pobranie statusu faktury
     *
     * @throws ApiException if fails to make API call
     */
    @GetMapping("/invoice-status")
    public SessionInvoiceStatusResponse getSessionInvoiceStatus(
            @RequestParam String referenceNumber,
            @RequestParam String invoiceReferenceNumber,
            @RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException {
        return ksefClient.getSessionInvoiceStatus(referenceNumber, invoiceReferenceNumber, authToken);
    }

    /**
     * Pobranie UPO dla faktury
     *
     * @throws ApiException if fails to make API call
     */
    @GetMapping("/invoice-upo-by-invoice-number")
    public byte[] getInvoiceUpoByReferenceNumber(
            @RequestParam String referenceNumber,
            @RequestParam String invoiceReferenceNumber,
            @RequestHeader(name = AUTHORIZATION) String authToken) throws ApiException {
        return ksefClient.getSessionInvoiceUpoByReferenceNumber(referenceNumber, invoiceReferenceNumber, authToken);
    }
}
