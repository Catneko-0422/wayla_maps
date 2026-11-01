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
import com.google.android.filament.utils.Utils

/**
 * A character's 3D model on a transparent background, blinking, moving its mouth while
 * [speech] is being said, and reacting to it with a mood and a gesture (see CharacterDirector). Renders at most [FRAME_RATE] frames per second and
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
    var viewer by remember { mutableStateOf<CharacterRenderer?>(null) }
    val textureView = remember { TextureView(context).apply { isOpaque = false } }

    DisposableEffect(model) {
        val created = CharacterRenderer(textureView, model, spec)
        viewer = created
        onDispose {
            viewer = null
            created.destroy()
        }
    }

    DisposableEffect(viewer, lifecycle) {
        val current = viewer ?: return@DisposableEffect onDispose { }
        val rig = CharacterRig(current, spec)
        val director = CharacterDirector()
        var lastFrame = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!current.alive) return
                Choreographer.getInstance().postFrameCallback(this)
                if (frameTimeNanos - lastFrame < 1_000_000_000L / FRAME_RATE) return
                lastFrame = frameTimeNanos
                // Same clock as Speech.startedAtMs.
                val nowMs = SystemClock.elapsedRealtime()
                rig.update(nowMs, director.update(nowMs, currentSpeech))
                current.render(frameTimeNanos)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> Choreographer.getInstance().run {
                    // A repeated resume must not start a second frame loop.
                    removeFrameCallback(callback)
                    postFrameCallback(callback)
                }
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
