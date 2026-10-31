package cc.nekocat.waylamaps.ui.character

import cc.nekocat.waylamaps.character.ModelSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Drives a loaded model each frame: morph weights for talking, blinking and moods, and the
 * pack's skeletal motions. A model without motions sways as a whole instead.
 */
class CharacterRig(private val renderer: CharacterRenderer, private val spec: ModelSpec) {
    /** Every renderable with morph targets: a face split into several materials has several. */
    private val morphRenderables: List<Pair<Int, Array<String>>> = renderer.asset?.let { asset ->
        asset.renderableEntities.map { it to asset.getMorphTargetNames(it) }.filter { it.second.isNotEmpty() }
    }.orEmpty()

    private val animator = renderer.asset?.instance?.animator
    private val motions: Map<CharacterMotion, Int> = animator?.let { animator ->
        val byName = (0 until animator.animationCount).associateBy { animator.getAnimationName(it) }
        CharacterMotion.entries.mapNotNull { motion -> spec.motions[motion.key]?.let(byName::get)?.let { motion to it } }.toMap()
    }.orEmpty()

    private val face = FaceAnimator()
    private val moodWeights = HashMap<String, Float>()
    private var lastFrameMs: Long? = null

    fun update(nowMs: Long, state: CharacterDirector.State) {
        if (!renderer.alive) return
        val dt = lastFrameMs?.let { (nowMs - it) / 1000f } ?: 0f
        lastFrameMs = nowMs
        applyMorphs(nowMs, state, dt)
        if (!applyMotion(nowMs, state)) sway(nowMs)
    }

    private fun applyMorphs(nowMs: Long, state: CharacterDirector.State, dt: Float) {
        if (morphRenderables.isEmpty()) return
        // Moods fade in and out over about a quarter second instead of snapping.
        val target = spec.expressions[state.mood.key].orEmpty()
        for (name in target.keys + moodWeights.keys) {
            val goal = target[name] ?: 0f
            val current = moodWeights[name] ?: 0f
            val step = MOOD_FADE_PER_S * dt
            moodWeights[name] = if (current < goal) min(goal, current + step) else maxOf(goal, current - step)
        }

        val weights = HashMap<String, Float>(moodWeights)
        val talking = face.weights(nowMs, state.speakingSinceMs)
        val names = mapOf(
            FaceAnimator.Morph.BLINK to spec.morphs.blink,
            FaceAnimator.Morph.A to spec.morphs.a,
            FaceAnimator.Morph.I to spec.morphs.i,
            FaceAnimator.Morph.U to spec.morphs.u,
            FaceAnimator.Morph.E to spec.morphs.e,
            FaceAnimator.Morph.O to spec.morphs.o,
        )
        for ((morph, name) in names) {
            if (name == null) continue
            weights[name] = ((weights[name] ?: 0f) + (talking[morph] ?: 0f)).coerceAtMost(1f)
        }

        val rm = renderer.engine.renderableManager
        for ((entity, targets) in morphRenderables) {
            val values = FloatArray(targets.size) { weights[targets[it]] ?: 0f }
            rm.setMorphWeights(rm.getInstance(entity), values, 0)
        }
    }

    /** Returns false when the model has no idle motion to play. */
    private fun applyMotion(nowMs: Long, state: CharacterDirector.State): Boolean {
        val animator = animator ?: return false
        val idle = motions[CharacterMotion.IDLE] ?: return false
        val idleTime = loopTime(animator.getAnimationDuration(idle), nowMs)

        val gesture = state.gesture?.let(motions::get)
        val gestureElapsed = (nowMs - state.gestureStartMs) / 1000f
        val talk = motions[CharacterMotion.TALK]
        val speakingSince = state.speakingSinceMs
        when {
            gesture != null && gestureElapsed < animator.getAnimationDuration(gesture) -> {
                val duration = animator.getAnimationDuration(gesture)
                animator.applyAnimation(gesture, gestureElapsed)
                // Ease in from and back out to the idle motion.
                val weight = min(1f, min(gestureElapsed, duration - gestureElapsed) / BLEND_S)
                animator.applyCrossFade(idle, idleTime, weight)
            }
            talk != null && speakingSince != null -> {
                animator.applyAnimation(talk, loopTime(animator.getAnimationDuration(talk), nowMs - speakingSince))
                animator.applyCrossFade(idle, idleTime, TALK_WEIGHT)
            }
            else -> animator.applyAnimation(idle, idleTime)
        }
        animator.updateBoneMatrices()
        return true
    }

    private fun loopTime(durationS: Float, ms: Long): Float = if (durationS > 0f) (ms / 1000f) % durationS else 0f

    /** For models without motions: a slow sway and breathing bob of the whole model. */
    private fun sway(nowMs: Long) {
        val asset = renderer.asset ?: return
        val t = nowMs / 1000.0
        val angle = 0.04 * sin(2 * PI * t / 6)
        val bob = (0.004 * sin(2 * PI * t / 4)).toFloat()
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        // Column-major rotation about Y plus a small vertical offset.
        val matrix = floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, bob, 0f, 1f,
        )
        val tm = renderer.engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), matrix)
    }

    private companion object {
        const val MOOD_FADE_PER_S = 4f
        const val BLEND_S = 0.25f

        /** How much of the talking nod shows over the idle motion. */
        const val TALK_WEIGHT = 0.6f
    }
}
