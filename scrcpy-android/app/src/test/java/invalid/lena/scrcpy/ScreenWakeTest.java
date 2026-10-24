package invalid.lena.scrcpy;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ScreenWakeTest {
    @Test public void wakeTargetsPhysicalScreenWithoutTogglingPowerOrUsingVirtualDisplay() throws Exception {
        ScreenWake.wake(command -> {
            assertTrue(command.startsWith("input -d 0 keyevent KEYCODE_WAKEUP;"));
            assertFalse(command.contains("KEYCODE_POWER"));
            assertFalse(command.contains("KEYCODE_SLEEP"));
            assertTrue(command.endsWith("echo padconnect_wake_exit:$?"));
            return "padconnect_wake_exit:0\r\n";
        });
    }

    @Test(expected = IOException.class)
    public void deniedWakeIsNotReportedAsSuccess() throws Exception {
        ScreenWake.wake(command -> "java.lang.SecurityException: Permission denied\npadconnect_wake_exit:1\n");
    }

    @Test(expected = IOException.class)
    public void closedStreamWithoutAcknowledgmentIsNotSuccess() throws Exception {
        ScreenWake.wake(command -> "");
    }

    @Test public void lostConnectionPropagatesToCaller() {
        IOException disconnected = new IOException("disconnected");
        try {
            ScreenWake.wake(command -> { throw disconnected; });
            fail("A disconnected tablet cannot be reported awake");
        } catch (Exception error) {
            assertSame(disconnected, error);
        }
    }
}
