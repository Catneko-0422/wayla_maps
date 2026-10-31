package cc.nekocat.waylamaps.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A sentence being spoken right now, for the character's speech bubble, mouth and reaction.
 * [phraseId] says what it is about, e.g. "arrived"; free text uses its own key.
 */
data class Speech(val text: String, val priority: Priority, val startedAtMs: Long, val phraseId: String? = null)

/** What any voice player is saying, shared with the UI. */
@Singleton
class SpeechState @Inject constructor() {
    private val _current = MutableStateFlow<Speech?>(null)
    val current: StateFlow<Speech?> = _current.asStateFlow()

    fun started(speech: Speech) {
        _current.value = speech
    }

    /** Clears [speech] if it is still the current one. */
    fun finished(speech: Speech?) {
        if (speech != null) _current.compareAndSet(speech, null)
    }
}
