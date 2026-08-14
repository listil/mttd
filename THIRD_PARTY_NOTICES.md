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

Adaptations are noted individually at the top of each file (package rename, JNI class path,
removed dependencies on Shizuku's own internal utility libraries, and the
`com.android.org.conscrypt` → `org.conscrypt` swap described below). No files from Shizuku's
`manager`/`server`/`starter` process-spawning machinery are used — mTTD's `direct` flavor never
spawns a second process; it talks to the device's own `adbd` directly from the app's own process.

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
