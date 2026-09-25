package com.lazyeng.family.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lazyeng.family.core.model.FamilyRepository
import com.lazyeng.family.core.model.ProfileRepository

@Database(entities = [FamilyEntity::class, ProfileEntity::class], version = 1, exportSchema = true)
@TypeConverters(FamilyConverters::class)
abstract class FamilyDatabase : RoomDatabase() {
    internal abstract fun familyDao(): FamilyDao
    internal abstract fun profileDao(): ProfileDao

    fun familyRepository(): FamilyRepository = RoomFamilyRepository(this)
    fun profileRepository(): ProfileRepository = RoomProfileRepository(this)

    companion object {
        /** The application owns one instance and closes it only when its storage scope ends. */
        fun open(context: Context): FamilyDatabase = Room.databaseBuilder(
            context.applicationContext, FamilyDatabase::class.java, "family.db",
        ).build()
    }
}
