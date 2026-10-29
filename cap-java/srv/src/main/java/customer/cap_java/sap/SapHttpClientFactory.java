package customer.cap_java.sap;

import java.time.Duration;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpClientFactory;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestinationProperties;

/** Konfiguracja Apache HttpClient 4.x z Cloud SDK 5.33 używanego przez CAP 5.2; tylko Destination AINV_H01. */
final class SapHttpClientFactory extends DefaultHttpClientFactory {
    private final int connectMillis;
    private final int readMillis;
    private final int poolMillis;

    SapHttpClientFactory(Duration connect, Duration read, Duration pool) {
        connectMillis = positiveMillis(connect);
        readMillis = positiveMillis(read);
        poolMillis = positiveMillis(pool);
    }

    private static int positiveMillis(Duration value) {
        int millis = Math.toIntExact(value.toMillis());
        if (millis <= 0) { throw new IllegalArgumentException("SAP_HTTP_TIMEOUT_INVALID"); }
        return millis;
    }

    private boolean isH01(HttpDestinationProperties destination) {
        return destination != null && "AINV_H01".equals(destination.get("Name").getOrNull());
    }

    @Override protected RequestConfig.Builder getRequestConfigBuilder(HttpDestinationProperties destination) {
        var builder = super.getRequestConfigBuilder(destination);
        return isH01(destination) ? builder.setConnectTimeout(connectMillis)
                .setSocketTimeout(readMillis).setConnectionRequestTimeout(poolMillis) : builder;
    }

    @Override protected SocketConfig.Builder getSocketConfigBuilder(HttpDestinationProperties destination) {
        var builder = super.getSocketConfigBuilder(destination);
        return isH01(destination) ? builder.setSoTimeout(readMillis) : builder;
    }

    /** Brak niewidocznych retry I/O; CSRF nadal jest obsługiwany przez klienta OData SDK. */
    @Override protected HttpClientBuilder getHttpClientBuilder(HttpDestinationProperties destination) {
        var builder = super.getHttpClientBuilder(destination);
        return isH01(destination) ? builder.disableAutomaticRetries() : builder;
    }
}
