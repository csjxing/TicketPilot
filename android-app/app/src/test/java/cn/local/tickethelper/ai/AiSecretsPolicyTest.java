package cn.local.tickethelper.ai;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure storage policy only: these fixtures do not claim to exercise Android Keystore. */
public final class AiSecretsPolicyTest {
    private JSONObject quota(String day, Object used) throws Exception {
        return new JSONObject().put("schema", 1).put("day", day).put("used", used);
    }

    @Test public void existingConfigurationKeepsValuesWhenNewFieldsAreAbsent() throws Exception {
        JSONObject old = new JSONObject().put("schema", 1).put("enabled", true)
                .put("base_url", "https://api.example.com/v1").put("model", "chosen-model")
                .put("max_output_tokens", 3072).put("daily_call_limit", 73)
                .put("legacy_token_limit", true).put("key", "fixture-key");
        AiSettings value = AiSecretsStore.settings(old);
        assertTrue(value.enabled); assertEquals("https://api.example.com/v1", value.baseUrl);
        assertEquals("chosen-model", value.model); assertEquals(3072, value.maxOutputTokens);
        assertEquals(73, value.dailyCallLimit); assertTrue(value.legacyTokenLimit);
        assertEquals(32768, value.maxInputChars); assertEquals(120, value.timeoutSeconds);
        assertEquals("fixture-key", old.getString("key"));
    }

    @Test public void persistedExpandedValuesSurviveDecoding() throws Exception {
        JSONObject record = new JSONObject().put("enabled", true).put("base_url", "https://api.example.com")
                .put("model", "chosen-model").put("max_output_tokens", 0).put("daily_call_limit", 0)
                .put("max_input_chars", 262144).put("timeout_seconds", 600);
        AiSettings value = AiSecretsStore.settings(record);
        assertEquals(0, value.maxOutputTokens); assertEquals(0, value.dailyCallLimit);
        assertEquals(262144, value.maxInputChars); assertEquals(600, value.timeoutSeconds);
        assertTrue(value.validate().isEmpty());
    }

    @Test public void malformedStoredLimitsAreNotReplacedByPermissiveDefaults() throws Exception {
        for (String name : new String[]{"max_output_tokens", "daily_call_limit", "max_input_chars", "timeout_seconds"}) {
            for (Object invalid : new Object[]{"0", true, 1.5, JSONObject.NULL}) {
                try { AiSecretsStore.settings(new JSONObject().put(name, invalid)); fail("Malformed limit accepted"); }
                catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains(name)); }
            }
        }
    }

    @Test public void unlimitedCallsStillIncrementBeyondTheOldAndConfigurableCaps() throws Exception {
        assertEquals(101, AiSecretsStore.nextUsage(quota("2030-01-01", 100), "2030-01-01", 0));
        assertEquals(1_000_001, AiSecretsStore.nextUsage(quota("2030-01-01", 1_000_000), "2030-01-01", 0));
        assertEquals(1, AiSecretsStore.nextUsage(quota("2030-01-01", 1_000_001), "2030-01-02", 0));
    }

    @Test public void selectedCapStillBlocksAndCanBeRaisedWithoutErasingCount() throws Exception {
        JSONObject record = quota("2030-01-01", 20);
        try { AiSecretsStore.nextUsage(record, "2030-01-01", 20); fail("Consumed quota accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().startsWith("已达到")); }
        assertEquals(21, AiSecretsStore.nextUsage(record, "2030-01-01", 21));
        assertEquals(20, record.getInt("used"));
    }

    @Test public void unlimitedDoesNotBypassDamagedRecordsOrClockRollback() throws Exception {
        for (JSONObject record : new JSONObject[]{quota("2030-01-01", -1), quota("2030-01-01", "0"),
                quota("2030-01-01", 1.5), quota("2030-01-01", Integer.MAX_VALUE),
                quota("2030-02-30", 0), quota("2030-01-02", 0), quota("2030-01-01", 0).put("schema", "1")}) {
            try { AiSecretsStore.nextUsage(record, "2030-01-01", 0); fail("Invalid accounting accepted"); }
            catch (Exception expected) { /* No usable reservation. */ }
        }
        try { AiSecretsStore.nextUsage(quota("2030-01-01", "broken"), "2030-01-02", 0); fail("Old corrupt record bypassed"); }
        catch (Exception expected) { /* A new day cannot silently erase corrupt accounting. */ }
    }
}
