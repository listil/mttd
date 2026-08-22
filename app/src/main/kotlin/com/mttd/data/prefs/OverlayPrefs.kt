package com.mttd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 오버레이 위치/투명도 + 앱 설정 저장소.
 *
 * 위치 값은 dp 가 아닌 px 단위로 저장 (`WindowManager.LayoutParams.x/y` 와 직접 매치).
 * 서로 다른 해상도로 이동해도 화면 안이면 유효한 좌표.
 */
class OverlayPrefs(private val context: Context) {

    /** 시세 출처. 값은 [com.mttd.data.prices.PriceSource.id]. */
    val priceSourceId: Flow<String> = context.dataStore.data.map { it[KEY_PRICE_SOURCE] ?: "etor" }

    suspend fun setPriceSourceId(id: String) {
        context.dataStore.edit { it[KEY_PRICE_SOURCE] = id }
    }

    /** ETOR 시즌 선택 모드(정규/하드코어, 항상 수동). 값은 [com.mttd.data.prices.SeasonMode.id]. */
    val seasonModeId: Flow<String> = context.dataStore.data.map {
        it[KEY_SEASON_MODE] ?: com.mttd.data.prices.SeasonMode.REGULAR.id
    }

    suspend fun setSeasonModeId(id: String) {
        context.dataStore.edit { it[KEY_SEASON_MODE] = id }
    }

    /** 배지(아이콘 오버레이) 2번째 줄에 표시할 수익 지표. 값은 [com.mttd.ui.overlay.BadgeIncomeMetric.id]. */
    val badgeIncomeMetric: Flow<String> = context.dataStore.data.map {
        it[KEY_BADGE_METRIC] ?: com.mttd.ui.overlay.BadgeIncomeMetric.DEFAULT.id
    }

    suspend fun setBadgeIncomeMetric(id: String) {
        context.dataStore.edit { it[KEY_BADGE_METRIC] = id }
    }

    /**
     * 경과/시간당 수익 기준. 값은 [com.mttd.domain.models.TimeBasis.id].
     * 수익 탭 헤드라인, 플로팅 HUD, 배지 1번째 줄이 전부 이 값 하나를 공유한다 — 어디서
     * 바꾸든 셋 다 같이 바뀐다(예전엔 배지만 따로 값이 있어서 설정 탭에서 바꿔도 플로팅 HUD가
     * 안 바뀌는 것처럼 보이는 혼란이 있었다).
     */
    val timeBasisId: Flow<String> = context.dataStore.data.map {
        it[KEY_TIME_BASIS] ?: com.mttd.domain.models.TimeBasis.DEFAULT.id
    }

    suspend fun setTimeBasisId(id: String) {
        context.dataStore.edit { it[KEY_TIME_BASIS] = id }
    }

    val iconX: Flow<Int> = context.dataStore.data.map { it[KEY_ICON_X] ?: 60 }
    val iconY: Flow<Int> = context.dataStore.data.map { it[KEY_ICON_Y] ?: 300 }
    val hudX: Flow<Int> = context.dataStore.data.map { it[KEY_HUD_X] ?: 60 }
    val hudY: Flow<Int> = context.dataStore.data.map { it[KEY_HUD_Y] ?: 400 }
    val hudAlpha: Flow<Float> = context.dataStore.data.map { it[KEY_HUD_ALPHA] ?: 0.85f }
    val hudVisible: Flow<Boolean> = context.dataStore.data.map { it[KEY_HUD_VISIBLE] ?: false }

    /** 최초 설정 가이드(마법사)를 완료했거나 건너뛴 적 있으면 true. */
    val wizardCompleted: Flow<Boolean> = context.dataStore.data.map { it[KEY_WIZARD_COMPLETED] ?: false }

    suspend fun setWizardCompleted(v: Boolean) {
        context.dataStore.edit { it[KEY_WIZARD_COMPLETED] = v }
    }

    suspend fun setIconPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_ICON_X] = x
            it[KEY_ICON_Y] = y
        }
    }

    suspend fun setHudPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_HUD_X] = x
            it[KEY_HUD_Y] = y
        }
    }

    suspend fun setHudAlpha(a: Float) {
        context.dataStore.edit { it[KEY_HUD_ALPHA] = a.coerceIn(0.2f, 1f) }
    }

    suspend fun setHudVisible(v: Boolean) {
        context.dataStore.edit { it[KEY_HUD_VISIBLE] = v }
    }

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "overlay_prefs")
        private val KEY_ICON_X = intPreferencesKey("icon_x")
        private val KEY_ICON_Y = intPreferencesKey("icon_y")
        private val KEY_HUD_X = intPreferencesKey("hud_x")
        private val KEY_HUD_Y = intPreferencesKey("hud_y")
        private val KEY_HUD_ALPHA = floatPreferencesKey("hud_alpha")
        private val KEY_HUD_VISIBLE = booleanPreferencesKey("hud_visible")
        private val KEY_WIZARD_COMPLETED = booleanPreferencesKey("wizard_completed")
        private val KEY_PRICE_SOURCE = androidx.datastore.preferences.core.stringPreferencesKey("price_source")
        private val KEY_SEASON_MODE = androidx.datastore.preferences.core.stringPreferencesKey("season_mode")
        private val KEY_BADGE_METRIC = androidx.datastore.preferences.core.stringPreferencesKey("badge_income_metric")
        private val KEY_TIME_BASIS = androidx.datastore.preferences.core.stringPreferencesKey("time_basis")
    }
}
