package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutingDao {

    @Query("SELECT * FROM routing_config WHERE id = 1")
    fun observeConfig(): Flow<RoutingConfigEntity?>

    @Query("SELECT * FROM routing_config WHERE id = 1")
    suspend fun getConfig(): RoutingConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: RoutingConfigEntity)

    @Query("SELECT * FROM route_source ORDER BY orderIndex ASC, id ASC")
    fun observeSources(): Flow<List<RouteSourceEntity>>

    @Query("SELECT * FROM route_source ORDER BY orderIndex ASC, id ASC")
    suspend fun getSources(): List<RouteSourceEntity>

    @Query("SELECT * FROM route_source WHERE id = :id")
    suspend fun getSource(id: String): RouteSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: RouteSourceEntity)

    @Query("DELETE FROM route_source WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Query("UPDATE route_source SET enabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: String, enabled: Boolean)

    @Query("UPDATE route_source SET lastResolvedMillis = :ms, lastError = :err WHERE id = :id")
    suspend fun setSourceResolutionState(id: String, ms: Long?, err: String?)

    @Query("UPDATE route_source SET lastError = :err WHERE id = :id")
    suspend fun setSourceError(id: String, err: String?)

    @Query("SELECT * FROM resolved_cidr ORDER BY sourceId ASC, cidr ASC")
    fun observeAllResolved(): Flow<List<ResolvedCidrEntity>>

    @Query("SELECT * FROM resolved_cidr WHERE sourceId = :sourceId")
    suspend fun getResolvedFor(sourceId: String): List<ResolvedCidrEntity>

    @Query("DELETE FROM resolved_cidr WHERE sourceId = :sourceId")
    suspend fun deleteResolvedFor(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResolved(entries: List<ResolvedCidrEntity>)

    @Transaction
    suspend fun replaceResolved(sourceId: String, cidrs: List<String>) {
        deleteResolvedFor(sourceId)
        if (cidrs.isNotEmpty()) {
            insertResolved(cidrs.map { ResolvedCidrEntity(sourceId = sourceId, cidr = it) })
        }
    }

    @Transaction
    suspend fun commitResolution(
        expected: RouteSourceEntity,
        cidrs: List<String>,
        resolvedAtMillis: Long,
        error: String?,
    ): Boolean {
        val current = getSource(expected.id) ?: return false
        if (!current.matchesIdentity(expected)) return false
        replaceResolved(expected.id, cidrs)
        setSourceResolutionState(expected.id, resolvedAtMillis, error)
        return true
    }

    @Transaction
    suspend fun commitResolutionError(expected: RouteSourceEntity, error: String?): Boolean {
        val current = getSource(expected.id) ?: return false
        if (!current.matchesIdentity(expected)) return false
        setSourceError(expected.id, error)
        return true
    }
}

private fun RouteSourceEntity.matchesIdentity(expected: RouteSourceEntity): Boolean =
    id == expected.id && kind == expected.kind && rawValue == expected.rawValue &&
        comment == expected.comment && enabled == expected.enabled && orderIndex == expected.orderIndex
