// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package io.github.muntashirakon.adb;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class PairingGuidTest {
    @Test public void readsOnlyNullTerminatedGuidNotPadding() throws Exception {
        byte[] peer = new byte[8191];
        byte[] guid = "adb-tablet-ABC123".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(guid, 0, peer, 0, guid.length);
        peer[guid.length + 1] = 1;
        assertEquals("adb-tablet-ABC123", PairingConnectionCtx.parsePeerGuid(peer));
    }

    @Test public void rejectsMissingTerminatorAndEmptyGuid() {
        assertThrows(IOException.class, () -> PairingConnectionCtx.parsePeerGuid(new byte[8191]));
        assertThrows(IOException.class, () -> PairingConnectionCtx.parsePeerGuid(new byte[0]));
        assertThrows(IOException.class, () -> PairingConnectionCtx.parsePeerGuid(new byte[]{'a', 'b'}));
    }

    @Test public void rejectsControlCharactersAndNonAscii() {
        for (byte invalid : new byte[]{'\n', ' ', '\t', (byte) 0xff}) {
            assertThrows(IOException.class, () -> PairingConnectionCtx.parsePeerGuid(new byte[]{'a', invalid, 0}));
        }
    }

    @Test public void capsGuidLength() throws Exception {
        byte[] boundary = new byte[256];
        Arrays.fill(boundary, 0, 255, (byte) 'a');
        assertEquals(255, PairingConnectionCtx.parsePeerGuid(boundary).length());
        byte[] oversized = new byte[257];
        Arrays.fill(oversized, 0, 256, (byte) 'a');
        assertThrows(IOException.class, () -> PairingConnectionCtx.parsePeerGuid(oversized));
    }
}
