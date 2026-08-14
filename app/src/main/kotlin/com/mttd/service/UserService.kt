package com.mttd.service

import android.util.Log
import com.mttd.IUserService
import com.mttd.data.GameFileAccessPolicy
import java.io.File
import java.io.RandomAccessFile

/**
 * Shizuku UserService — shell UID(2000) 안에서 실행되는 별도 프로세스.
 * 앱 프로세스는 이 클래스에 [android.os.Binder] 로만 접근 가능하며,
 * [IUserService] 에 정의된 op 만 호출할 수 있다.
 *
 * 보안 원칙:
 * - shell 커맨드 문자열 파라미터를 절대 받지 않는다.
 * - 파일 경로는 whitelist prefix 검사를 통과해야만 처리한다 ([GameFileAccessPolicy]).
 * - 미승인 경로에는 [SecurityException] 을 던져 감사 로그에 남긴다.
 */
class UserService : IUserService.Stub() {

    override fun getFileSize(path: String): Long {
        GameFileAccessPolicy.ensurePathAllowed(path)
        return try {
            val f = File(path)
            if (f.exists()) f.length() else -1L
        } catch (e: Exception) {
            Log.w(TAG, "getFileSize failed for $path", e)
            -1L
        }
    }

    override fun fileExists(path: String): Boolean {
        GameFileAccessPolicy.ensurePathAllowed(path)
        return try {
            File(path).exists()
        } catch (e: Exception) {
            false
        }
    }

    override fun readFileChunk(path: String, offset: Long, maxBytes: Int): ByteArray {
        GameFileAccessPolicy.ensurePathAllowed(path)
        require(offset >= 0) { "offset must be >= 0" }
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val limit = maxBytes.coerceAtMost(GameFileAccessPolicy.MAX_CHUNK_BYTES)

        return try {
            RandomAccessFile(path, "r").use { raf ->
                val size = raf.length()
                if (offset >= size) return ByteArray(0)
                raf.seek(offset)
                val toRead = ((size - offset).coerceAtMost(limit.toLong())).toInt()
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                buf
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFileChunk failed for $path @$offset+$limit", e)
            ByteArray(0)
        }
    }

    override fun listInstalledGamePackages(): String {
        // /sdcard/Android/data/<pkg>/ 를 stat 해서 존재 확인.
        // pm 커맨드 대신 파일시스템 존재 여부로 감지 (셸 회피).
        val found = mutableListOf<String>()
        for (pkg in GameFileAccessPolicy.knownGamePackages) {
            val dir = File("/sdcard/Android/data/$pkg/files/UE4Game")
            if (dir.exists()) found += pkg
        }
        return found.joinToString("\n")
    }

    override fun destroy() {
        Log.i(TAG, "destroy() called by Shizuku")
        System.exit(0)
    }

    companion object {
        private const val TAG = "mTTD.UserService"
    }
}
