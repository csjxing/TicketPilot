package cn.local.tickethelper.sync;

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
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A distinct Keystore credential; never shares the AI key or participates in backup/exports. */
public final class SyncSecretsStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "ticketpilot_device_sync_v1";
    private static final byte[] MAGIC = {'T', 'P', 'S', 'Y', 1};
    private final AtomicFile file;

    /** Public connection metadata, intentionally does not carry a device token. */
    public static final class Connection {
        public final SyncEndpoint endpoint;
        public final String deviceId, expiresAt;
        public final boolean autoUpload;
        Connection(SyncEndpoint endpoint, String deviceId, String expiresAt, boolean autoUpload) {
            if (endpoint == null || !DiagnosticReport.uuid(deviceId) || expiresAt == null || !expiresAt.endsWith("Z"))
                throw new IllegalArgumentException("Invalid sync connection");
            Instant.parse(expiresAt);
            this.endpoint = endpoint; this.deviceId = deviceId; this.expiresAt = expiresAt; this.autoUpload = autoUpload;
        }
        public boolean expired(long nowMs) { return !Instant.parse(expiresAt).isAfter(Instant.ofEpochMilli(nowMs)); }
        public boolean sameReceiver(Connection other) {
            return other != null && endpoint.equals(other.endpoint) && deviceId.equals(other.deviceId) && expiresAt.equals(other.expiresAt);
        }
    }

    public SyncSecretsStore(Context context) {
        file = new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), "device-sync.enc"));
    }
    public Connection load() throws IOException {
        synchronized (LOCK) {
            if (!exists()) return null;
            try { return connection(read()); }
            catch (Exception error) { throw new IOException("云端连接无法读取，请重新配对；尚未上传诊断。"); }
        }
    }
    /** A new pairing always starts with auto upload disabled. No token reuse across receivers. */
    void savePaired(SyncEndpoint endpoint, String deviceId, String token, String expiresAt) throws IOException {
        if (!validToken(token)) throw new IOException("云端返回了无效授权，连接未保存。");
        synchronized (LOCK) {
            try {
                Connection value = new Connection(endpoint, deviceId, expiresAt, false);
                JSONObject data = new JSONObject().put("schema", 1).put("baseUrl", value.endpoint.baseUrl)
                        .put("deviceId", value.deviceId).put("deviceToken", token).put("expiresAt", value.expiresAt).put("autoUpload", false);
                write(data);
            } catch (Exception error) { throw new IOException("云端连接未保存，请重试配对。"); }
        }
    }
    public void setAutoUpload(boolean enabled) throws IOException {
        synchronized (LOCK) {
            try {
                JSONObject data = read(); Connection current = connection(data);
                if (enabled && current.expired(System.currentTimeMillis())) throw new IOException();
                data.put("autoUpload", enabled); write(data);
            } catch (Exception error) { throw new IOException("自动上传设置未保存，请检查云端连接。"); }
        }
    }
    String readTokenFor(Connection expected) throws IOException {
        synchronized (LOCK) {
            try {
                JSONObject data = read(); Connection current = connection(data);
                if (expected == null || !expected.sameReceiver(current) || current.expired(System.currentTimeMillis())) throw new IOException();
                String token = DiagnosticReport.string(data, "deviceToken"); if (!validToken(token)) throw new IOException(); return token;
            } catch (Exception error) { throw new IOException("云端连接已变化或过期，请重新配对。"); }
        }
    }
    public void clear() throws IOException {
        synchronized (LOCK) {
            try {
                file.delete(); if (exists()) throw new IOException();
                KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
            } catch (Exception error) { throw new IOException("无法移除本机云端连接，请稍后重试。"); }
        }
    }
    static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{32,128}"); }
    private static Connection connection(JSONObject data) throws Exception {
        DiagnosticReport.keys(data, Set.of("schema", "baseUrl", "deviceId", "deviceToken", "expiresAt", "autoUpload"));
        DiagnosticReport.integer(data, "schema", 1, 1);
        if (!validToken(DiagnosticReport.string(data, "deviceToken"))) throw new IOException();
        return new Connection(new SyncEndpoint(DiagnosticReport.string(data, "baseUrl")), DiagnosticReport.string(data, "deviceId"),
                DiagnosticReport.string(data, "expiresAt"), DiagnosticReport.bool(data, "autoUpload"));
    }
    private JSONObject read() throws Exception {
        byte[] data = readBytes();
        if (data.length < MAGIC.length + 12 + 16 || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) throw new IOException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, data, MAGIC.length, 12)); cipher.updateAAD(MAGIC);
        byte[] plain = cipher.doFinal(data, MAGIC.length + 12, data.length - MAGIC.length - 12);
        try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private void write(JSONObject data) throws Exception {
        byte[] plain = data.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(MAGIC);
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain); if (iv.length != 12) throw new IOException();
            byte[] payload = ByteBuffer.allocate(MAGIC.length + 12 + encrypted.length).put(MAGIC).put(iv).put(encrypted).array();
            FileOutputStream out = null;
            try {
                out = file.startWrite(); out.write(payload); out.getFD().sync(); file.finishWrite(out);
                if (!Arrays.equals(readBytes(), payload)) throw new IOException();
            } catch (Exception error) { if (out != null) file.failWrite(out); throw new IOException(); }
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists(); }
    private byte[] readBytes() throws IOException {
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = in.read(buffer)) != -1) { if (out.size() + count > 8192) throw new IOException(); out.write(buffer, 0, count); }
            return out.toByteArray();
        }
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
}
