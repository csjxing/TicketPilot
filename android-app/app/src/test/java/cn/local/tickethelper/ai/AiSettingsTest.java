package cn.local.tickethelper.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AiSettingsTest {
    private AiSettings settings(String url) { return new AiSettings(true, url, "provider/model-v1", 1024, 20, false); }

    @Test public void emptyDefaultsCannotAccidentallyEnableAProvider() {
        AiSettings defaults = AiSettings.defaults();
        assertFalse(defaults.enabled); assertEquals("", defaults.baseUrl); assertEquals("", defaults.model);
        assertTrue(defaults.validate().isEmpty());
        assertFalse(new AiSettings(true, "", "", 1024, 20, false).validate().isEmpty());
    }

    @Test public void baseAndFullEndpointsHaveOneCanonicalDestination() {
        assertEquals("https://api.example.com/v1/chat/completions", settings("HTTPS://API.EXAMPLE.COM:443/v1/").endpoint());
        assertEquals(settings("https://api.example.com").endpoint(), settings("https://api.example.com/v1/chat/completions/").endpoint());
        assertEquals("https://api.example.com:8443/proxy/v2/chat/completions", settings("https://api.example.com:8443/proxy/v2").endpoint());
    }

    @Test public void credentialsCannotBeEmbeddedInTheEndpoint() {
        for (String url : new String[]{"https://user:secret@api.example.com/v1", "https://api.example.com/v1?key=SECRET",
                "https://api.example.com/v1#SECRET", "http://api.example.com/v1", "https://api.example.com:0/v1",
                "https://api.example.com:65536/v1", "file:///etc/passwd", "https://api.example.com/v1\r\nAuthorization:x"}) {
            assertFalse(url, settings(url).validate().isEmpty());
            try { settings(url).endpoint(); fail("Unsafe URL was accepted"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("SECRET")); }
        }
    }

    @Test public void pathTraversalAndAmbiguousHostAreRejected() {
        for (String url : new String[]{"https://api.example.com/a/../v1", "https://api.example.com/a/./v1", "https:///v1",
                "https://api.example.com\\@different.example/v1"}) assertFalse(settings(url).validate().isEmpty());
    }

    @Test public void missingAndControlCharacterModelsAreRejected() {
        for (String model : new String[]{"", "a b", "x\ny", "x\u0000y", "x".repeat(201)})
            assertFalse(new AiSettings(true, "https://api.example.com/v1", model, 1024, 20, false).validate().isEmpty());
    }

    @Test public void outputAndDailyBudgetsHaveHardBounds() {
        for (int tokens : new int[]{-1, 0, 127, 4097, Integer.MAX_VALUE})
            assertFalse(new AiSettings(true, "https://api.example.com", "model", tokens, 20, false).validate().isEmpty());
        for (int calls : new int[]{-1, 0, 101, Integer.MAX_VALUE})
            assertFalse(new AiSettings(true, "https://api.example.com", "model", 1024, calls, false).validate().isEmpty());
        assertTrue(new AiSettings(true, "https://api.example.com", "model", 128, 1, false).validate().isEmpty());
        assertTrue(new AiSettings(true, "https://api.example.com", "model", 4096, 100, true).validate().isEmpty());
    }

    @Test public void headerInjectionAndWhitespaceKeysAreRejected() {
        assertTrue(AiSettings.validApiKey("fixture-key-only"));
        for (String value : new String[]{"", "key\r\nX-Other: injected", "Bearer key", "密钥", "x".repeat(8193)})
            assertFalse(AiSettings.validApiKey(value));
    }
}
