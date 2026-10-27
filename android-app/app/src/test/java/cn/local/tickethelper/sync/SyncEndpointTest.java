package cn.local.tickethelper.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SyncEndpointTest {
    @Test public void exactHttpsReceiverNormalizesDefaultPortAndTrailingSlash() {
        SyncEndpoint endpoint = new SyncEndpoint(" https://HUB.example.com:443/ticketpilot/ ");
        assertEquals("https://hub.example.com/ticketpilot", endpoint.baseUrl);
        assertEquals("https://hub.example.com/ticketpilot/v1/pair", endpoint.pairUrl());
        assertEquals("https://hub.example.com/ticketpilot/v1/diagnostics", endpoint.diagnosticsUrl());
        assertEquals(endpoint, new SyncEndpoint("https://hub.example.com/ticketpilot"));
        assertNotEquals(endpoint, new SyncEndpoint("https://other.example.com/ticketpilot"));
    }
    @Test public void credentialsQueriesFragmentsCleartextAndAmbiguousPathsAreRejected() {
        for (String value : new String[]{"http://hub.example.com", "https://user:secret@hub.example.com", "https://hub.example.com?key=x",
                "https://hub.example.com/#fragment", "https://hub.example.com:8443", "https://hub.example.com/../private",
                "https://hub.example.com/%2e%2e", "https://hub.example.com//private", "https://hub.example.com./", "", null}) {
            try { new SyncEndpoint(value); fail(String.valueOf(value)); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("secret")); }
        }
    }
}
