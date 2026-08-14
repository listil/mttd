package com.mttd.data.access

import com.mttd.IUserService
import kotlinx.coroutines.flow.StateFlow

/**
 * 게임 로그 파일에 접근할 [IUserService] 를 제공하는 계층의 공통 계약.
 *
 * 두 개의 독립 구현이 있다 (flavor로 분리, 동시에 컴파일되지 않음):
 * - `shizuku` flavor: [com.mttd.data.shizuku.ShizukuManager] — Shizuku 경유.
 * - `direct` flavor: `com.mttd.data.adb.DirectAdbManager` — 무선 디버깅 직접 연결.
 *
 * [TrackerForegroundService][com.mttd.service.TrackerForegroundService] 등 `main`에 있는
 * 소비자는 이 인터페이스 모양만 알면 되고, 어느 구현이 실제로 붙는지는 flavor가 결정한다.
 */
interface PrivilegedAccessManager {
    /** 파일을 읽을 준비가 됐는가 (Shizuku: 4단계 모두 통과 / direct: 페어링+연결 완료). */
    val ready: StateFlow<Boolean>

    /** 준비됐을 때만 non-null. */
    val service: IUserService?

    /** [android.app.Application.onCreate] 에서 한 번. */
    fun start()

    /** 조용히 재시도 — 다이얼로그/화면 전환 없음. 백그라운드 부트스트랩 재시도 루프용. */
    fun retryConnect()

    /** 유저가 직접 트리거 — 필요하면 권한 요청/페어링 화면 등 UI를 띄울 수 있음. */
    fun requestOrReconnect()
}
