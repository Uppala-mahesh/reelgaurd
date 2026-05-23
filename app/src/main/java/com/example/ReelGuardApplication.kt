package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.ReelDatabase
import com.example.data.ReelRepository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.service.ReelStreakWidgetProvider

class ReelGuardApplication : Application() {

    // Singleton dependency container (Service Locator pattern)
    lateinit var database: ReelDatabase
    lateinit var repository: ReelRepository

    companion object {
        lateinit var instance: ReelGuardApplication
            private set

        fun isInitialized(): Boolean {
            return ::instance.isInitialized && instance.isInitializedInternal()
        }
    }

    fun isInitializedInternal(): Boolean {
        return ::repository.isInitialized && ::database.isInitialized
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Instantiate database
        database = Room.databaseBuilder(
            applicationContext,
            ReelDatabase::class.java,
            "reelguard_database"
        ).fallbackToDestructiveMigration(dropAllTables = true)
         .build()

        // Instantiate repository
        repository = ReelRepository(applicationContext, database)

        // Reactively observe streak updates to sync home screen widgets instantly
        CoroutineScope(Dispatchers.Main).launch {
            try {
                repository.streakFlow.collect {
                    try {
                        ReelStreakWidgetProvider.updateAllWidgets(applicationContext)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
