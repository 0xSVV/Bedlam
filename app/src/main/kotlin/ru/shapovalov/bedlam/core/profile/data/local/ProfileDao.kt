package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Upsert
    suspend fun upsert(entity: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)
}
