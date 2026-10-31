package cc.nekocat.waylamaps.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSchedulerTest {
    private val events = mutableListOf<String>()
    private var now = 0L
    private val output = object : PromptOutput {
        override fun play(prompt: Prompt) {
            events += "play ${prompt.phraseId}"
        }

        override fun stop() {
            events += "stop"
        }

        override fun onIdle() {
            events += "idle"
        }
    }
    private val scheduler = PromptScheduler(output, clock = { now }, maxQueueAgeMs = 10_000)

    private fun prompt(id: String, priority: Priority, vararg args: Pair<String, Any>) =
        Prompt(id, mapOf(*args), priority)

    @Test
    fun playsImmediatelyWhenIdle() {
        scheduler.submit(prompt("camera", Priority.SPEED_CAMERA))

        assertEquals(listOf("play camera"), events)
    }

    @Test
    fun higherPriorityInterruptsAndDropsCurrent() {
        val camera = prompt("camera", Priority.SPEED_CAMERA)
        scheduler.submit(camera)
        val turn = prompt("turn", Priority.TURN)
        scheduler.submit(turn)
        scheduler.onFinished(camera) // late callback from the stopped camera prompt
        scheduler.onFinished(turn)

        assertEquals(listOf("play camera", "stop", "play turn", "idle"), events)
    }

    @Test
    fun equalPriorityWaitsItsTurn() {
        val first = prompt("left", Priority.TURN)
        scheduler.submit(first)
        val second = prompt("arrive", Priority.TURN)
        scheduler.submit(second)
        scheduler.onFinished(first)
        scheduler.onFinished(second)

        assertEquals(listOf("play left", "play arrive", "idle"), events)
    }

    @Test
    fun queuePlaysHigherPriorityFirst() {
        val current = prompt("turn", Priority.TURN)
        scheduler.submit(current)
        scheduler.submit(prompt("chatter", Priority.CHATTER))
        scheduler.submit(prompt("traffic", Priority.TRAFFIC))
        scheduler.submit(prompt("camera", Priority.SPEED_CAMERA))

        scheduler.onFinished(current)

        assertEquals(listOf("play turn", "play camera"), events)
    }

    @Test
    fun newerPromptReplacesQueuedSamePhrase() {
        val current = prompt("turn", Priority.TURN)
        scheduler.submit(current)
        scheduler.submit(prompt("camera", Priority.SPEED_CAMERA, "distance" to 300))
        val newer = prompt("camera", Priority.SPEED_CAMERA, "distance" to 250)
        scheduler.submit(newer)

        scheduler.onFinished(current)
        scheduler.onFinished(newer)

        assertEquals(listOf("play turn", "play camera", "idle"), events)
    }

    @Test
    fun stalePromptsAreDropped() {
        val current = prompt("turn", Priority.TURN)
        scheduler.submit(current)
        scheduler.submit(prompt("camera", Priority.SPEED_CAMERA))
        now = 10_001

        scheduler.onFinished(current)

        assertEquals(listOf("play turn", "idle"), events)
    }

    @Test
    fun clearStopsAndEmptiesQueue() {
        scheduler.submit(prompt("turn", Priority.TURN))
        scheduler.submit(prompt("camera", Priority.SPEED_CAMERA))

        scheduler.clear()

        assertEquals(listOf("play turn", "stop", "idle"), events)
        assertTrue(scheduler.isIdle)
    }
}
