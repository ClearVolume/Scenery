package graphics.scenery

import graphics.scenery.utils.extensions.minus
import org.joml.Vector3f
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Constructs a plane with the dimensions given in [sizes].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @param[sizes] The dimensions of the plane.
 */
open class Plane(sizes: Vector3f) : Mesh() {
    init {
        this.scale = sizes
        this.name = "plane"
        val side = 2.0f
        val side2 = side / 2.0f

        vertices = BufferUtils.allocateFloatAndPut(floatArrayOf(
                // Front
                -side2, -side2, side2,
                side2, -side2, side2,
                side2,  side2, side2,
                -side2,  side2, side2
        ))

        normals = BufferUtils.allocateFloatAndPut(floatArrayOf(
                // Front
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        ))

        indices = BufferUtils.allocateIntAndPut(intArrayOf(
                0,1,2,0,2,3
        ))

        texcoords = BufferUtils.allocateFloatAndPut(floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        ))

        boundingBox = generateBoundingBox()
    }

    constructor(lowerLeft: Vector3f, upperLeft: Vector3f, lowerRight: Vector3f, upperRight: Vector3f) : this(Vector3f(1.0f)) {
        val vr = lowerRight - lowerLeft
        val vu = upperLeft - lowerLeft
        val vn = Vector3f(vr).cross(vu).normalize()

        vertices = BufferUtils.allocateFloatAndPut(floatArrayOf(
            // Front
            lowerLeft.x, lowerLeft.y, lowerLeft.z,
            lowerRight.x, lowerRight.y, lowerRight.z,
            upperRight.x, upperRight.y, upperRight.z,
            upperLeft.x, upperLeft.y, upperLeft.z
        ))

        normals = BufferUtils.allocateFloatAndPut(floatArrayOf(
            // Front
            vn.x, vn.y, vn.z,
            vn.x, vn.y, vn.z,
            vn.x, vn.y, vn.z,
            vn.x, vn.y, vn.z
        ))

        indices = BufferUtils.allocateIntAndPut(intArrayOf(
            0,1,2,0,2,3
        ))

        texcoords = BufferUtils.allocateFloatAndPut(floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
        ))

        boundingBox = generateBoundingBox()
    }
}
