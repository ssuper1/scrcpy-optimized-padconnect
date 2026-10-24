// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DevicesTest {

    @Test
    public void parseEmptyOrNull() {
        assertTrue(Devices.parse(null).isEmpty());
        assertTrue(Devices.parse("").isEmpty());
        assertTrue(Devices.parse("   ").isEmpty());
    }

    @Test
    public void parseNonArrayFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> Devices.parse("{not json"));
        assertThrows(IllegalArgumentException.class,
                () -> Devices.parse("{\"host\":\"x\"}"));
    }

    @Test
    public void parseSkipsMalformedRowsKeepsValid() {
        // Middle row missing 'port' - must NOT nuke the other two.
        String json = "["
                + "{\"host\":\"a\",\"port\":1},"
                + "{\"host\":\"bad\"},"
                + "{\"host\":\"c\",\"port\":3}"
                + "]";
        List<Devices.Device> got = Devices.parse(json);
        assertEquals(2, got.size());
        assertEquals("a", got.get(0).host);
        assertEquals("c", got.get(1).host);
    }

    @Test
    public void roundTrip() {
        List<Devices.Device> in = new ArrayList<>(Arrays.asList(
                new Devices.Device("192.168.1.10", 5555),
                new Devices.Device("10.0.0.2",     43210)));
        String json = Devices.serialize(in);
        List<Devices.Device> out = Devices.parse(json);
        assertEquals(2, out.size());
        assertEquals("192.168.1.10", out.get(0).host);
        assertEquals(5555,           out.get(0).port);
        assertEquals("10.0.0.2",     out.get(1).host);
        assertEquals(43210,          out.get(1).port);
    }

    @Test
    public void serializeRejectsInvalidRows() {
        assertThrows(IllegalStateException.class, () -> Devices.serialize(Arrays.asList(
                new Devices.Device("bad host", 5555))));
        assertThrows(IllegalStateException.class, () -> Devices.serialize(Arrays.asList(
                new Devices.Device("target", 0))));
    }

    @Test
    public void parseAddressSplitsHostAndPort() {
        Devices.Device d = Devices.parseAddress("  192.168.1.42:41234 ");
        assertEquals("192.168.1.42", d.host);
        assertEquals(41234, d.port);

        Devices.Device v6 = Devices.parseAddress("[fe80::1]:5555");
        assertEquals("fe80::1", v6.host);
        assertEquals(5555, v6.port);
        // Round-trips through the display form.
        assertEquals("[fe80::1]:5555", v6.toString());
    }

    @Test
    public void parseAddressRejectsMalformed() {
        // No port, empty host, non-numeric and out-of-range ports.
        assertNull(Devices.parseAddress(null));
        assertNull(Devices.parseAddress(""));
        assertNull(Devices.parseAddress("192.168.1.42"));
        assertNull(Devices.parseAddress(":5555"));
        assertNull(Devices.parseAddress("192.168.1.42:"));
        assertNull(Devices.parseAddress("192.168.1.42:port"));
        assertNull(Devices.parseAddress("192.168.1.42:0"));
        assertNull(Devices.parseAddress("192.168.1.42:65536"));
        // Unbracketed IPv6 would otherwise split at the wrong colon.
        assertNull(Devices.parseAddress("fe80::1:5555"));
        assertNull(Devices.parseAddress("[fe80::1]5555"));
    }

    @Test
    public void parsePortRejectsNonDigits() {
        assertEquals(5555, Devices.parsePort(" 5555 "));
        assertEquals(1,     Devices.parsePort("1"));
        assertEquals(65535, Devices.parsePort("65535"));
        assertEquals(-1, Devices.parsePort(""));
        assertEquals(-1, Devices.parsePort("+5555"));
        assertEquals(-1, Devices.parsePort("-1"));
        assertEquals(-1, Devices.parsePort("55x5"));
        assertEquals(-1, Devices.parsePort("655360"));
    }

    @Test
    public void deviceEqualsByHostAndPort() {
        Devices.Device a = new Devices.Device("1.1.1.1", 5555);
        Devices.Device b = new Devices.Device("1.1.1.1", 5555);
        Devices.Device c = new Devices.Device("1.1.1.1", 5556);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(false, a.equals(c));
    }

    @Test public void legacyMigrationKeepsIdAcrossReadsAndAddressChanges() {
        String json = "[{\"host\":\"192.168.1.10\",\"port\":30001}]";
        Devices.Device first = Devices.parse(json).get(0);
        assertEquals(first.id, Devices.parse(json).get(0).id);
        assertNull(first.guid);
        Devices.Device migrated = first.at("192.168.2.20", 40002).withGuid("adb-tablet-ABC123");
        Devices.Device restored = Devices.parse(Devices.serialize(List.of(migrated))).get(0);
        assertEquals(first.id, restored.id);
        assertEquals(migrated.guid, restored.guid);
        assertEquals(migrated, restored);
    }

    @Test public void repairingSameGuidUpdatesOneRowAndKeepsId() {
        Devices.Device original = new Devices.Device("192.168.1.10", 30001).withGuid("adb-tablet-ABC123");
        List<Devices.Device> list = new ArrayList<>(List.of(original));
        Devices.upsertIn(list, new Devices.Device("192.168.2.20", 40002).withGuid(original.guid));
        assertEquals(1, list.size());
        assertEquals(original.id, list.get(0).id);
        assertEquals(40002, list.get(0).port);
    }

    @Test public void repairingLegacyAddressBindsGuidWithoutDuplicatingRow() {
        Devices.Device legacy = new Devices.Device("192.168.1.10", 30001);
        List<Devices.Device> list = new ArrayList<>(List.of(legacy));
        Devices.upsertIn(list, new Devices.Device(legacy.host, legacy.port).withGuid("adb-tablet-ABC123"));
        assertEquals(1, list.size());
        assertEquals(legacy.id, list.get(0).id);
        assertEquals("adb-tablet-ABC123", list.get(0).guid);
    }

    @Test public void staleDiscoveryCannotRestoreForgottenDevice() {
        Devices.Device device = new Devices.Device("192.168.1.10", 30001);
        List<Devices.Device> list = new ArrayList<>();
        assertNull(Devices.replaceIn(list, device, device.at(device.host, 40002)));
        assertTrue(list.isEmpty());
    }

    @Test public void staleDiscoveryCannotOverwriteManualAddressEdit() {
        Devices.Device original = new Devices.Device("192.168.1.10", 30001);
        Devices.Device edited = original.at(original.host, 50003);
        List<Devices.Device> list = new ArrayList<>(List.of(edited));
        assertNull(Devices.replaceIn(list, original, original.at(original.host, 40002)));
        assertSame(edited, list.get(0));
    }

    @Test public void manualEditPreservesGuidAndLocalId() {
        Devices.Device original = new Devices.Device("192.168.1.10", 30001).withGuid("adb-tablet-ABC123");
        List<Devices.Device> list = new ArrayList<>(List.of(original));
        Devices.Device updated = Devices.replaceIn(list, original, original.at("192.168.2.20", 40002));
        assertEquals(original.guid, updated.guid);
        assertEquals(original.id, updated.id);
        assertEquals(1, list.size());
    }

    @Test public void sameIpAndPortDoesNotMergeDifferentKnownDevices() {
        Devices.Device first = new Devices.Device("192.168.1.10", 30001).withGuid("adb-first-ABC123");
        Devices.Device second = new Devices.Device(first.host, first.port).withGuid("adb-second-DEF456");
        List<Devices.Device> list = new ArrayList<>(List.of(first));
        Devices.upsertIn(list, second);
        assertEquals(2, list.size());
        assertNotEquals(first.id, second.id);
        Devices.Device updated = second.at(second.host, 40002);
        Devices.replaceIn(list, second, updated);
        assertSame(first, list.get(0));
        assertSame(updated, list.get(1));
    }

    @Test public void boundIdentityCannotBeReplacedOrClearedByAddressUpdate() {
        Devices.Device device = new Devices.Device("192.168.1.10", 30001).withGuid("adb-first-ABC123");
        List<Devices.Device> list = new ArrayList<>(List.of(device));
        assertNull(Devices.replaceIn(list, device, device.withGuid("adb-second-DEF456")));
        assertNull(Devices.replaceIn(list, device, device.withGuid(null)));
        assertSame(device, list.get(0));
    }

    @Test public void invalidGuidDoesNotDiscardUsableLegacyEndpoint() {
        Devices.Device device = Devices.parse("[{\"host\":\"tablet\",\"port\":30001,\"guid\":\"bad\\nidentity\"}]").get(0);
        assertNull(device.guid);
        assertEquals("tablet", device.host);
    }
}
