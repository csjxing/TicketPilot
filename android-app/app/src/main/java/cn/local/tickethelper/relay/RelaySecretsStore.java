package cn.local.tickethelper.relay;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Independent, non-backup credential; never exposes the bearer token in connection metadata. */
public final class RelaySecretsStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "ticketpilot_foresight_relay_v1";
    private static final byte[] MAGIC = {'T', 'P', 'R', 'L', 1};
    private final AtomicFile file;
    public static final class Connection {
        public final RelayEndpoint endpoint;
        public final String binding;
        Connection(RelayEndpoint endpoint, String binding) {
            if (endpoint == null || !uuid(binding)) throw new IllegalArgumentException();
            this.endpoint = endpoint; this.binding = binding;
        }
        public boolean same(Connection other) { return other != null && endpoint.equals(other.endpoint) && binding.equals(other.binding); }
    }
    public RelaySecretsStore(Context context) {
        file = new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), "foresight-relay.enc"));
    }
    public Connection load() throws IOException {
        synchronized (LOCK) {
            if (!exists()) return null;
            try { return connection(read()); }
            catch (Exception error) { throw new IOException("中转站连接无法读取，请重新连接；没有上传或清除任何诊断。"); }
        }
    }
    Connection save(RelayEndpoint endpoint, String token) throws IOException {
        if (!RelayEndpoint.validToken(token)) throw new IOException("请输入网站颁发的完整 Key。");
        synchronized (LOCK) {
            try {
                Connection result = new Connection(endpoint, UUID.randomUUID().toString());
                write(new JSONObject().put("schema", 1).put("origin", endpoint.origin).put("binding", result.binding).put("token", token));
                return result;
            } catch (Exception error) { throw new IOException("中转站连接未保存，请重试。"); }
        }
    }
    String token(Connection expected) throws IOException {
        synchronized (LOCK) {
            try {
                JSONObject data = read(); if (expected == null || !expected.same(connection(data))) throw new IOException();
                return data.getString("token");
            } catch (Exception error) { throw new IOException("中转站连接已变化，请重新核对后上传。"); }
        }
    }
    public void clear() throws IOException {
        synchronized (LOCK) {
            try {
                file.delete(); if (exists()) throw new IOException();
                KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
            } catch (Exception error) { throw new IOException("中转站授权尚未移除，请稍后重试。"); }
        }
    }
    private static Connection connection(JSONObject data) throws Exception {
        if (data.length() != 4 || data.getInt("schema") != 1 || !RelayEndpoint.validToken(data.getString("token"))) throw new IOException();
        return new Connection(new RelayEndpoint(data.getString("origin")), data.getString("binding"));
    }
    private JSONObject read() throws Exception {
        byte[] bytes = readBytes();
        if (bytes.length < MAGIC.length + 28 || !Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC)) throw new IOException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, bytes, MAGIC.length, 12)); cipher.updateAAD(MAGIC);
        byte[] plain = cipher.doFinal(bytes, MAGIC.length + 12, bytes.length - MAGIC.length - 12);
        try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private void write(JSONObject value) throws Exception {
        byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(MAGIC);
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain); if (iv.length != 12) throw new IOException();
            byte[] bytes = ByteBuffer.allocate(MAGIC.length + 12 + encrypted.length).put(MAGIC).put(iv).put(encrypted).array();
            FileOutputStream out = null;
            try { out = file.startWrite(); out.write(bytes); out.getFD().sync(); file.finishWrite(out);
                if (!Arrays.equals(bytes, readBytes())) throw new IOException();
            } catch (Exception error) { if (out != null) file.failWrite(out); throw error; }
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private byte[] readBytes() throws IOException {
        try (InputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > 8192) throw new IOException(); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    private boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists(); }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value) && UUID.fromString(value).version() == 4; }
        catch (Exception error) { return false; }
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
