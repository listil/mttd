package com.mttd.data.prices

/**
 * ETOR 시즌 선택 모드.
 *
 * ETOR 시즌ID 는 정규/하드코어가 완전히 별개 체인이다(예: 정규 1501, 하드코어 1531 — 고정
 * `+30` 오프셋, `PriceRepository` 참고). 항상 유저가 직접 고른다 — 로그에서 접속 캐릭터의
 * 시즌을 읽어 자동 전환하는 걸 시도했었지만(2026-08-22), 역방향 스캔과 실시간 감지 사이
 * 순서 보장이 없어 재접속 타이밍에 따라 최신 감지값이 stale 스캔 결과로 덮어써지는 등
 * 신뢰성 문제가 있어 걷어냈다.
 */
enum class SeasonMode(
    val id: String,
    val label: String,
    val description: String,
) {
    REGULAR(
        id = "regular",
        label = "정규",
        description = "정규 시즌 시세",
    ),
    HARDCORE(
        id = "hardcore",
        label = "하드코어",
        description = "하드코어 시즌 시세",
    ),
    ;

    companion object {
        fun fromId(id: String?): SeasonMode = entries.firstOrNull { it.id == id } ?: REGULAR
    }
}
