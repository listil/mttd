package com.mttd.data.items

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TTD 에서 파생한 `assets/item_names_ko.json` 로더.
 *
 * 앱 시작 시 1회 로드 (~113 KB). itemId → (koName, koType) 매핑.
 */
class ItemInfoLookup(context: Context) {

    private val map: Map<String, ItemInfo>

    /**
     * 번들 자산에 없는 itemId 를 위한 런타임 보완 사전. TTD 시세 fetch 가 이름/타입을
     * 같이 내려주는데 [com.mttd.data.prices.PriceRepository] 는 원래 그걸 버리고 가격만
     * 쓰므로, TTD 가 실제로 fetch 될 때(=사용자가 TTD 시세 소스를 쓸 때) 그 부산물로
     * 채워진다 — 이 클래스가 능동적으로 네트워크를 타는 일은 없다.
     * 번들 자산이 항상 우선한다 (신뢰도 높은 큐레이션 데이터, 세션 간 이름 흔들림 방지).
     */
    private val gapFill = java.util.concurrent.ConcurrentHashMap<String, ItemInfo>()

    init {
        map = try {
            val raw = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, ItemInfo>>(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load $ASSET_PATH", t)
            emptyMap()
        }
        Log.i(TAG, "loaded ${map.size} item names")
    }

    fun lookup(itemId: String): ItemInfo? = map[itemId] ?: gapFill[itemId]

    /** UI 라벨용 안전 표시. 없으면 fallback. */
    fun displayName(itemId: String?): String = when {
        itemId == null -> "Unknown"
        else -> lookup(itemId)?.name ?: "Unknown (id=$itemId)"
    }

    fun displayType(itemId: String?): String? = itemId?.let { lookup(it)?.type }

    /**
     * 번들 자산에 없는 id 만 채운다 (번들이 항상 우선). 아이콘은 채우지 않음 —
     * TTD 응답엔 img 가 없고, 검증 안 된 소스로 기존 아이콘을 덮어쓰지 않기 위함.
     */
    fun mergeGapFill(extra: Map<String, ItemInfo>) {
        var added = 0
        for ((id, info) in extra) {
            if (map.containsKey(id) || info.name.isBlank()) continue
            if (gapFill.putIfAbsent(id, info) == null) added++
        }
        if (added > 0) Log.i(TAG, "gap-filled $added item name(s) from TTD")
    }

    val size: Int get() = map.size + gapFill.size

    @Serializable
    data class ItemInfo(
        val name: String = "",
        val type: String = "",
        val img: String = "",
    )

    companion object {
        private const val TAG = "mTTD.ItemInfo"
        private const val ASSET_PATH = "item_names_ko.json"
    }
}
