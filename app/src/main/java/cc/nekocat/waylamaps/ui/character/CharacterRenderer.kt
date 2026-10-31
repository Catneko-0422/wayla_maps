package cc.nekocat.waylamaps.ui.character

import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.ToneMapper
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import cc.nekocat.waylamaps.character.ModelSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws one glTF model on a transparent [TextureView] with Filament.
 *
 * Replaces filament-utils' ModelViewer, whose destroy() cancels an async resource load that a
 * synchronous load never started and crashes natively (gltfio 1.77.3). Resources load
 * synchronously here and are torn down in reverse order of creation.
 */
class CharacterRenderer(textureView: TextureView, model: ByteArray, private val spec: ModelSpec) {
    val engine: Engine = Engine.create(Engine.Backend.VULKAN)
    private val renderer: Renderer = engine.createRenderer().apply {
        clearOptions = clearOptions.apply { clear = true }
    }
    private val scene: Scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val colorGrading: ColorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    private val view: View = engine.createView().apply {
        scene = this@CharacterRenderer.scene
        camera = this@CharacterRenderer.camera
        blendMode = View.BlendMode.TRANSLUCENT
        // Linear keeps the texture's anime colors; a filmic curve would wash them out.
        colorGrading = this@CharacterRenderer.colorGrading
        // Hair and lashes are alpha-masked cut-outs with hard edges.
        antiAliasing = View.AntiAliasing.FXAA
        multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
            enabled = true
            sampleCount = 4
        }
    }

    /** Soft key light from the upper front left, plus even ambient light, for gentle shading. */
    private val keyLight = EntityManager.get().create().also {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.94f)
            .intensity(KEY_LIGHT_LUX)
            .direction(0.4f, -0.5f, -0.75f)
            .castShadows(false)
            .build(engine, it)
    }
    private val ambient: IndirectLight = IndirectLight.Builder()
        .irradiance(1, floatArrayOf(1.0f, 1.0f, 1.0f))
        .intensity(AMBIENT_LUX)
        .build(engine)
    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val loadedAsset: FilamentAsset? = assetLoader.createAsset(
        ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).put(model).flip(),
    )

    /** False after [destroy]; a frame callback may still run once while the view is torn down. */
    var alive = true
        private set

    /** The model, or null once destroyed. */
    val asset: FilamentAsset? get() = loadedAsset.takeIf { alive }
    private var swapChain: SwapChain? = null
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }

    init {
        scene.addEntity(keyLight)
        scene.indirectLight = ambient
        loadedAsset?.let {
            resourceLoader.loadResources(it)
            it.releaseSourceData()
            scene.addEntities(it.entities)
        }
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let(engine::destroySwapChain)
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }

            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(FIELD_OF_VIEW_DEG, width.toDouble() / height.coerceAtLeast(1), NEAR_M, FAR_M, Camera.Fov.VERTICAL)
                camera.lookAt(
                    0.0, spec.focusHeight.toDouble(), spec.cameraDistance.toDouble(),
                    0.0, spec.focusHeight.toDouble(), 0.0,
                    0.0, 1.0, 0.0,
                )
            }
        }
        uiHelper.attachTo(textureView)
    }

    fun render(frameTimeNanos: Long) {
        if (!alive) return
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun destroy() {
        if (!alive) return
        alive = false
        uiHelper.detach()
        loadedAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyView(view)
        engine.destroyColorGrading(colorGrading)
        engine.lightManager.destroy(keyLight)
        EntityManager.get().destroy(keyLight)
        engine.destroyIndirectLight(ambient)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }

    private companion object {
        // Matches ModelViewer's 28 mm lens, which the pack framing values were tuned with.
        const val FIELD_OF_VIEW_DEG = 46.4
        const val NEAR_M = 0.05
        const val FAR_M = 20.0

        // At the camera's default exposure (f/16, 1/125 s, ISO 100), about 100 000 lux is white.
        const val KEY_LIGHT_LUX = 38_000f
        const val AMBIENT_LUX = 30_000f
    }
}
