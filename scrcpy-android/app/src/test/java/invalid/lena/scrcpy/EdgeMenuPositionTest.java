package invalid.lena.scrcpy;

import static org.junit.Assert.*;
import org.junit.Test;

public class EdgeMenuPositionTest {
    @Test public void snapsToBothEdgesAndAvoidsCameraCutout() {
        EdgeMenuPosition bounds = new EdgeMenuPosition(2400, 1080, 72, 144, 108, 0, 0, 0);
        assertEquals(108, bounds.x(true));
        assertEquals(2328, bounds.x(false));
        assertEquals(0, bounds.y(0));
        assertEquals(936, bounds.y(1000));
    }

    @Test public void savedRelativeHeightSurvivesRotation() {
        EdgeMenuPosition portrait = new EdgeMenuPosition(1080, 2400, 72, 144, 0, 108, 0, 0);
        EdgeMenuPosition landscape = new EdgeMenuPosition(2400, 1080, 72, 144, 108, 0, 0, 0);
        int saved = portrait.permille(portrait.y(800));
        assertEquals(800, saved, 1);
        assertEquals(800, landscape.permille(landscape.y(saved)), 1);
    }

    @Test public void corruptedHeightCannotPlaceHandleOffScreen() {
        EdgeMenuPosition bounds = new EdgeMenuPosition(1080, 2400, 72, 144, 0, 108, 0, 0);
        assertEquals(108, bounds.y(-200));
        assertEquals(2256, bounds.y(1200));
        assertEquals(0, bounds.permille(-100));
        assertEquals(1000, bounds.permille(4000));
    }

    @Test public void tinyWindowKeepsPositionNonNegative() {
        EdgeMenuPosition bounds = new EdgeMenuPosition(40, 80, 72, 144, 108, 108, 108, 108);
        assertEquals(0, bounds.x(true));
        assertEquals(0, bounds.x(false));
        assertEquals(0, bounds.y(500));
        assertEquals(500, bounds.permille(0));
    }

    @Test public void draggingAcrossMiddleChangesSnapEdge() {
        EdgeMenuPosition bounds = new EdgeMenuPosition(1080, 2400, 72, 144, 0, 0, 0, 0);
        assertTrue(bounds.nearestLeft(200));
        assertFalse(bounds.nearestLeft(800));
        assertFalse(bounds.nearestLeft(504));
    }

    @Test public void largeInsetsCannotInvertMovementBounds() {
        EdgeMenuPosition bounds = new EdgeMenuPosition(300, 400, 72, 144, 260, 300, 260, 300);
        assertTrue(bounds.maxX >= bounds.minX);
        assertTrue(bounds.maxY >= bounds.minY);
        assertTrue(bounds.y(1000) <= 256);
        assertTrue(bounds.x(false) <= 228);
    }
}
