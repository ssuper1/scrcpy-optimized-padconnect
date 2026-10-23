package invalid.lena.scrcpy;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public class RemoteAppsTest {
    @Test public void parsesUnicodeSystemAndUserApps() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse("[server] INFO: List of apps:\r\n"
                + " * 设置                             com.android.settings\r\n"
                + " - My Game                         com.example.game\r\n");
        assertEquals(2, apps.size());
        assertEquals("设置", apps.get(0).label);
        assertEquals("com.android.settings", apps.get(0).packageName);
        assertEquals("My Game", apps.get(1).label);
    }

    @Test public void parsesServerWrappedLongNames() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse("List of apps:\n"
                + " - An application with a very long title\n"
                + "                                  com.example.longname\n");
        assertEquals("An application with a very long title", apps.get(0).label);
        assertEquals("com.example.longname", apps.get(0).packageName);
    }

    @Test public void ignoresLogLinesAndDeduplicatesPackages() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse("[server] INFO: Starting\n"
                + " - App  com.example.app\n - App  com.example.app\n");
        assertEquals(1, apps.size());
    }

    @Test(expected = IOException.class)
    public void serverFailureDoesNotBecomeAnEmptyPicker() throws Exception {
        RemoteApps.parse("[server] ERROR: Permission denied");
    }

    @Test public void searchesInsidePackageNamesAndLabels() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - 天气  com.vivo.weather\n"
                + " - My Game  com.example.game\n");
        assertEquals("com.vivo.weather", RemoteApps.filter(apps, "WEATHER").get(0).packageName);
        assertEquals("com.example.game", RemoteApps.filter(apps, "Game").get(0).packageName);
        assertEquals(2, RemoteApps.filter(apps, "  ").size());
        assertEquals(2, apps.size());
        assertTrue(RemoteApps.filter(apps, "missing").isEmpty());
    }
}
