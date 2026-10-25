package cn.local.tickethelper.core;

/** One visible accessibility node or OCR text block; never an inventory API record. */
public final class UiItem {
    public enum Source { NODE, OCR }
    public final String text;
    public final int left, top, right, bottom;
    public final boolean enabled, clickable, selected;
    public final Source source;
    public final String resourceId;

    public UiItem(String text, int left, int top, int right, int bottom,
                  boolean enabled, boolean clickable, boolean selected, Source source) {
        this(text, left, top, right, bottom, enabled, clickable, selected, source, "");
    }
    public UiItem(String text, int left, int top, int right, int bottom,
                  boolean enabled, boolean clickable, boolean selected, Source source, String resourceId) {
        this.text = text == null ? "" : text.trim();
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.enabled = enabled; this.clickable = clickable; this.selected = selected;
        this.source = source == null ? Source.OCR : source;
        this.resourceId = resourceId == null ? "" : resourceId;
    }
    public int centerX() { return left + (right - left) / 2; }
    public int centerY() { return top + (bottom - top) / 2; }
    public boolean hasBounds() { return right > left && bottom > top && left >= 0 && top >= 0; }
}
