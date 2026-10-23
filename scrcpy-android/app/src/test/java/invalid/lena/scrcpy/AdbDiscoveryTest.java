package invalid.lena.scrcpy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AdbDiscoveryTest {
    private static final String GUID = "adb-tablet-ABC123";
    private final Devices.Device tablet = new Devices.Device("tablet", "192.168.1.10", 30001, GUID);

    @Test public void onlyWirelessConnectServiceIsAccepted() {
        assertTrue(AdbDiscovery.isConnectType("_adb-tls-connect._tcp"));
        assertTrue(AdbDiscovery.isConnectType("_adb-tls-connect._tcp."));
        assertFalse(AdbDiscovery.isConnectType("_adb-tls-pairing._tcp."));
        assertFalse(AdbDiscovery.isConnectType("_adb._tcp."));
        assertFalse(AdbDiscovery.isConnectType(null));
    }

    @Test public void recognizesDeviceAfterBothIpAndPortChange() {
        AdbDiscovery.Endpoint found = new AdbDiscovery.Endpoint(GUID, "192.168.2.20", 40002);
        assertSame(found, AdbDiscovery.match(tablet, List.of(found)));
    }

    @Test public void sameIpWithOtherGuidIsNotOurTablet() {
        assertNull(AdbDiscovery.match(tablet, List.of(
                new AdbDiscovery.Endpoint("adb-other-ABC123", tablet.host, 40002))));
    }

    @Test public void doesNotGuessGuidForLegacyDevice() {
        assertNull(AdbDiscovery.match(new Devices.Device(tablet.host, tablet.port), List.of(
                new AdbDiscovery.Endpoint(GUID, tablet.host, 40002))));
    }

    @Test public void exactGuidRequiredNotSerialPrefix() {
        assertNull(AdbDiscovery.match(tablet, List.of(
                new AdbDiscovery.Endpoint(GUID + "-other", tablet.host, 40002))));
    }

    @Test public void dnsNamesAreCaseInsensitiveAndPreferPreviousAddress() {
        AdbDiscovery.Endpoint v6 = new AdbDiscovery.Endpoint(GUID, "fe80::1%wlan0", 40002);
        AdbDiscovery.Endpoint v4 = new AdbDiscovery.Endpoint("ADB-TABLET-abc123", tablet.host, 40002);
        assertSame(v4, AdbDiscovery.match(tablet, List.of(v6, v4)));
    }

    @Test public void rejectsInvalidResolvedEndpoints() {
        assertNull(AdbDiscovery.match(tablet, List.of(
                new AdbDiscovery.Endpoint(GUID, "", 1),
                new AdbDiscovery.Endpoint(GUID, "bad host", 1),
                new AdbDiscovery.Endpoint(GUID, tablet.host, 0),
                new AdbDiscovery.Endpoint(GUID, tablet.host, 65536))));
    }

    @Test public void scopedIpv6CanBeUsedAndDisplayed() {
        AdbDiscovery.Endpoint ipv6 = new AdbDiscovery.Endpoint(GUID, "fe80::12%wlan0", 40002);
        assertSame(ipv6, AdbDiscovery.match(tablet, List.of(ipv6)));
        assertEquals("[fe80::12%wlan0]:40002", tablet.at(ipv6.host, ipv6.port).toString());
    }
}
