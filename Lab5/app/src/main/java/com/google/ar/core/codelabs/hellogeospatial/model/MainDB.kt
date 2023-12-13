package com.google.ar.core.codelabs.hellogeospatial.model

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoRenderer

@Database(entities = [MarkEntity::class], version = 1)
abstract class MainDB : RoomDatabase() {
    abstract fun getDao(): MarkDao
    companion object {
        fun getDb(context: HelloGeoRenderer): MainDB {
            return Room.databaseBuilder(
                context.activity,
                MainDB::class.java,
                "marks.db"
            ).build()

        }
    }
}