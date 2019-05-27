package graphics.scenery

import cleargl.GLVector
import java.lang.Math.max
import java.lang.Math.min

/**
 * Oriented bounding box class to perform easy intersection tests.
 *
 * @property[min] The x/y/z minima for the bounding box.
 * @property[max] The x/y/z maxima for the bounding box.
 */
open class OrientedBoundingBox(val n: Node, val min: GLVector, val max: GLVector) {
    /**
     * Bounding sphere class, a bounding sphere is defined by an origin and a radius,
     * to enclose all of the Node's geometry.
     */
    data class BoundingSphere(val origin: GLVector, val radius: Float)

    /**
     * Alternative [OrientedBoundingBox] constructor taking the [min] and [max] as a series of floats.
     */
    constructor(n: Node, xMin: Float, yMin: Float, zMin: Float, xMax: Float, yMax: Float, zMax: Float) : this(n, GLVector(xMin, yMin, zMin), GLVector(xMax, yMax, zMax))

    /**
     * Alternative [OrientedBoundingBox] constructor, taking a 6-element float array for [min] and [max].
     */
    constructor(n: Node, boundingBox: FloatArray) : this(n, GLVector(boundingBox[0], boundingBox[2], boundingBox[4]), GLVector(boundingBox[1], boundingBox[3], boundingBox[5]))

    /**
     * Returns the maximum bounding sphere of this bounding box.
     */
    fun getBoundingSphere(): BoundingSphere {
        if(n.needsUpdate || n.needsUpdateWorld) {
            n.updateWorld(true, false)
        }

        val worldMin = n.worldPosition(min)
        val worldMax = n.worldPosition(max)

        val origin = worldMin + (worldMax - worldMin) * 0.5f

        val radius = (worldMax - origin).magnitude()

        return BoundingSphere(origin, radius)
    }

    /**
     * Checks this [OrientedBoundingBox] for intersection with [other], and returns
     * true if the bounding boxes do intersect.
     */
    fun intersects(other: OrientedBoundingBox): Boolean {
        return other.getBoundingSphere().radius + getBoundingSphere().radius > (other.getBoundingSphere().origin - getBoundingSphere().origin).magnitude()
    }

    /**
     * Returns the hash code of this [OrientedBoundingBox], taking [min] and [max] into consideration.
     */
    override fun hashCode(): Int {
        return min.hashCode() + max.hashCode()
    }

    /**
     * Compares this bounding box to [other], returning true if they are equal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as? OrientedBoundingBox ?: return false

        if (min.hashCode() != other.min.hashCode()) return false
        if (max.hashCode() != other.max.hashCode()) return false

        return true
    }

    /**
     * Return an [OrientedBoundingBox] that covers both [lhs] and [rhs].
     */
    fun expand(lhs: OrientedBoundingBox, rhs: OrientedBoundingBox): OrientedBoundingBox {
        return OrientedBoundingBox(lhs.n,
            min(lhs.min.x(), rhs.min.x()),
            min(lhs.min.y(), rhs.min.y()),
            min(lhs.min.z(), rhs.min.z()),
            max(lhs.max.x(), rhs.max.x()),
            max(lhs.max.y(), rhs.max.y()),
            max(lhs.max.z(), rhs.max.z()))
    }
}
