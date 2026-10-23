package invalid.lena.scrcpy;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class DeviceConnectorTest {
    private final Devices.Device saved = new Devices.Device("tablet", "192.168.1.10", 30001, "adb-tablet-ABC123");

    private final class FakeConnection implements DeviceConnector.Connection {
        final List<String> attempts = new ArrayList<>();
        String guid = saved.guid;
        boolean failFirst, failQuery;
        Runnable onConnect = () -> {};

        @Override public void connect(String host, int port) throws Exception {
            attempts.add(host + ":" + port);
            onConnect.run();
            if (failFirst && attempts.size() == 1) throw new IOException("old advertisement");
        }
        @Override public String readGuid() throws IOException {
            if (failQuery) throw new IOException("getprop unavailable");
            return guid;
        }
    }

    @Test public void connectsNewAddressAndKeepsStableIdentity() throws Exception {
        FakeConnection connection = new FakeConnection();
        Devices.Device updated = DeviceConnector.connect(saved, saved.at("192.168.2.20", 40002), connection, () -> false);
        assertEquals(List.of("192.168.2.20:40002"), connection.attempts);
        assertEquals(saved.id, updated.id);
        assertEquals(saved.guid, updated.guid);
        assertEquals(40002, updated.port);
    }

    @Test public void staleAdvertisementFallsBackToSavedAddress() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.failFirst = true;
        Devices.Device updated = DeviceConnector.connect(saved, saved.at(saved.host, 40002), connection, () -> false);
        assertEquals(List.of("192.168.1.10:40002", "192.168.1.10:30001"), connection.attempts);
        assertEquals(saved, updated);
    }

    @Test public void migratesLegacyIdentityAfterSuccessfulConnection() throws Exception {
        Devices.Device legacy = saved.withGuid(null);
        Devices.Device updated = DeviceConnector.connect(legacy, legacy, new FakeConnection(), () -> false);
        assertEquals(saved.guid, updated.guid);
        assertEquals(legacy.id, updated.id);
    }

    @Test public void identityMismatchCannotStartServerOrProduceUpdatedRow() {
        FakeConnection connection = new FakeConnection();
        connection.guid = "adb-someone-else";
        assertThrows(IOException.class, () -> DeviceConnector.connect(
                saved, saved.at(saved.host, 40002), connection, () -> false));
    }

    @Test public void pairedNewAddressStillConnectsWhenPropertyQueryFails() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.failQuery = true;
        Devices.Device updated = DeviceConnector.connect(
                saved, saved.at(saved.host, 40002), connection, () -> false);
        assertEquals(40002, updated.port);
        assertEquals(saved.guid, updated.guid);
        assertEquals(saved.id, updated.id);
    }

    @Test public void unavailablePropertyNeverClearsExistingPairingIdentity() throws Exception {
        for (String guid : new String[]{null, "", "bad\nidentity", "x".repeat(256)}) {
            FakeConnection connection = new FakeConnection();
            connection.guid = guid;
            Devices.Device updated = DeviceConnector.connect(
                    saved, saved.at(saved.host, 40002), connection, () -> false);
            assertEquals(40002, updated.port);
            assertEquals(saved.guid, updated.guid);
            assertEquals(saved.id, updated.id);
        }
    }

    @Test public void wifiToggleCanUpdateBothIpAndPortWithoutReadableSystemProperty() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.guid = null;
        Devices.Device updated = DeviceConnector.connect(
                saved, saved.at("192.168.2.20", 50003), connection, () -> false);
        List<Devices.Device> list = new ArrayList<>(List.of(saved));
        assertSame(updated, Devices.replaceIn(list, saved, updated));
        Devices.Device restored = Devices.parse(Devices.serialize(list)).get(0);
        AdbDiscovery.Endpoint next = new AdbDiscovery.Endpoint(saved.guid, "192.168.2.20", 50004);
        assertSame(next, AdbDiscovery.match(restored, List.of(next)));
        assertEquals(saved.id, restored.id);
        assertEquals(saved.guid, restored.guid);
    }

    @Test public void differentDiscoveryGuidCannotUseUnavailablePropertyAsFallback() {
        FakeConnection connection = new FakeConnection();
        connection.guid = null;
        Devices.Device other = saved.at(saved.host, 40002).withGuid("adb-other-DEF456");
        assertThrows(IOException.class, () -> DeviceConnector.connect(saved, other, connection, () -> false));
        assertTrue(connection.attempts.isEmpty());
    }

    @Test public void unreadablePropertyDoesNotBreakExistingLegacyAddress() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.failQuery = true;
        Devices.Device legacy = saved.withGuid(null);
        assertSame(legacy, DeviceConnector.connect(legacy, legacy, connection, () -> false));
    }

    @Test public void legacyRowCannotAdoptNewAddressByDiscoveryGuess() {
        FakeConnection connection = new FakeConnection();
        Devices.Device legacy = saved.withGuid(null);
        assertThrows(IOException.class, () -> DeviceConnector.connect(
                legacy, legacy.at(saved.host, 40002), connection, () -> false));
        assertTrue(connection.attempts.isEmpty());
    }

    @Test public void cancellationBeforeConnectDoesNotOpenSocket() {
        FakeConnection connection = new FakeConnection();
        assertThrows(InterruptedException.class, () -> DeviceConnector.connect(saved, saved, connection, () -> true));
        assertTrue(connection.attempts.isEmpty());
    }

    @Test public void cancellationDuringConnectCannotPersistAnEndpoint() {
        FakeConnection connection = new FakeConnection();
        AtomicBoolean cancelled = new AtomicBoolean();
        connection.onConnect = () -> cancelled.set(true);
        assertThrows(InterruptedException.class, () -> DeviceConnector.connect(
                saved, saved.at(saved.host, 40002), connection, cancelled::get));
        assertEquals(1, connection.attempts.size());
    }

    @Test public void mismatchedLocalDeviceIdCannotConnect() {
        FakeConnection connection = new FakeConnection();
        Devices.Device other = new Devices.Device("other", saved.host, saved.port, saved.guid);
        assertThrows(IOException.class, () -> DeviceConnector.connect(saved, other, connection, () -> false));
        assertTrue(connection.attempts.isEmpty());
    }
}
