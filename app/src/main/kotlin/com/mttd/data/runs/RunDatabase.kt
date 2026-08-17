package com.mttd.data.runs

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 완료된 회차 저장소.
 *
 * **목적은 영속화가 아니라 메모리 상한**이다. 회차마다 아이템 목록(수십 개)을 들고 있으면
 * 장시간 세션에서 프로세스 메모리가 계속 늘기 때문에, 회차가 끝나는 즉시 디스크로 내리고
 * 메모리에는 그래프에 필요한 요약값만 남긴다. 아이템 목록은 사용자가 그래프를 눌러
 * 상세 팝업을 열 때만 다시 읽는다.
 *
 * 아이템 목록은 정규화하지 않고 JSON 한 컬럼에 넣는다 — 조인이 필요 없고,
 * 이 데이터로 SQL 집계를 할 일이 없다 (세션 전체 합계는 메모리에서 증분 계산).
 */
@Entity(tableName = "map_run")
data class RunEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtMs: Long?,
    @ColumnInfo(name = "map_name") val mapName: String?,
    /** [com.mttd.domain.models.MapRun.isMapRun] — "M타임" 합산 대상 여부. */
    @ColumnInfo(name = "is_map_run", defaultValue = "1") val isMapRun: Boolean = true,
    @ColumnInfo(name = "total_value") val totalValue: Double,
    /** 경매장 세금(1/8) 반영한 실수령 예상 합계. */
    @ColumnInfo(name = "net_total_value") val netTotalValue: Double,
    @ColumnInfo(name = "pickup_count") val pickupCount: Int,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    /** [com.mttd.domain.models.PickupSummary] 목록의 JSON. */
    @ColumnInfo(name = "items_json") val itemsJson: String,
)

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity)

    @Query("DELETE FROM map_run WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM map_run")
    suspend fun clear()

    /** 상세 팝업용 — 아이템 JSON 까지 포함해 한 건만. */
    @Query("SELECT * FROM map_run WHERE id = :id")
    suspend fun findById(id: Long): RunEntity?

    /**
     * 앱 재시작 시 요약 복원용. `items_json` 은 제외해서 메모리에 안 올린다.
     */
    @Query(
        "SELECT id, started_at, ended_at, map_name, is_map_run, total_value, net_total_value, pickup_count, item_count " +
            "FROM map_run ORDER BY started_at ASC LIMIT :limit"
    )
    suspend fun summaries(limit: Int): List<RunSummaryRow>

    /** 보관 한도를 넘긴 오래된 회차 정리. */
    @Query("DELETE FROM map_run WHERE id NOT IN (SELECT id FROM map_run ORDER BY started_at DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM map_run")
    suspend fun count(): Int
}

/** `items_json` 없는 경량 행. 그래프/합계에 필요한 값만. */
data class RunSummaryRow(
    val id: Long,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtMs: Long?,
    @ColumnInfo(name = "map_name") val mapName: String?,
    @ColumnInfo(name = "is_map_run", defaultValue = "1") val isMapRun: Boolean = true,
    @ColumnInfo(name = "total_value") val totalValue: Double,
    @ColumnInfo(name = "net_total_value") val netTotalValue: Double,
    @ColumnInfo(name = "pickup_count") val pickupCount: Int,
    @ColumnInfo(name = "item_count") val itemCount: Int,
)

@Database(entities = [RunEntity::class], version = 3, exportSchema = false)
abstract class RunDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        @Volatile private var instance: RunDatabase? = null

        fun get(context: Context): RunDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RunDatabase::class.java,
                "tli_runs.db",
            )
                // 스키마가 바뀌면 그냥 버린다 — 세션 통계용 임시 데이터라 마이그레이션 가치가 없다.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
