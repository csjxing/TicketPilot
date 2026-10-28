package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import cn.local.tickethelper.core.UiItem;
import cn.local.tickethelper.core.Observation;
import cn.local.tickethelper.core.VisualFrameProof;
import cn.local.tickethelper.adapter.DamaiSkuAdapter;
import cn.local.tickethelper.catalog.CatalogSelection;
import java.util.ArrayList;
import java.util.List;

/** Bundled Chinese OCR. Pixels remain in memory and are released after each result. */
final class OcrReader implements AutoCloseable {
    interface Callback { void complete(List<UiItem> items, String error); }
    interface EvidenceCallback { void complete(List<UiItem> items, Observation.VerifiedSelection evidence, String error); }
    interface CatalogCallback { void complete(List<UiItem> items, Observation.VerifiedSelection evidence, CatalogSelection catalogSelection, String error); }
    interface GuardedCallback { void complete(List<UiItem> items, Observation.VerifiedSelection evidence, VisualFrameProof proof, String error); }
    interface GuardedCatalogCallback { void complete(List<UiItem> items, Observation.VerifiedSelection evidence, CatalogSelection catalogSelection, VisualFrameProof proof, String error); }
    interface VerifyCallback { void complete(boolean matches); }
    private final AccessibilityService service;
    private final TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private boolean busy;
    private boolean closed;
    private long lastScreenshotAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    OcrReader(AccessibilityService service) { this.service = service; }
    boolean isBusy() { return busy; }

    void read(Callback callback) {
        read(List.of(), 0, (items, evidence, error) -> callback.complete(items, error));
    }

    void read(List<UiItem> nodes, long observedAtMs, EvidenceCallback callback) {
        readCatalog(nodes, observedAtMs, (items, evidence, catalogSelection, error) -> callback.complete(items, evidence, error));
    }

    void readCatalog(List<UiItem> nodes, long observedAtMs, CatalogCallback callback) {
        readCatalogGuarded(nodes, observedAtMs, (items, evidence, selection, proof, error) ->
                callback.complete(items, evidence, selection, error));
    }

    void readGuarded(List<UiItem> nodes, long observedAtMs, GuardedCallback callback) {
        readCatalogGuarded(nodes, observedAtMs, (items, evidence, selection, proof, error) ->
                callback.complete(items, evidence, proof, error));
    }

