package invalid.lena.scrcpy;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Base64;
import org.junit.Test;

public class RemoteAppsTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=");

    @Test public void matchesIconRecordsByPackageEvenWhenTheirOrderDiffers() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - 设置  com.android.settings\n - 天气  com.vivo.weather\n"
                + "PADCONNECT_APPS_END\n" + iconRecord("com.vivo.weather", PNG)
                + iconRecord("com.android.settings", PNG));
        assertEquals("com.android.settings", apps.get(0).packageName);
        assertArrayEquals(PNG, apps.get(0).iconPng);
        assertArrayEquals(PNG, apps.get(1).iconPng);
        assertArrayEquals(PNG, RemoteApps.filterAndPin(apps, "weather",
                Collections.singleton("com.vivo.weather")).get(0).iconPng);
    }

    @Test public void brokenOrMissingIconsLeaveAppsAvailable() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - 设置  com.android.settings\n - 天气  com.vivo.weather\n"
                + "PADCONNECT_ICON com.android.settings !!!bad-base64!!!\n"
                + iconRecord("com.vivo.weather", new byte[]{1, 2, 3}));
        assertEquals(2, apps.size());
        assertNull(apps.get(0).iconPng);
        assertNull(apps.get(1).iconPng);
        assertEquals("com.vivo.weather", RemoteApps.filter(apps, "weather").get(0).packageName);
    }

    @Test public void oversizedImageDimensionsAndPayloadAreIgnored() throws Exception {
        byte[] oversized = PNG.clone();
        Wire.writeBe32(oversized, 16, 16384);
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - App  com.example.app\n"
                + iconRecord("com.example.app", oversized));
        assertNull(apps.get(0).iconPng);
        apps = RemoteApps.parse(" - App  com.example.app\n"
                + iconRecord("com.example.app", new byte[32769]));
        assertNull(apps.get(0).iconPng);
    }

    @Test public void unknownPackagesAndDuplicateIconsDoNotChangeTheAppList() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - App  com.example.app\n"
                + iconRecord("com.example.unknown", PNG)
                + iconRecord("com.example.app", PNG)
                + "PADCONNECT_ICON com.example.app invalid\n");
        assertEquals(1, apps.size());
        assertArrayEquals(PNG, apps.get(0).iconPng);
    }

    private static String iconRecord(String packageName, byte[] png) {
        return "PADCONNECT_ICON " + packageName + " " + Base64.getEncoder().encodeToString(png) + "\n";
    }

    @Test public void favoritesPreserveGroupOrderWithoutChangingSource() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - 设置  com.android.settings\n"
                + " - 天气  com.vivo.weather\n - 浏览器  com.example.browser\n - 相机  com.example.camera\n");
        Set<String> favorites = new HashSet<>(Arrays.asList("com.example.camera", "com.vivo.weather"));
        List<RemoteApps.Entry> result = RemoteApps.filterAndPin(apps, "", favorites);
        assertEquals(Arrays.asList(apps.get(1), apps.get(3), apps.get(0), apps.get(2)), result);
        assertEquals("com.android.settings", apps.get(0).packageName);
        assertEquals("com.vivo.weather", apps.get(1).packageName);
    }

    @Test public void searchOnlyPinsMatchingFavorites() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - Weather Lite  com.example.weather\n"
                + " - 设置  com.android.settings\n - WEATHER Pro  com.example.pro\n");
        Set<String> favorites = new HashSet<>(Arrays.asList("com.android.settings", "com.example.pro"));
        assertEquals(Arrays.asList(apps.get(2), apps.get(0)),
                RemoteApps.filterAndPin(apps, " WeAtHeR ", favorites));
        assertTrue(RemoteApps.filterAndPin(apps, "missing", favorites).isEmpty());
    }

    @Test public void unfavoritingRestoresOrderAndUnknownFavoritesDoNotCreateApps() throws Exception {
        List<RemoteApps.Entry> apps = RemoteApps.parse(" - A  com.example.a\n - B  com.example.b\n");
        Set<String> favorites = new HashSet<>(Arrays.asList("com.example.b", "com.example.uninstalled"));
        assertEquals(apps.get(1), RemoteApps.filterAndPin(apps, "", favorites).get(0));
        favorites.remove("com.example.b");
        assertEquals(apps, RemoteApps.filterAndPin(apps, "", favorites));
        assertTrue(RemoteApps.filterAndPin(Collections.emptyList(), "", favorites).isEmpty());
    }

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
