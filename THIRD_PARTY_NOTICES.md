# Third-party notices

## RikkaApps/Shizuku (Apache License 2.0)

The `direct` build flavor's wireless-debugging pairing/connection code is ported from
[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
(`manager/src/main/java/moe/shizuku/manager/adb/` and `manager/src/main/jni/adb_pairing.{cpp,h}`,
`logging.h`), licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0). Ported files:

- `app/src/direct/kotlin/com/mttd/data/adb/AdbKey.kt`
- `app/src/direct/kotlin/com/mttd/data/adb/AdbClient.kt`
- `app/src/direct/kotlin/com/mttd/data/adb/AdbPairingClient.kt`
- `app/src/direct/kotlin/com/mttd/data/adb/AdbMessage.kt`
- `app/src/direct/kotlin/com/mttd/data/adb/AdbProtocol.kt`
- `app/src/direct/kotlin/com/mttd/data/adb/AdbException.kt`
- `app/src/direct/jni/adb_pairing.cpp`
- `app/src/direct/jni/adb_pairing.h`
- `app/src/direct/jni/logging.h`
- `app/src/direct/jni/mttd_starter.cpp` (ported from `manager/src/main/jni/starter.cpp` and
  `misc.cpp` — root-only code paths removed, since mTTD's `direct` flavor only ever runs as the
  adb shell user; see the file's own header comment for the exact adaptation)

Adaptations are noted individually at the top of each file (package rename, JNI class path,
removed dependencies on Shizuku's own internal utility libraries, and the
`com.android.org.conscrypt` → `org.conscrypt` swap described below).

**2026-08-15 update**: mTTD's `direct` flavor *does* now spawn a second process — a shell-UID
daemon started via `app_process` (mirroring Shizuku's own bootstrap model), needed so file access
keeps working after the wireless-debugging connection drops (e.g. switching off Wi-Fi to LTE),
instead of depending on that adb connection staying open for every single file read. `mttd_starter.cpp`
above is the ported half of that (process spawn/backgrounding). The Kotlin side of the handoff
(`app/src/direct/kotlin/com/mttd/data/adb/starter/HiddenApis.kt`,
`DirectDaemonStarter.kt`, `DirectUserServiceProvider.kt`) is **original code, not a Shizuku port** —
it implements the same general pattern (hand a Binder to the app process via a `ContentProvider`
`call()`, since that's the standard Android mechanism for an arbitrary process to reach a specific
app's process over Binder) against AOSP's own `@hide` framework interfaces
(`IActivityManager`/`IContentProvider`), not against Shizuku's source, which wasn't available to
reference at the time.

```
Copyright (C) 2021 Rikka
Copyright (C) 2021 RikkaW

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Google Conscrypt (Apache License 2.0)

`org.conscrypt:conscrypt-android` is used in the `direct` flavor to call the public
`Conscrypt.exportKeyingMaterial` (RFC 5705 TLS exporter), needed to bind the SPAKE2 pairing
password to the TLS session per Android's wireless-debugging pairing protocol. Used as a
published Maven dependency, not vendored — see https://github.com/google/conscrypt.

## BoringSSL / `io.github.vvb2060.ndk:boringssl` and BouncyCastle

Native SPAKE2 primitives come from a prebuilt BoringSSL-for-Android package published as
`io.github.vvb2060.ndk:boringssl` (same artifact Shizuku's own build uses). Certificate/keypair
handling in `AdbKey.kt` uses `org.bouncycastle:bcpkix-jdk18on` (Bouncy Castle, MIT License).
