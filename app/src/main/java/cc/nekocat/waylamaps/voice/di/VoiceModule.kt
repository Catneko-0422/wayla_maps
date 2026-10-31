package cc.nekocat.waylamaps.voice.di

import android.content.Context
import cc.nekocat.waylamaps.voice.PhraseCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides
    @Singleton
    fun providePhraseCatalog(@ApplicationContext context: Context): PhraseCatalog =
        context.assets.open(PhraseCatalog.ASSET_PATH).bufferedReader().use { PhraseCatalog.parse(it.readText()) }
}
