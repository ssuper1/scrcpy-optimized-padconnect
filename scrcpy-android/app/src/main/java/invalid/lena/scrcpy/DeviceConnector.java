package invalid.lena.scrcpy;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Connect only saved targets or endpoints matched to their saved discovery identity. */
final class DeviceConnector {
    interface Connection {
        void connect(String host, int port) throws Exception;
        String readGuid() throws Exception;
    }

    private DeviceConnector() {}

    static Devices.Device connect(Devices.Device saved, Devices.Device discovered,
                                  Connection connection, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (!saved.id.equals(discovered.id)
                || !java.util.Objects.equals(saved.guid, discovered.guid)
                || (saved.guid == null && !saved.equals(discovered))) {
            throw new IOException("Discovery target does not match saved device");
        }
        Devices.Device endpoint = discovered;
        try {
            connection.connect(endpoint.host, endpoint.port);
        } catch (Exception e) {
            checkCancelled(cancelled);
            if (e instanceof InterruptedException || endpoint.equals(saved)) throw e;
            endpoint = saved;
            try {
                connection.connect(endpoint.host, endpoint.port);
            } catch (Exception fallback) {
                fallback.addSuppressed(e);
                throw fallback;
            }
        }
        checkCancelled(cancelled);
        String guid;
        try {
            guid = Devices.normalizeGuid(connection.readGuid());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            checkCancelled(cancelled);
            // Pairing already supplied the GUID, and NSD matched it before dialing.
            // Some OEM systems do not expose this property to the ADB shell. An
            // optional property query must not reject a successful paired connection.
            Log.w("could not read optional device GUID; keeping paired identity: %s", e);
            guid = null;
        }
        if (guid != null && saved.guid != null && !saved.guid.equals(guid)) {
            throw new IOException("Device identity changed; pair this device again");
        }
        checkCancelled(cancelled);
        return guid == null ? endpoint : endpoint.withGuid(guid);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Device connection cancelled");
        }
    }
}
