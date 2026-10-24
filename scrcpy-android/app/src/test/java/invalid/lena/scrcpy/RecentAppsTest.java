package invalid.lena.scrcpy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RecentAppsTest {
    @Test public void reopeningAnAppMovesItToFrontWithoutDuplicatingIt() {
        List<String> previous = Arrays.asList("com.example.one", "com.example.two", "com.example.three");
        assertEquals(Arrays.asList("com.example.two", "com.example.one", "com.example.three"),
                RecentApps.record(previous, "com.example.two"));
        assertEquals("com.example.one", previous.get(0));
    }

    @Test public void historyIsBoundedAndDropsMalformedSavedValues() {
        List<String> previous = new ArrayList<>(Arrays.asList(null, "", "?settings", "com.example.fresh"));
        for (int i = 0; i < 30; i++) previous.add("com.example.app" + i);
        List<String> result = RecentApps.record(previous, "com.example.fresh");
        assertEquals(RecentApps.LIMIT, result.size());
        assertEquals("com.example.fresh", result.get(0));
        assertEquals("com.example.app18", result.get(19));
    }

    @Test public void recentSelectionKeepsHistoryOrderAndDropsRemovedAppsAndDuplicates() {
        RemoteApps.Entry one = new RemoteApps.Entry("One", "com.example.one");
        RemoteApps.Entry two = new RemoteApps.Entry("Two", "com.example.two");
        List<RemoteApps.Entry> apps = Arrays.asList(one, two);
        assertEquals(Arrays.asList(two, one), RecentApps.select(apps, Arrays.asList(
                "com.example.missing", "com.example.two", "com.example.one", "com.example.two")));
        assertTrue(RecentApps.select(apps, Collections.emptyList()).isEmpty());
    }

    @Test public void searchDoesNotReorderRecentApps() {
        RemoteApps.Entry one = new RemoteApps.Entry("Example One", "com.example.one");
        RemoteApps.Entry two = new RemoteApps.Entry("Example Two", "com.example.two");
        List<RemoteApps.Entry> recent = RecentApps.select(Arrays.asList(one, two),
                Arrays.asList("com.example.two", "com.example.one"));
        assertEquals(Arrays.asList(two, one), RemoteApps.filter(recent, "example"));
        assertEquals(Collections.singletonList(one), RemoteApps.filter(recent, "one"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLaunchCannotEnterHistory() {
        RecentApps.record(Collections.emptyList(), "+?settings");
    }
}
