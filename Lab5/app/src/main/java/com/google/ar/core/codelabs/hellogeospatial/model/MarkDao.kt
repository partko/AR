package com.google.ar.core.codelabs.hellogeospatial.model

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(anchor: MarkEntity)
    @Delete
    fun delete(anchor: MarkEntity)

    @Query("SELECT  * from marks")
    fun getAllAnchor(): Flow<List<MarkEntity>>
    @Query("SELECT * FROM marks ORDER BY anchorId DESC LIMIT 5")
    fun get5LastAnchor(): Flow<List<MarkEntity>>
    @Query("SELECT COUNT(*) FROM marks")
    fun getCount(): Int
    // selecting one note at a time
    @Query("SELECT * FROM marks WHERE anchorId LIKE :id")
    fun getAnchor(id : Int) : MarkEntity


}