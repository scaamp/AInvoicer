package customer.cap_java.ksef.inbound;

/**
 * Środowisko KSeF, z którego pochodzi dokument. Jest częścią tożsamości dokumentu i klucza checkpointu:
 * numer KSeF z TEST nigdy nie jest utożsamiany z tym samym napisem z DEMO/PROD.
 *
 * <p>Obecny klient SDK aplikacji ({@code KsefSdkConfiguration}) jest celowo ograniczony do TEST;
 * pozostałe wartości istnieją wyłącznie po to, aby model danych nie mieszał środowisk w przyszłości.
 */
public enum KsefEnvironment {
    TEST,
    DEMO,
    PROD
}
