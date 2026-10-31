package cc.nekocat.waylamaps.ui.character

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.nekocat.waylamaps.character.ModelSpec
import cc.nekocat.waylamaps.voice.Speech
import com.google.android.filament.Engine
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A character's 3D model on a transparent background, blinking, swaying a little and moving
 * its mouth while [speech] is being said. Renders at most [FRAME_RATE] frames per second and
 * only while the screen is resumed.
 */
@Composable
fun CharacterView(model: ByteArray, spec: ModelSpec, speech: Speech?, modifier: Modifier = Modifier) {
    // Filament aborts the whole process when a shader fails to link, which its OpenGL backend
    // does on some GPUs (the emulator's among them). Only Vulkan is used; without it the
    // character is simply not drawn and the speech bubble still shows.
    val engineAvailable = remember { vulkanEngineAvailable() }
    if (!engineAvailable) return

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSpeech by rememberUpdatedState(speech)
    var viewer by remember { mutableStateOf<ModelViewer?>(null) }
    val textureView = remember { TextureView(context).apply { isOpaque = false } }

    DisposableEffect(model) {
        val engine = Engine.create(Engine.Backend.VULKAN)
        val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
        val manipulator = Manipulator.Builder()
            .targetPosition(0f, spec.focusHeight, 0f)
            .orbitHomePosition(0f, spec.focusHeight, spec.cameraDistance)
            .build(Manipulator.Mode.ORBIT)
        val created = ModelViewer(textureView, engine, uiHelper, manipulator).apply {
            view.blendMode = View.BlendMode.TRANSLUCENT
            renderer.clearOptions = renderer.clearOptions.apply { clear = true }
            scene.skybox = null
            loadModelGlb(ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).put(model).flip())
            // The pack's first animation holds the rest pose (arms down instead of the T-pose).
            animator?.takeIf { it.animationCount > 0 }?.apply {
                applyAnimation(0, 0f)
                updateBoneMatrices()
            }
        }
        viewer = created
        onDispose {
            viewer = null
            created.destroy()
        }
    }

    DisposableEffect(viewer, lifecycle) {
        val current = viewer ?: return@DisposableEffect onDispose { }
        val face = FaceAnimator()
        val morphs = current.morphTargets(spec)
        var lastFrame = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                Choreographer.getInstance().postFrameCallback(this)
                if (frameTimeNanos - lastFrame < 1_000_000_000L / FRAME_RATE) return
                lastFrame = frameTimeNanos
                // Same clock as Speech.startedAtMs.
                val nowMs = SystemClock.elapsedRealtime()
                current.applyFace(morphs, face.weights(nowMs, currentSpeech?.startedAtMs))
                current.sway(nowMs)
                current.render(frameTimeNanos)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> Choreographer.getInstance().postFrameCallback(callback)
                Lifecycle.Event.ON_PAUSE -> Choreographer.getInstance().removeFrameCallback(callback)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }

    AndroidView(factory = { textureView }, modifier = modifier)
}

/** Renderable entity and morph target index for each face morph the model has. */
private class MorphTargets(val entity: Int, val count: Int, val indices: Map<FaceAnimator.Morph, Int>)

private fun ModelViewer.morphTargets(spec: ModelSpec): MorphTargets? {
    val asset = asset ?: return null
    val names = mapOf(
        FaceAnimator.Morph.BLINK to spec.morphs.blink,
        FaceAnimator.Morph.A to spec.morphs.a,
        FaceAnimator.Morph.I to spec.morphs.i,
        FaceAnimator.Morph.U to spec.morphs.u,
        FaceAnimator.Morph.E to spec.morphs.e,
        FaceAnimator.Morph.O to spec.morphs.o,
    )
    for (entity in asset.renderableEntities) {
        val targets = asset.getMorphTargetNames(entity)
        if (targets.isEmpty()) continue
        val indices = names.mapNotNull { (morph, name) -> name?.let { targets.indexOf(it) }?.takeIf { it >= 0 }?.let { morph to it } }.toMap()
        if (indices.isNotEmpty()) return MorphTargets(entity, targets.size, indices)
    }
    return null
}

private fun ModelViewer.applyFace(targets: MorphTargets?, weights: Map<FaceAnimator.Morph, Float>) {
    targets ?: return
    val values = FloatArray(targets.count)
    for ((morph, index) in targets.indices) values[index] = weights[morph] ?: 0f
    val rm = engine.renderableManager
    rm.setMorphWeights(rm.getInstance(targets.entity), values, 0)
}

/** A slow sway and breathing bob of the whole model. */
private fun ModelViewer.sway(nowMs: Long) {
    val asset = asset ?: return
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
    val tm = engine.transformManager
    tm.setTransform(tm.getInstance(asset.root), matrix)
}

/** Whether a Vulkan engine can be created here; tried once per process. */
private fun vulkanEngineAvailable(): Boolean = vulkanSupport

private val vulkanSupport: Boolean by lazy {
    Utils.init()
    try {
        val engine = Engine.create(Engine.Backend.VULKAN)
        val ok = engine.backend == Engine.Backend.VULKAN
        engine.destroy()
        if (!ok) Log.w(TAG, "No Vulkan backend; the 3D character is disabled")
        ok
    } catch (e: RuntimeException) {
        Log.w(TAG, "Filament engine unavailable; the 3D character is disabled", e)
        false
    }
}

private const val TAG = "CharacterView"
private const val FRAME_RATE = 30
