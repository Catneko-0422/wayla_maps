package cc.nekocat.waylamaps.voice.di

import cc.nekocat.waylamaps.voice.Announcer
import cc.nekocat.waylamaps.voice.TtsAnnouncer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.scopes.ServiceScoped

@Module
@InstallIn(ServiceComponent::class)
abstract class VoiceModule {
    @Binds
    @ServiceScoped
    abstract fun bindAnnouncer(impl: TtsAnnouncer): Announcer
}
