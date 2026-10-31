package cc.nekocat.waylamaps.data.di

import android.content.Context
import androidx.room.Room
import cc.nekocat.waylamaps.data.db.EnforcementPointDao
import cc.nekocat.waylamaps.data.db.SpeedCameraDao
import cc.nekocat.waylamaps.data.db.WaylaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WaylaDatabase =
        Room.databaseBuilder(context, WaylaDatabase::class.java, WaylaDatabase.NAME).build()

    @Provides
    fun provideSpeedCameraDao(db: WaylaDatabase): SpeedCameraDao = db.speedCameraDao()

    @Provides
    fun provideEnforcementPointDao(db: WaylaDatabase): EnforcementPointDao = db.enforcementPointDao()
}
