package graphics.scenery.volumes.bdv

import java.nio.ByteBuffer
import net.imglib2.RandomAccessibleInterval
import net.imglib2.realtransform.AffineTransform3D
import tpietzsch.multires.SimpleStack3D

open class BufferedSimpleStack3D<T>(internal val backingBuffer : ByteBuffer, internal val type : T, val dimensions : IntArray) : SimpleStack3D<T> {

    val buffer : ByteBuffer
        get() = backingBuffer.duplicate()

    /**
     * Get the image data.
     *
     * @return the image.
     */
    override fun getImage() : RandomAccessibleInterval<T>? {
        return null
    }

    /**
     * Get the transformation from image coordinates to world coordinates.
     *
     * @return transformation from image coordinates to world coordinates.
     */
    override fun getSourceTransform() : AffineTransform3D? {
        return null
    }

    override fun numDimensions() : Int {
        return 3
    }

    override fun getType() : T {
        return type
    }
}
