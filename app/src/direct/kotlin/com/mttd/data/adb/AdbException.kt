// Ported from RikkaApps/Shizuku (manager/src/main/java/moe/shizuku/manager/adb/AdbException.kt),
// licensed under Apache License 2.0. See THIRD_PARTY_NOTICES.md.
package com.mttd.data.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
