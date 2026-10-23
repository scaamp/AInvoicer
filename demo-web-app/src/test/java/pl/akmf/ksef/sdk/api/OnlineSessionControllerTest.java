package pl.akmf.ksef.sdk.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.session.SessionStatusResponse;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OnlineSessionControllerTest {
    @Test
    void sessionStatusUsesRawJwtAndReturnsBusinessStatus() throws Exception {
        var client = mock(DefaultKsefClient.class);
        var controller = new OnlineSessionController(client, mock(DefaultCryptographyService.class));
        var status = new SessionStatusResponse();
        status.setStatus(new StatusInfo(200, "Przetworzono", null));
        when(client.getSessionStatus("session-ref", "test-jwt")).thenReturn(status);
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/session-status")
                        .param("referenceNumber", "session-ref").header("Authorization", "test-jwt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status.code").value(200));
        verify(client).getSessionStatus("session-ref", "test-jwt");
    }

    @Test
    void runtimeConfigReflectsActualEnvironmentWithoutCallingSdk() throws Exception {
        var client = mock(DefaultKsefClient.class);
        var controller = new OnlineSessionController(client, mock(DefaultCryptographyService.class));
        ReflectionTestUtils.setField(controller, "baseUri", "https://api.ksef.mf.gov.pl");
        ReflectionTestUtils.setField(controller, "suffixUri", "v2");
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/e2e/config"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("OTHER"));
        verifyNoInteractions(client);
    }
}
