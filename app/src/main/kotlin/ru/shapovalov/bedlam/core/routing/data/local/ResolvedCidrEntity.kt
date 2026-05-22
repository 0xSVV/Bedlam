package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "resolved_cidr",
    foreignKeys = [
        ForeignKey(
            entity = RouteSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sourceId")],
)
data class ResolvedCidrEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: String,
    val cidr: String,
)
