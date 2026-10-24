package invalid.lena.scrcpy;

import java.io.IOException;

/** Wake the physical display, independently of scrcpy's virtual input display. */
final class ScreenWake {
    interface Shell {
        String run(String command) throws Exception;
    }

    private ScreenWake() {}

    static void wake(Shell shell) throws Exception {
        // WAKEUP is idempotent; POWER would turn an already awake device off.
        // The shell protocol has no exit status, so acknowledge it explicitly.
        String output = shell.run("input -d 0 keyevent KEYCODE_WAKEUP; echo padconnect_wake_exit:$?");
        if (output == null || !output.trim().equals("padconnect_wake_exit:0")) {
            throw new IOException("Tablet did not accept the screen wake command");
        }
    }
}
