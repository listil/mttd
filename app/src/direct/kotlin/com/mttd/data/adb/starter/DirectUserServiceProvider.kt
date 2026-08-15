package com.mttd.data.adb.starter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * [DirectDaemonStarter] (shell UID, `app_process` 로 별도 기동)가 만든 [com.mttd.service.UserService]
 * 의 Binder를 앱 프로세스(여기, Zygote로 정상 기동된 쪽)로 받는 창구.
 *
 * ContentProvider 를 쓰는 이유: Binder 객체 자체는 이미 확립된 Binder IPC 채널을 통해서만
 * 프로세스 경계를 넘길 수 있는데, 서로 다른 두 프로세스(우리 shell UID 프로세스와 우리 앱
 * 프로세스) 사이엔 그런 채널이 처음엔 없다 — ContentProvider 는 ActivityManagerService 를 거쳐
 * 임의의 프로세스가 특정 앱의 provider 에 `call()` 을 걸 수 있게 해주는 표준 경로라 이 초기
 * 핸드셰이크에 쓸 수 있다(Shizuku 도 정확히 같은 이유로 이 패턴을 쓴다).
 *
 * 매니페스트 등록: `app/src/direct/AndroidManifest.xml`, authority는
 * `${applicationId}.direct.userservice`.
 */
class DirectUserServiceProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SEND_USER_SERVICE) return null
        // exported provider라 임의 앱이 호출을 시도할 수 있다 — [DirectDaemonStarter] 는 항상
        // shell UID(2000)로만 뜨므로, 그 외 호출자는 무시한다(가짜 Binder를 주입해 우리 앱이
        // 그걸 IUserService 인 것처럼 쓰게 만드는 걸 막기 위함).
        if (android.os.Binder.getCallingUid() != 2000) {
            Log.w(TAG, "call($method) rejected: unexpected calling uid ${android.os.Binder.getCallingUid()}")
            return null
        }
        val binder = extras?.getBinder(EXTRA_BINDER)
        if (binder == null) {
            Log.w(TAG, "call($method) received no binder extra")
            return null
        }
        Log.i(TAG, "received user service binder")
        onBinderReceived?.invoke(binder)
        return null
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "mTTD.DirectProvider"
        const val METHOD_SEND_USER_SERVICE = "sendUserService"
        const val EXTRA_BINDER = "binder"

        /** [DirectAdbManager][com.mttd.data.adb.DirectAdbManager] 가 시작 시 등록. */
        @Volatile
        var onBinderReceived: ((IBinder) -> Unit)? = null
    }
}
