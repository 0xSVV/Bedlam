package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutingDao {

    @Query("SELECT * FROM routing_config WHERE id = 1")
    fun observeConfig(): Flow<RoutingConfigEntity?>

    @Query("SELECT * FROM routing_config WHERE id = 1")
    suspend fun getConfig(): RoutingConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: RoutingConfigEntity)

    @Query("SELECT * FROM direct_route ORDER BY orderIndex ASC, id ASC")
    fun observeDirectRoutes(): Flow<List<DirectRouteEntity>>

    @Query("SELECT * FROM direct_route ORDER BY orderIndex ASC, id ASC")
    suspend fun getDirectRoutes(): List<DirectRouteEntity>

    @Upsert
    suspend fun upsertDirectRoute(rule: DirectRouteEntity)

    @Query("DELETE FROM direct_route WHERE id = :id")
    suspend fun deleteDirectRoute(id: String)

    @Query("UPDATE direct_route SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
