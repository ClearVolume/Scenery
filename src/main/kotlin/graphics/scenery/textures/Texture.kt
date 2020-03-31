package graphics.scenery.textures

import cleargl.GLTypeEnum
import org.joml.Vector3f
import graphics.scenery.utils.Image
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.UnsignedByteType
import org.joml.Vector3i
import java.io.Serializable
import java.nio.ByteBuffer


/**
 * Data class for storing renderer-agnostic textures
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class Texture @JvmOverloads constructor(
        /** Dimensions of the texture in pixels */
        var dimensions: Vector3i,
        /** The texture's number of channels */
        var channels: Int = 4,
        /** [GLTypeEnum] declaring the data type stored in [contents] */
        var type: NumericType<*> = UnsignedByteType(),
        /** Byte contents of the texture */
    @Transient var contents: ByteBuffer?,
        /** Shall the texture be repeated on the U/V/W coordinates? */
        var repeatUVW: Triple<RepeatMode, RepeatMode, RepeatMode> = Triple(RepeatMode.Repeat, RepeatMode.Repeat, RepeatMode.Repeat),
        /** Texture border color */
        var borderColor: BorderColor = BorderColor.TransparentBlack,
        /** Should the texture data be interpreted as normalized? Default is true, non-normalisation is better for volume data, though */
        var normalized: Boolean = true,
        /** Should mipmaps be generated? */
        var mipmap: Boolean = true,
        /** Linear or nearest neighbor filtering for scaling down. */
        var minFilter: FilteringMode = FilteringMode.Linear,
        /** Linear or nearest neighbor filtering for scaling up. */
        var maxFilter: FilteringMode = FilteringMode.Linear


) : Serializable {
    /**
     * Enum class defining available texture repeat modes.
     */
    enum class RepeatMode {
        Repeat,
        MirroredRepeat,
        ClampToEdge,
        ClampToBorder;

        fun all():  Triple<RepeatMode, RepeatMode, RepeatMode> {
            return Triple(this, this, this)
        }
    }

    /**
     * Enum class defining which colors are available for a texture's border.
     */
    enum class BorderColor {
        TransparentBlack,
        OpaqueBlack,
        OpaqueWhite
    }

    /**
     * Enum class defining texture filtering modes
     */
    enum class FilteringMode {
        NearestNeighbour,
        Linear
    }

    /** Companion object of [Texture], containing mainly constant defines */
    companion object {
        /** The textures to be contained in the ObjectTextures texture array */
        val objectTextures = listOf("ambient", "diffuse", "specular", "normal", "alphamask", "displacement")
        /** The ObjectTextures that should be mipmapped */
        val mipmappedObjectTextures = listOf("ambient", "diffuse", "specular")

        @JvmStatic @JvmOverloads fun fromImage(
            image: Image,
            repeatUVW: Triple<RepeatMode, RepeatMode, RepeatMode> = RepeatMode.Repeat.all(),
            borderColor: BorderColor = BorderColor.OpaqueBlack,
            normalized: Boolean = true,
            mipmap: Boolean = true,
            minFilter: FilteringMode = FilteringMode.Linear,
            maxFilter: FilteringMode = FilteringMode.Linear
        ): Texture {
            return Texture(Vector3i(image.width, image.height, 1),
                4, UnsignedByteType(), image.contents, repeatUVW, borderColor, normalized, mipmap)
        }
    }
}
