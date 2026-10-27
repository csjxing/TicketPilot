package cn.local.tickethelper.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** In-memory digest of the entire OCR crop. No pixels, text or screenshot are retained or exported. */
public final class VisualFrameProof {
    private static final int MAX_PIXELS = 8_000_000;
    private final int width, height;
    private final byte[] digest;
    private VisualFrameProof(int width, int height, byte[] digest) {
        this.width = width; this.height = height; this.digest = digest;
    }
    public static VisualFrameProof capture(int width, int height, int[] pixels) {
        byte[] digest = digest(width, height, pixels);
        return digest == null ? null : new VisualFrameProof(width, height, digest);
    }
    public boolean matches(int width, int height, int[] pixels) {
        if (this.width != width || this.height != height) return false;
        byte[] current = digest(width, height, pixels);
        return current != null && MessageDigest.isEqual(digest, current);
    }
    private static byte[] digest(int width, int height, int[] pixels) {
        long length = (long) width * height;
        if (width <= 0 || height <= 0 || length > MAX_PIXELS || pixels == null || pixels.length != length) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[8192];
            int used = 0;
            for (int pixel : pixels) {
                chunk[used++] = (byte) (pixel >>> 24); chunk[used++] = (byte) (pixel >>> 16);
                chunk[used++] = (byte) (pixel >>> 8); chunk[used++] = (byte) pixel;
                if (used == chunk.length) { digest.update(chunk); used = 0; }
            }
            if (used > 0) digest.update(chunk, 0, used);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { return null; }
    }
}
