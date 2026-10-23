package invalid.lena.scrcpy;

import static org.junit.Assert.*;
import org.junit.Test;

public class PhoneDisplayTest {
    @Test public void tallPhoneRetainsItsRatioWithoutTabletLayout() {
        PhoneDisplay display = PhoneDisplay.fit(1440, 3140, 100);
        assertEquals(1440, display.width);
        assertEquals(3144, display.height);
        assertEquals(0, display.width % 8);
        assertEquals(1440.0 / 3140, display.width / (double) display.height, 0.008);
        double shortEdgeDp = display.width * 160.0 / display.dpi;
        assertEquals(360, shortEdgeDp, 3);
        assertTrue(shortEdgeDp < 600);
    }

    @Test public void rotatingPhoneSwapsDimensionsAndKeepsDensity() {
        PhoneDisplay portrait = PhoneDisplay.fit(1080, 2340, 100);
        PhoneDisplay landscape = PhoneDisplay.fit(2340, 1080, 100);
        assertEquals(portrait.width, landscape.height);
        assertEquals(portrait.height, landscape.width);
        assertEquals(portrait.dpi, landscape.dpi);
    }

    @Test public void largeTextIncreasesLogicalUiSizeWithoutChangingVideoSize() {
        PhoneDisplay standard = PhoneDisplay.fit(1080, 2340, 100);
        PhoneDisplay large = PhoneDisplay.fit(1080, 2340, 150);
        assertEquals(standard.width, large.width);
        assertEquals(standard.height, large.height);
        assertEquals(1.5, large.dpi / (double) standard.dpi, 0.01);
    }

    @Test public void renderAndVideoLimitsAreIndependent() {
        PhoneDisplay display = PhoneDisplay.fit(2160, 4800, 100);
        assertEquals(4096, display.height);
        assertEquals(2160, PhoneDisplay.videoMaxSize(0));
        assertEquals(1080, PhoneDisplay.videoMaxSize(1080));
        assertEquals(480, PhoneDisplay.videoMaxSize(480));
    }

    @Test public void compactTextShowsMoreContentWithoutChangingVideoResolution() {
        PhoneDisplay standard = PhoneDisplay.fit(1080, 2340, 100);
        for (int scale : new int[]{75, 85}) {
            PhoneDisplay compact = PhoneDisplay.fit(1080, 2340, scale);
            assertEquals(standard.width, compact.width);
            assertEquals(standard.height, compact.height);
            assertEquals(scale / 100.0, compact.dpi / (double) standard.dpi, 0.01);
            assertTrue(compact.width * 160.0 / compact.dpi < 600);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedTextScaleIsRejected() {
        PhoneDisplay.fit(1080, 2340, 50);
    }

    @Test public void smallPhoneIsNotUpscaled() {
        PhoneDisplay display = PhoneDisplay.fit(480, 960, 100);
        assertEquals(480, display.width);
        assertEquals(960, display.height);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unmeasuredViewportIsRejected() {
        PhoneDisplay.fit(0, 0, 100);
    }
}
