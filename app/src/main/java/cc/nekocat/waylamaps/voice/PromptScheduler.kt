package cc.nekocat.waylamaps.voice

/**
 * One sentence to speak: a phrase from the phrase table with its slot values, or, when [text] is
 * set, free text for the system TTS (instructions with no recorded fragments).
 */
data class Prompt(
    val phraseId: String,
    val args: Map<String, Any> = emptyMap(),
    val priority: Priority,
    val text: String? = null,
)

/** Plays one prompt at a time for [PromptScheduler]. */
interface PromptOutput {
    /** Start playing; call [PromptScheduler.onFinished] with the same instance when done. */
    fun play(prompt: Prompt)

    /** Interrupt the current prompt. A late finish callback for it is ignored. */
    fun stop()

    /** Nothing is playing or queued; audio focus can be released. */
    fun onIdle()
}

/**
 * Orders prompts by CLAUDE.md priority (turn > speed camera > traffic > chatter).
 *
 * - A higher-priority prompt interrupts the current one, which is dropped as stale.
 * - Equal or lower priority waits; the queue plays highest priority first, then oldest first.
 * - A newer prompt for a phrase already queued replaces it.
 * - Queued prompts older than [maxQueueAgeMs] are dropped instead of played late; prompts that
 *   say a distance already after [maxDistanceAgeMs], since the rider has moved on and the next
 *   distance will be said soon.
 *
 * Not thread-safe: call from the main thread.
 */
class PromptScheduler(
    private val output: PromptOutput,
    private val clock: () -> Long,
    private val maxQueueAgeMs: Long = 10_000,
    private val maxDistanceAgeMs: Long = 3_000,
) {
    private data class Queued(val prompt: Prompt, val enqueuedAt: Long, val order: Long)

    private var current: Prompt? = null
    private val queue = mutableListOf<Queued>()
    private var nextOrder = 0L

    val isIdle: Boolean get() = current == null && queue.isEmpty()

    fun submit(prompt: Prompt) {
        val playing = current
        when {
            playing == null -> start(prompt)
            prompt.priority > playing.priority -> {
                current = null
                output.stop()
                start(prompt)
            }
            else -> {
                queue.removeAll { it.prompt.phraseId == prompt.phraseId }
                queue += Queued(prompt, clock(), nextOrder++)
            }
        }
    }

    /** Called by the output when [prompt] has finished, failed or been stopped. */
    fun onFinished(prompt: Prompt) {
        if (prompt !== current) return
        current = null
        playNext()
    }

    /** Stops playback and forgets everything queued. */
    fun clear() {
        queue.clear()
        if (current != null) {
            current = null
            output.stop()
        }
        output.onIdle()
    }

    private fun playNext() {
        val now = clock()
        queue.removeAll {
            val age = now - it.enqueuedAt
            age > maxQueueAgeMs || ("distance" in it.prompt.args && age > maxDistanceAgeMs)
        }
        val next = queue.minWithOrNull(compareByDescending<Queued> { it.prompt.priority }.thenBy { it.order })
        if (next == null) {
            output.onIdle()
            return
        }
        queue.remove(next)
        start(next.prompt)
    }

    private fun start(prompt: Prompt) {
        current = prompt
        output.play(prompt)
    }
}
