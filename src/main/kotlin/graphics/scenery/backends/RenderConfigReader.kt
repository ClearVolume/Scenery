package graphics.scenery.backends

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import graphics.scenery.Blending
import graphics.scenery.utils.JsonDeserialisers
import org.joml.Vector4f
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.collections.LinkedHashMap


/**
 * Returns the output name of the pass with [passname] if it exists, otherwise null.
 */
@Suppress("unused")
fun RenderConfigReader.RenderConfig.getOutputOfPass(passname: String): String? {
    return renderpasses[passname]?.output
}

/**
 * Returns all inputs of [targetName], which may be an empty set.
 */
@Suppress("unused")
fun RenderConfigReader.RenderConfig.getInputsOfTarget(targetName: String): Set<String> {
    return rendertargets.filter {
        it.key == renderpasses.filter { p -> p.value.output == targetName }.keys.first()
    }.keys
}

/**
 * Creates a linear flow of renderpasses from the configuration, returning
 * it as a [List] of Strings.
 */
fun RenderConfigReader.RenderConfig.createRenderpassFlow(): List<String> {
    val passes = renderpasses
    val dag = ArrayList<String>()

    // find first
    val start = passes.filter { it.value.output == "Viewport" }.entries.first()
    var inputs: List<String>? = start.value.inputs
    dag.add(start.key)

    while(inputs != null) {
        passes.filter {
            inputs!!
                .map { input -> input.substringBefore(".") }
                .contains(it.value.output)
        }.forEach {
                inputs = it.value.inputs

                dag.add(it.key.substringBefore("."))
        }
    }

    return dag.reversed().distinct().toList()
}

/**
 * Class to ingest rendering configuration files.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class RenderConfigReader {

    /**
     * Render configuration top-level class, containing information about [rendertargets]
     * and [renderpasses], as well as [qualitySettings].
     */
    data class RenderConfig(
        var name: String,
        var sRGB: Boolean = false,
        var description: String?,
        var stereoEnabled: Boolean = false,
        var rendertargets: Map<String, RendertargetConfig> = emptyMap(),
        var renderpasses: LinkedHashMap<String, RenderpassConfig>,
        var qualitySettings: Map<RenderingQuality, Map<String, Any>> = emptyMap())

    /**
     * Configuration for a single render target, defining its [size] and [attachments].
     */
    data class RendertargetConfig(
        @JsonDeserialize(using = JsonDeserialisers.FloatPairDeserializer::class) var size: Pair<Float, Float> = Pair(1.0f, 1.0f),
        val attachments: Map<String, TargetFormat> = emptyMap()
    )

    /**
     * Configuration for a single render pass
     */
    data class RenderpassConfig(
        var type: RenderpassType,
        var blitInputs: Boolean = false,
        var renderTransparent: Boolean = false,
        var depthTestEnabled: Boolean = true,
        var depthWriteEnabled: Boolean = true,
        var order: RenderOrder = RenderOrder.BackToFront,
        var renderOpaque: Boolean = true,
        var colorBlendOp: Blending.BlendOp = Blending.BlendOp.add,
        var alphaBlendOp: Blending.BlendOp = Blending.BlendOp.add,
        var srcColorBlendFactor: Blending.BlendFactor = Blending.BlendFactor.SrcAlpha,
        var dstColorBlendFactor: Blending.BlendFactor = Blending.BlendFactor.OneMinusSrcAlpha,
        var srcAlphaBlendFactor: Blending.BlendFactor = Blending.BlendFactor.SrcAlpha,
        var dstAlphaBlendFactor: Blending.BlendFactor = Blending.BlendFactor.OneMinusSrcAlpha,
        var shaders: List<String>,
        var inputs: List<String>?,
        var output: String,
        var parameters: Map<String, Any>?,
        @JsonDeserialize(using = JsonDeserialisers.FloatPairDeserializer::class) var viewportSize: Pair<Float, Float> = Pair(1.0f, 1.0f),
        @JsonDeserialize(using = JsonDeserialisers.FloatPairDeserializer::class) var viewportOffset: Pair<Float, Float> = Pair(0.0f, 0.0f),
        @JsonDeserialize(using = JsonDeserialisers.FloatPairDeserializer::class) var scissor: Pair<Float, Float> = Pair(1.0f, 1.0f),
        @JsonDeserialize(using = JsonDeserialisers.VectorDeserializer::class) var clearColor: Vector4f = Vector4f(0.0f, 0.0f, 0.0f, 0.0f),
        var depthClearValue: Float = 1.0f,
        @JsonDeserialize(using = JsonDeserialisers.VREyeDeserializer::class) var eye: Int = -1
    )

    /** Rendering quality enums */
    enum class RenderingQuality { Low, Medium, High, Ultra }

    /** Renderpass types */
    enum class RenderpassType { geometry, quad, lights, compute }

    /** Render ordering */
    enum class RenderOrder { DontCare, BackToFront, FrontToBack }

    /** Rendertarget formats */
    enum class TargetFormat {
        RGBA_Float32,
        RGBA_Float16,
        RGB_Float32,
        RGB_Float16,
        RG_Float32,
        RG_Float16,
        R_Float16,
        Depth24,
        Depth32,
        RGBA_UInt8,
        RGBA_UInt16,
        R_UInt16,
        R_UInt8
    }

    /**
     * Loads a [RenderConfig] from a file given by [path].
     */
    fun loadFromFile(path: String): RenderConfig {
        val mapper = ObjectMapper(YAMLFactory())
        mapper.registerModule(KotlinModule())

        var stream = this.javaClass.getResourceAsStream(path)

        if (stream == null) {
            val p = Paths.get(path)

            return if (!Files.exists(p)) {
                stream = this.javaClass.getResourceAsStream("DeferredShading.yml")
                mapper.readValue(stream, RenderConfig::class.java)
            } else {
                Files.newBufferedReader(p).use {
                    mapper.readValue(it, RenderConfig::class.java)
                }
            }
        }

        return mapper.readValue(stream, RenderConfig::class.java)
    }
}
