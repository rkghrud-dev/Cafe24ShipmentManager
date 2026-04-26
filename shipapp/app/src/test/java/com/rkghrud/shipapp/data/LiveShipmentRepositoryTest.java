package com.rkghrud.shipapp.data;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveShipmentRepositoryTest {

    @Test
    public void normalizeCafe24JsonForStorage_acceptsSavedAccessTokenWithoutNetworkValidation() throws Exception {
        String normalized = LiveShipmentRepository.normalizeCafe24JsonForStorage(
                "{\"MallId\":\"home\",\"AccessToken\":\"expired-access-token\"}"
        );

        JSONObject json = new JSONObject(normalized);

        assertEquals("home", json.getString("MallId"));
        assertEquals("expired-access-token", json.getString("AccessToken"));
        assertEquals("2025-12-01", json.getString("ApiVersion"));
        assertFalse(json.getString("UpdatedAt").isEmpty());
    }

    @Test
    public void normalizeCafe24JsonForStorage_acceptsRefreshCredentialsWithoutAccessToken() throws Exception {
        String normalized = LiveShipmentRepository.normalizeCafe24JsonForStorage(
                "{\"MallId\":\"home\",\"ClientId\":\"client\",\"ClientSecret\":\"secret\",\"RefreshToken\":\"refresh\"}"
        );

        JSONObject json = new JSONObject(normalized);

        assertEquals("home", json.getString("MallId"));
        assertEquals("client", json.getString("ClientId"));
        assertEquals("refresh", json.getString("RefreshToken"));
        assertEquals("2025-12-01", json.getString("ApiVersion"));
    }

    @Test
    public void normalizeCafe24JsonForStorage_rejectsJsonWithoutAnyAuthPath() {
        try {
            LiveShipmentRepository.normalizeCafe24JsonForStorage("{\"MallId\":\"home\"}");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("AccessToken"));
            return;
        }

        throw new AssertionError("Expected missing auth path to be rejected.");
    }
}
