package graphics.scenery.volumes

import graphics.scenery.utils.Image
import graphics.scenery.utils.LazyLogger
import net.imagej.lut.LUTService
import org.scijava.plugin.Parameter
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Class for holding RGBA colormaps for volumes
 */
class Colormap(val buffer: ByteBuffer, val width: Int, val height: Int) {

    companion object {
        val logger by LazyLogger()

        @Parameter
        protected var lutService: LUTService? = null

        /**
         * Creates a new color map from a [ByteBuffer], with dimensions given as [width] and [height].
         */
        fun fromBuffer(buffer: ByteBuffer, width: Int, height: Int): Colormap {
            return Colormap(buffer.duplicate(), width, height)
        }

        /**
         * Creates a new colormap from an [array], with dimensions given as [width] and [height].
         */
        fun fromArray(array: ByteArray, width: Int, height: Int): Colormap {
            return Colormap(ByteBuffer.wrap(array), width, height)
        }

        /**
         * Creates a new colormap from an [stream], with the file type/extension given in [extension].
         */
        fun fromStream(stream: InputStream, extension: String): Colormap {
            val image = Image.readFromStream(stream, extension)
            logger.info("Read image from $stream with ${image.contents.remaining()} bytes, size=${image.width}x${image.height}")
            return Colormap(image.contents, image.width, image.height)
        }

        /**
         * Tries to load a colormap from a file. Available colormaps can be queried with [list].
         */
        fun get(name: String): Colormap {
            try {
                val luts = lutService?.findLUTs()
                val colorTable = luts?.let {
                    val url = it[name]
                    lutService?.loadLUT(url)
                } ?: throw IOException("Color map $name not found in ImageJ colormaps")

                val copies = 16
                val byteBuffer = ByteBuffer.allocateDirect(
                    4 * colorTable.length * copies) // Num bytes * num components * color map length * height of color map texture
                val tmp = ByteArray(4 * colorTable.length)
                for (k in 0 until colorTable.length) {
                    for (c in 0 until colorTable.componentCount) { // TODO this assumes numBits is 8, could be 16
                        tmp[4 * k + c] = colorTable[c, k].toByte()
                    }
                    if (colorTable.componentCount == 3) {
                        tmp[4 * k + 3] = 255.toByte()
                    }
                }
                for (i in 0 until copies) {
                    byteBuffer.put(tmp)
                }
                byteBuffer.flip()

                logger.info("Using ImageJ colormap $name with size ${colorTable.length}x$copies")
                return fromBuffer(byteBuffer, colorTable.length, copies)
            } catch(e: IOException) {
                logger.debug("LUT $name not found as ImageJ colormap, trying stream")
                logger.info("Using colormap $name from stream")
                val resource = this::class.java.getResourceAsStream("colormap-$name.png")
                    ?: throw FileNotFoundException("Could not find color map for name $name (colormap-$name.png)")

                return fromStream(resource, "png")
            }
        }

        /**
         * Returns a list of strings containing the names of the available color maps for use with [get].
         */
        fun list(): List<String> {
            // FIXME: Hardcoded for the moment, not nice.
            return listOf("grays", "hot", "jet", "plasma", "viridis")
        }
    }
}
