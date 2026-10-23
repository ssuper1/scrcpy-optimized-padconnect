package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.AffineMatrix;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

public class NewDisplayCaptureTest {
    @Test
    public void lowVideoResolutionPreservesNativeAppCanvasAndDensity() throws Exception {
        NewDisplayCapture capture = capture("1080x2400/360", 1080);
        try {
            capture.prepare();
            assertEquals(new Size(1080, 2400), field(capture, "displaySize"));
            assertEquals(360, field(capture, "dpi"));
            assertEquals(new Size(480, 1080), capture.getSize());
        } finally {
            capture.release();
        }
    }

    @Test
    public void landscapeVideoKeepsItsAspectRatio() throws Exception {
        NewDisplayCapture capture = capture("2400x1080/360", 1080);
        try {
            capture.prepare();
            assertEquals(new Size(2400, 1080), field(capture, "displaySize"));
            assertEquals(new Size(1080, 480), capture.getSize());
        } finally {
            capture.release();
        }
    }

    @Test
    public void encoderFallbackDoesNotEnlargeAppUi() throws Exception {
        NewDisplayCapture capture = capture("1080x2400/360", 1080);
        try {
            capture.prepare();
            capture.applyNewVideoConstraints(new VideoConstraints(720, 8, null));
            capture.prepare();
            assertEquals(new Size(1080, 2400), field(capture, "displaySize"));
            assertEquals(360, field(capture, "dpi"));
            assertEquals(new Size(320, 720), capture.getSize());
        } finally {
            capture.release();
        }
    }

    @Test
    public void touchesMapFromEncodedFrameToFullCanvas() throws Exception {
        NewDisplayCapture capture = capture("1080x2400/360", 1080);
        try {
            capture.prepare();
            PositionMapper mapper = PositionMapper.create(capture.getSize(),
                    (AffineMatrix) field(capture, "eventTransform"), (Size) field(capture, "displaySize"));
            Point center = mapper.map(new Position(240, 540, 480, 1080));
            assertEquals(540, center.getX());
            assertEquals(1200, center.getY());
        } finally {
            capture.release();
        }
    }

    private static NewDisplayCapture capture(String display, int maxSize) {
        Options options = Options.parse(new String[]{"4.1", "new_display=" + display, "flex_display=true"});
        NewDisplayCapture capture = new NewDisplayCapture(null, options);
        capture.init(new VideoConstraints(maxSize, 8, null));
        return capture;
    }

    private static Object field(NewDisplayCapture capture, String name) throws Exception {
        Field field = NewDisplayCapture.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(capture);
    }
}
