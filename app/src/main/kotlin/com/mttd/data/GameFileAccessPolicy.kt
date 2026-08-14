package com.mttd.data

import android.util.Log
import java.io.File

/**
 * [IUserService][com.mttd.IUserService] 구현체가 공통으로 지켜야 하는 경로 whitelist.
 *
 * `shizuku` flavor(`UserService`, shell UID 프로세스)와 `direct` flavor(`DirectAdbManager`,
 * 같은 앱 프로세스에서 셸 명령을 직접 실행)가 각자 다른 경로로 파일에 접근하지만, **어느 쪽이든
 * 이 whitelist를 통과 못 한 경로는 절대 건드리면 안 된다** — 한쪽만 고치고 다른 쪽을 깜빡하면
 * 보안 경계가 갈라지므로 반드시 이 한 곳만 수정한다.
 */
object GameFileAccessPolicy {

    private const val TAG = "mTTD.FileAccessPolicy"

    const val MAX_CHUNK_BYTES = 262_144 // 256 KB

    /** 접근 허용 경로 prefix. 게임 로그 디렉토리만 허용. 추후 게임 패키지가 추가되면 여기 확장. */
    val allowedPathPrefixes = listOf(
        "/sdcard/Android/data/com.xindong.torchlight/",
        "/sdcard/Android/data/com.xd.TLglobal/",
        "/sdcard/Android/data/com.xd.TLglobalTap/",
        "/storage/emulated/0/Android/data/com.xindong.torchlight/",
        "/storage/emulated/0/Android/data/com.xd.TLglobal/",
        "/storage/emulated/0/Android/data/com.xd.TLglobalTap/",
    )

    /** 알려진 게임 패키지 whitelist ([listInstalledGamePackages][com.mttd.IUserService.listInstalledGamePackages] 반환값 필터). */
    val knownGamePackages = setOf(
        "com.xindong.torchlight",
        "com.xd.TLglobal",
        "com.xd.TLglobalTap",
    )

    /** 통과 못 하면 [SecurityException]. */
    fun ensurePathAllowed(path: String) {
        val normalized = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            path
        }
        val ok = allowedPathPrefixes.any {
            normalized.startsWith(it) || normalized.startsWith(it.removePrefix("/storage/emulated/0"))
        }
        if (!ok) {
            Log.w(TAG, "path denied: $normalized")
            throw SecurityException("path not allowed: $normalized")
        }
    }
}
