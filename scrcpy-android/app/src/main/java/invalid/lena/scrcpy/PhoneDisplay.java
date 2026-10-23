package invalid.lena.scrcpy;

/** Geometry for a phone-shaped virtual display; independent of Android for testing. */
public final class PhoneDisplay {
    public final int width, height, dpi;

    private PhoneDisplay(int width, int height, int dpi) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
    }

    public static PhoneDisplay fit(int width, int height, int textScale) {
        if (width < 128 || height < 128
                || !isValidTextScale(textScale)) {
            throw new IllegalArgumentException("invalid phone display geometry");
        }
        // Keep the app canvas near the controller's native pixel dimensions.
        // Low video quality must not create a tiny display that magnifies
        // cached bitmaps or controls sized in raw pixels. Encoding is separate.
        double scale = Math.min(1.0, 4096.0 / Math.max(width, height));
        int w = Math.max(128, (int) Math.round(width * scale / 8) * 8);
        int h = Math.max(128, (int) Math.round(height * scale / 8) * 8);
        // A 360 dp short edge requests a phone layout rather than a tablet layout.
        int density = Math.max(72, Math.round(Math.min(w, h) * 160f / 360 * textScale / 100));
        return new PhoneDisplay(w, h, density);
    }

    public String serverValue() {
        return width + "x" + height + "/" + dpi;
    }

    public static int videoMaxSize(int configuredMaxSize) {
        if (configuredMaxSize < 0) throw new IllegalArgumentException("invalid video limit");
        return configuredMaxSize == 0 ? 2160 : configuredMaxSize;
    }

    public static boolean isValidTextScale(int value) {
        return value == 75 || value == 85 || value == 100 || value == 125 || value == 150;
    }
}
