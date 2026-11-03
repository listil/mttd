package com.mttd.data.prices

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 게임 내 경매장에서 **직접 조회한** 시세.
 *
 * 서버 스냅샷([PriceRepository])보다 우선한다. 유저가 방금 눈으로 본 호가라
 * 스냅샷보다 최신이고, 스냅샷에 없는 아이템도 커버되기 때문.
 *
 * 로그의 `XchgSearchPrice` 응답은 **최저가순 호가 목록**(관측상 100 건)을 주고
 * 수량 필드는 없다. 단가는 **가장 싼 [LOW_SAMPLE_COUNT] 건의 평균**으로 잡는다 — 100 건 전체
 * 평균은 팔 생각 없이 걸어둔 비싼 호가 몇 건에 끌려 올라가서, 실제로 팔리는 가격과 동떨어진다
 * (2026-10-31 실기기: 명왕의 신격: 심판이 TTD 6.9 인데 전체 평균 9,183 으로 잡혔다).
 *
 * 관측은 [EXPIRY_MS] 가 지나면 무시한다. 앱 프로세스가 며칠씩 살아 있으면 오래전 조회값이
 * 스냅샷을 계속 덮어써서, 같은 날 실기기에서 65 시간 전 조회값이 그대로 쓰이고 있었다.
 *
 * 앱 프로세스 메모리에만 둔다. 재시작하면 사라지고 다시 스냅샷 가격을 쓴다.
 */
class ObservedPriceStore(
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _prices = MutableStateFlow<Map<String, Observed>>(emptyMap())
    val prices: StateFlow<Map<String, Observed>> = _prices.asStateFlow()

    /**
     * 경매장 조회 결과 1건 기록.
     *
     * @param unitPrices 응답의 `unitPrices` 배열 (최저가순)
     */
    fun record(
        itemId: String,
        unitPrices: List<Double>,
        observedAtMs: Long,
        currencyId: String? = null,
    ) {
        if (itemId.isBlank() || unitPrices.isEmpty()) return
        val sorted = unitPrices.sorted()
        val low = sorted.take(LOW_SAMPLE_COUNT)
        val avg = low.sum() / low.size
        if (avg <= 0.0 || !avg.isFinite()) return

        val entry = Observed(
            itemId = itemId,
            avgPrice = avg.toFloat(),
            samples = low.size,
            lowest = sorted.first().toFloat(),
            highest = low.last().toFloat(),
            observedAtMs = observedAtMs,
            currencyId = currencyId,
        )
        val cutoff = now() - EXPIRY_MS
        _prices.value = _prices.value.filterValues { it.observedAtMs >= cutoff } + (itemId to entry)
        // JVM 단위 테스트에선 android.util.Log 가 스텁이라 던진다 — 로그 때문에 기록이 실패하면 안 된다.
        runCatching { Log.i(TAG, "observed $itemId avg=%.4f from ${low.size}/${unitPrices.size} lowest listings".format(avg)) }
    }

    /** 관측 가격. 없거나 [EXPIRY_MS] 가 지났으면 null → 호출측이 스냅샷 가격으로 폴백. */
    fun priceOf(itemId: String?): Float? {
        val o = itemId?.let { _prices.value[it] } ?: return null
        if (now() - o.observedAtMs > EXPIRY_MS) return null
        return o.avgPrice.takeIf { it > 0f }
    }

    /** 아직 만료되지 않은 관측만 (UI 표시용). */
    fun active(): Map<String, Observed> {
        val cutoff = now() - EXPIRY_MS
        return _prices.value.filterValues { it.observedAtMs >= cutoff }
    }

    fun clear() {
        _prices.value = emptyMap()
    }

    data class Observed(
        val itemId: String,
        /** 단가 = 가장 싼 [LOW_SAMPLE_COUNT] 건 호가의 평균. */
        val avgPrice: Float,
        /** 평균을 낸 호가 건수 (최대 [LOW_SAMPLE_COUNT]). */
        val samples: Int,
        val lowest: Float,
        /** 평균에 들어간 호가 중 가장 비싼 것. */
        val highest: Float,
        val observedAtMs: Long,
        /** 호가 화폐. 결정(100300) 만 기록되고 가루(100200) 거래는 제외된다. */
        val currencyId: String? = null,
    )

    companion object {
        private const val TAG = "mTTD.Observed"
        const val LOW_SAMPLE_COUNT = 20
        const val EXPIRY_MS = 24L * 60 * 60 * 1000
    }
}
