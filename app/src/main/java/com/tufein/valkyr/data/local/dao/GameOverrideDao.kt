package com.tufein.valkyr.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tufein.valkyr.data.local.entity.GameOverrideEntity

@Dao
interface GameOverrideDao {
    @Query("SELECT * FROM game_overrides WHERE `key` = :key")
    suspend fun get(key: String): GameOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GameOverrideEntity)

    @Query("DELETE FROM game_overrides WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT COUNT(*) FROM game_overrides")
    suspend fun count(): Int
}