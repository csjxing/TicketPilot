package cn.local.tickethelper.ai;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials and settings commit together, encrypted at rest and excluded from Android backup. */
public final class AiSecretsStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "ticketpilot_ai_settings_v1";
    private static final byte[] MAGIC = new byte[]{'T', 'P', 'A', 'I', 1};
    private static final int MAX_FILE = 32768;
    private final AtomicFile secrets, quota;

    public AiSecretsStore(Context context) {
        File root = context.getApplicationContext().getNoBackupFilesDir();
        secrets = new AtomicFile(new File(root, "ai-provider.enc"));
        quota = new AtomicFile(new File(root, "ai-daily-quota.json"));
    }

    /** A damaged/unreadable secret record is never treated as an enabled provider. */
    public AiSettings loadSettings() {
        synchronized (LOCK) {
            try { return settings(read()); }
            catch (Exception e) { return AiSettings.defaults(); }
        }
    }

    public boolean hasKey() {
        synchronized (LOCK) {
            try { return AiSettings.validApiKey(read().optString("key", "")); }
            catch (Exception e) { return false; }
        }
    }

    public String readKey() throws IOException {
        synchronized (LOCK) {
            try {
                String key = read().optString("key", "");
                if (!AiSettings.validApiKey(key)) throw new IOException();
                return key;
            } catch (Exception e) { throw new IOException("无法读取模型密钥，请在设置中重新填写。"); }
        }
    }

    /** Bind decryption to the exact settings the user reviewed, never a newer provider's key. */
    public String readKeyFor(AiSettings expected) throws IOException {
        synchronized (LOCK) {
            try {
                JSONObject record = read();
                AiSettings current = settings(record);
                if (expected == null || !current.enabled || !expected.enabled
                        || !current.endpoint().equals(expected.endpoint()) || !current.model.equals(expected.model)
                        || current.maxOutputTokens != expected.maxOutputTokens
                        || current.dailyCallLimit != expected.dailyCallLimit
                        || current.legacyTokenLimit != expected.legacyTokenLimit)
                    throw new IOException();
                String key = record.optString("key", "");
                if (!AiSettings.validApiKey(key)) throw new IOException();
                return key;
            } catch (Exception e) { throw new IOException("模型设置或密钥已变化，请重新预览后发送。"); }
        }
    }

    /** Empty newKey retains a key only for the exact same normalized endpoint. */
    public void saveSettings(AiSettings value, String newKey) throws IOException {
        if (value == null || !value.validate().isEmpty()) throw new IOException("模型设置不完整，请核对接口、模型和调用上限。");
        String supplied = newKey == null ? "" : newKey.trim();
        if (!supplied.isEmpty() && !AiSettings.validApiKey(supplied)) throw new IOException("密钥格式无效，请重新填写。");
        synchronized (LOCK) {
            byte[] plain = null;
            try {
                JSONObject old;
                try { old = read(); }
                catch (Exception e) { old = new JSONObject(); }
                AiSettings previous = settings(old);
                String key = supplied;
                boolean sameEndpoint = !value.baseUrl.isEmpty() && !previous.baseUrl.isEmpty()
                        && value.endpoint().equals(previous.endpoint());
                if (key.isEmpty() && sameEndpoint) key = old.optString("key", "");
                if (value.enabled && !AiSettings.validApiKey(key))
                    throw new IOException("请填写 API Key；更换接口地址后需要重新填写密钥。");
                JSONObject record = new JSONObject().put("schema", 1).put("enabled", value.enabled)
                        .put("base_url", value.baseUrl).put("model", value.model)
                        .put("max_output_tokens", value.maxOutputTokens).put("daily_call_limit", value.dailyCallLimit)
                        .put("legacy_token_limit", value.legacyTokenLimit).put("key", key);
                plain = record.toString().getBytes(StandardCharsets.UTF_8);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                cipher.updateAAD(MAGIC);
                byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
                if (iv.length != 12) throw new IOException();
                ByteBuffer payload = ByteBuffer.allocate(MAGIC.length + iv.length + encrypted.length);
                payload.put(MAGIC).put(iv).put(encrypted);
                write(secrets, payload.array());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("请填写 API Key")) throw e;
                throw new IOException("模型设置未保存，请重试；不会使用未保存的密钥。");
            } catch (Exception e) { throw new IOException("模型设置未保存，请重试；不会使用未保存的密钥。"); }
            finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
        }
    }

    /** Clearing a provider must not reset an already-consumed daily call budget. */
    public void clear() throws IOException {
        synchronized (LOCK) {
            try {
                secrets.delete();
                if (secrets.getBaseFile().exists()) throw new IOException();
                KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
            } catch (Exception e) { throw new IOException("未能完整清除模型设置，请重试。"); }
        }
    }

    public int dailyUsed() {
        synchronized (LOCK) {
            try {
                JSONObject record = readQuota();
                String today = day();
                String saved = record.optString("day", today);
                int count = count(record);
                return saved.compareTo(today) >= 0 ? count : 0;
            } catch (Exception e) { return AiSettings.MAX_DAILY_CALLS; }
        }
    }

    /** Reserve durably before sending. Failed/cancelled calls are deliberately not refunded. */
    public void reserveCall(int dailyLimit) throws IOException {
        if (dailyLimit < 1 || dailyLimit > AiSettings.MAX_DAILY_CALLS) throw new IOException("每日调用上限无效。");
        synchronized (LOCK) {
            try {
                JSONObject record = readQuota();
                String today = day(), saved = record.optString("day", today);
                if (saved.compareTo(today) > 0) throw new IOException("手机日期早于已有调用记录，请先校正时间。");
                int used = saved.equals(today) ? count(record) : 0;
                if (used >= dailyLimit) throw new IOException("已达到今日模型调用上限，请明天再试或调整上限。");
                write(quota, new JSONObject().put("schema", 1).put("day", today).put("used", used + 1)
                        .toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (e.getMessage() != null && (e.getMessage().startsWith("已达到") || e.getMessage().startsWith("手机日期"))) throw e;
                throw new IOException("无法保存模型调用计数，本次没有发送。");
            } catch (Exception e) { throw new IOException("无法保存模型调用计数，本次没有发送。"); }
        }
    }

    private static String day() { return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(); }

    private JSONObject readQuota() throws Exception {
        if (!exists(quota)) return new JSONObject().put("schema", 1).put("day", day()).put("used", 0);
        JSONObject value = new JSONObject(new String(readBytes(quota), StandardCharsets.UTF_8));
        if (value.getInt("schema") != 1 || !LocalDate.parse(value.getString("day")).toString().equals(value.getString("day")))
            throw new IOException();
        count(value); return value;
    }

    private static int count(JSONObject record) throws Exception {
        Object raw = record.get("used");
        if (!(raw instanceof Integer) || (Integer) raw < 0 || (Integer) raw > AiSettings.MAX_DAILY_CALLS)
            throw new IOException();
        return (Integer) raw;
    }

    private JSONObject read() throws Exception {
        if (!exists(secrets)) return new JSONObject();
        byte[] raw = readBytes(secrets);
        if (raw.length < MAGIC.length + 12 + 16 || !Arrays.equals(Arrays.copyOf(raw, MAGIC.length), MAGIC)) throw new IOException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, raw, MAGIC.length, 12));
        cipher.updateAAD(MAGIC);
        byte[] plain = cipher.doFinal(raw, MAGIC.length + 12, raw.length - MAGIC.length - 12);
        try {
            JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (data.getInt("schema") != 1 || !settings(data).validate().isEmpty()) throw new IOException();
            return data;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    private static AiSettings settings(JSONObject value) {
        AiSettings defaults = AiSettings.defaults();
        return new AiSettings(value.optBoolean("enabled", false), value.optString("base_url", ""),
                value.optString("model", ""), value.optInt("max_output_tokens", defaults.maxOutputTokens),
                value.optInt("daily_call_limit", defaults.dailyCallLimit), value.optBoolean("legacy_token_limit", false));
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IOException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static boolean exists(AtomicFile file) {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }

    private static byte[] readBytes(AtomicFile file) throws IOException {
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int n;
            while ((n = in.read(bytes)) != -1) {
                if (out.size() + n > MAX_FILE) throw new IOException();
                out.write(bytes, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void write(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(data); stream.getFD().sync(); file.finishWrite(stream);
            // AtomicFile can report rename failures only to logcat; do not report a stale value as saved.
            if (!Arrays.equals(readBytes(file), data)) throw new IOException();
        } catch (IOException | RuntimeException e) {
            if (stream != null) file.failWrite(stream);
            throw new IOException();
        }
    }
}
