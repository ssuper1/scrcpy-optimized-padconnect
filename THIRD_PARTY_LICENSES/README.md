# Additional third-party license texts

These files retain the notices shipped by the pinned upstream components.
They supplement, and do not replace, the root `LICENSE` / `NOTICE` and the
vendored libadb-android `COPYING` / `LICENSES` files.

- `Conscrypt-NOTICE.txt`, `Conscrypt-Netty.txt`, `Conscrypt-Harmony.txt`:
  unmodified texts from google/conscrypt tag `2.6.1`, respectively `NOTICE`,
  `licenses/LICENSE.netty.txt`, and `licenses/LICENSE.harmony.txt`.
- `Bouncy-Castle-LICENSE.html`: unmodified `LICENSE.html` from bcgit/bc-java
  tag `r1rv84`.
- `BoringSSL-LICENSE.txt`: unmodified `LICENSE` from google/boringssl commit
  `5ac7567c234514157a504ff3fbedc0f5eddbf678`, as identified by
  `org/conscrypt/conscrypt.properties` in the Conscrypt 2.6.1 AAR. This revision
  uses Apache-2.0; original per-file notices may also apply.
- `SPAKE2-Native-NOTICES.txt`: opening license comments reproduced from
  `spake2.c`, `ed25519.h`, `curve25519_32.h`, and `sha512.c` in
  MuntashirAkon/spake2-c commit
  `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`. File names identify the excerpts.
- `LGPL-2.1.txt`: unmodified GNU LGPL version 2.1 text from
  `https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt`.

Full LGPL-3.0 and GPL-3.0 texts are preserved in
`scrcpy-android/vendor/libadb-android/LICENSES/` and included in the bundled
notices. SPAKE2-Java 2.2.1's `LICENSE` matches that LGPL-3.0 text.

The `:remote:generateOpenSourceNotices` build task includes these `.txt` and
`.html` files and the other license files in the APK. The settings license
screen displays the generated document offline. Bouncy Castle's original
HTML license is included verbatim, including its markup.