    void readCatalogGuarded(List<UiItem> nodes, long observedAtMs, GuardedCatalogCallback callback) {
        if (busy || closed) { callback.complete(List.of(), null, null, null, closed ? "reader_closed" : "ocr_busy"); return; }
        busy = true;
        lastScreenshotAt = SystemClock.elapsedRealtime();
        try { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                Bitmap pixels = null;
                try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("No screenshot");
                    Bitmap full = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    hardware.recycle();
                    int top = dimension("status_bar_height"), bottom = dimension("navigation_bar_height");
                    int height = full.getHeight() - top - bottom;
                    if (height <= 0) { full.recycle(); throw new IllegalStateException("Invalid bounds"); }
                    pixels = Bitmap.createBitmap(full, 0, top, full.getWidth(), height);
                    if (pixels != full) full.recycle();
                    Bitmap captured = pixels;
                    recognizer.process(InputImage.fromBitmap(captured, 0)).addOnSuccessListener(text -> {
                        List<UiItem> items = new ArrayList<>(), amountElements = new ArrayList<>();
                        for (com.google.mlkit.vision.text.Text.TextBlock block : text.getTextBlocks()) {
                            for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                                Rect b = line.getBoundingBox();
                                if (b != null) items.add(new UiItem(line.getText(), b.left, b.top + top, b.right, b.bottom + top,
                                        true, false, false, UiItem.Source.OCR));
                                for (com.google.mlkit.vision.text.Text.Element element : line.getElements()) {
                                    Rect e = element.getBoundingBox();
                                    if (e != null) amountElements.add(new UiItem(element.getText(), e.left, e.top + top,
                                            e.right, e.bottom + top, true, false, false, UiItem.Source.OCR));
                                }
                            }
                        }
                        DamaiSkuAdapter.Result adapted;
                        VisualFrameProof proof;
                        try {
                            int width = captured.getWidth(), pixelHeight = captured.getHeight();
                            int[] frame = new int[width * pixelHeight];
                            captured.getPixels(frame, 0, width, 0, 0, width, pixelHeight);
                            proof = VisualFrameProof.capture(width, pixelHeight, frame);
                            DamaiSkuAdapter.Pixels sameFrame = new DamaiSkuAdapter.Pixels() {
                                @Override public int width() { return width; }
                                @Override public int height() { return pixelHeight + top + bottom; }
                                @Override public int argb(int x, int y) {
                                    int localY = y - top;
                                    return x < 0 || x >= width || localY < 0 || localY >= pixelHeight ? 0 : frame[localY * width + x];
                                }
                            };
                            adapted = DamaiSkuAdapter.adapt(nodes, items, amountElements, sameFrame, observedAtMs);
                            if ((service.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                                    && adapted.evidence != null && adapted.evidence.selectedPrice != null
                                    && adapted.evidence.totalYuan == null) {
                                android.util.Log.d("TicketPilotSku", adapted.totalCheckCode.name());
                                android.util.Log.d("TicketPilotSku", DamaiSkuAdapter.totalGeometryDiagnostic(nodes, amountElements, width));
                            }
                        } catch (RuntimeException invalidFrame) {
                            if (!captured.isRecycled()) captured.recycle();
                            busy = false; callback.complete(List.of(), null, null, null, "sku_adapter_failed"); return;
                        }
                        captured.recycle(); busy = false;
                        callback.complete(adapted.items, adapted.evidence, adapted.catalogSelection, proof, null);
                    }).addOnFailureListener(error -> {
                        captured.recycle(); busy = false;
                        callback.complete(List.of(), null, null, null, "ocr_failed");
                    });
                } catch (Exception failure) {
                    if (pixels != null && !pixels.isRecycled()) pixels.recycle();
                    busy = false; callback.complete(List.of(), null, null, null, "screenshot_failed");
                }
            }
            @Override public void onFailure(int errorCode) {
                busy = false;
                callback.complete(List.of(), null, null, null, errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                        ? "secure_window" : "screenshot_unavailable");
            }
        }); } catch (RuntimeException unavailable) {
            busy = false; callback.complete(List.of(), null, null, null, "screenshot_unavailable");
        }
    }

    /** One extra local frame only when content events invalidated an otherwise unchanged read.
     * Includes the whole app crop so a newly appearing OCR-only dialog cannot escape the comparison.
     * The caller must still check the original age, window and event generation after this callback. */
    void verifyFrame(VisualFrameProof proof, java.util.function.BooleanSupplier allowed, VerifyCallback callback) {
        if (busy || closed || proof == null || !permitted(allowed)) { callback.complete(false); return; }
        busy = true;
        // Android throttles takeScreenshot; do not turn ordinary throttling into a new UI failure.
        long delay = Math.max(0, 400 - (SystemClock.elapsedRealtime() - lastScreenshotAt));
        handler.postDelayed(() -> {
            if (closed || !permitted(allowed)) { busy = false; callback.complete(false); return; }
            lastScreenshotAt = SystemClock.elapsedRealtime();
            try { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    Bitmap full = null;
                    boolean matches = false;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) {
                            try { full = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                            finally { hardware.recycle(); }
                        }
                        if (full != null) {
                            int top = dimension("status_bar_height"), bottom = dimension("navigation_bar_height");
                            int width = full.getWidth(), height = full.getHeight() - top - bottom;
                            if (height > 0 && (long) width * height <= 8_000_000) {
                                int[] frame = new int[width * height];
                                full.getPixels(frame, 0, width, 0, top, width, height);
                                matches = proof.matches(width, height, frame);
                            }
                        }
                    } catch (RuntimeException invalidFrame) { matches = false; }
                    finally { if (full != null && !full.isRecycled()) full.recycle(); busy = false; }
                    callback.complete(matches);
                }
                @Override public void onFailure(int errorCode) { busy = false; callback.complete(false); }
            }); } catch (RuntimeException unavailable) { busy = false; callback.complete(false); }
        }, delay);
    }
    private static boolean permitted(java.util.function.BooleanSupplier guard) {
        try { return guard != null && guard.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
    private int dimension(String name) {
        int id = service.getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : service.getResources().getDimensionPixelSize(id);
    }
    @Override public void close() { closed = true; handler.removeCallbacksAndMessages(null); recognizer.close(); }
}
