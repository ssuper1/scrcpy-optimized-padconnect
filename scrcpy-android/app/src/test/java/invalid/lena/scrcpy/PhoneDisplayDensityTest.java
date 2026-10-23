package invalid.lena.scrcpy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneDisplayDensityTest {
    @Test public void identifiesOnlyValidSecondaryDisplayRecords() {
        assertEquals(5, PhoneDisplayDensity.findDisplayId(
                "[server] INFO: New display: 1080x2400/360 (id=5)\n"));
        assertEquals(-1, PhoneDisplayDensity.findDisplayId("New display: 1080x2400/360 (id=0)"));
        assertEquals(-1, PhoneDisplayDensity.findDisplayId("New display: 1080x2400/360 (id=999999999999)"));
        assertEquals(-1, PhoneDisplayDensity.findDisplayId("Device: test (id=4)"));
    }

    @Test public void readsOverrideInsteadOfPhysicalDensity() throws Exception {
        assertEquals(360, PhoneDisplayDensity.effectiveDensity(
                "Physical density: 480\r\nOverride density: 360\r\n"));
        assertEquals(480, PhoneDisplayDensity.effectiveDensity("Physical density: 480\n"));
    }

    @Test public void alreadyAppliedDensityDoesNotWrite() throws Exception {
        List<String> commands = new ArrayList<>();
        assertEquals(360, PhoneDisplayDensity.ensure(7, 360, command -> {
            commands.add(command);
            return "Physical density: 480\nOverride density: 360\n";
        }));
        assertEquals(List.of("wm density -d 7"), commands);
    }

    @Test public void correctsIgnoredDensityAndRequiresReadback() throws Exception {
        List<String> commands = new ArrayList<>();
        String[] replies = {"Physical density: 480\n", "", "Physical density: 480\nOverride density: 360\n"};
        assertEquals(360, PhoneDisplayDensity.ensure(7, 360, command -> {
            commands.add(command);
            return replies[commands.size() - 1];
        }));
        assertEquals(List.of("wm density -d 7", "wm density 360 -d 7", "wm density -d 7"), commands);
    }

    @Test(expected = IOException.class)
    public void silentWriteFailureIsNotReportedAsSuccess() throws Exception {
        PhoneDisplayDensity.ensure(7, 360, command ->
                command.equals("wm density 360 -d 7") ? "" : "Physical density: 480\n");
    }

    @Test(expected = IOException.class)
    public void rejectedWriteIsNotReportedAsSuccess() throws Exception {
        PhoneDisplayDensity.ensure(7, 360, command ->
                command.equals("wm density 360 -d 7") ? "Error: permission denied" : "Physical density: 480\n");
    }

    @Test public void refusesMainDisplayAndMalformedQueries() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> PhoneDisplayDensity.ensure(0, 360, command -> failShell()));
        for (String output : List.of("", "Physical density: 0\n", "Physical density: 999999999999\n",
                "Error: rejected\nPhysical density: 360\n", "Physical density: 360\nPhysical density: 480\n")) {
            assertThrows(IOException.class, () -> PhoneDisplayDensity.effectiveDensity(output));
        }
    }

    private String failShell() {
        fail("A command must never target the main display");
        return "";
    }
}
