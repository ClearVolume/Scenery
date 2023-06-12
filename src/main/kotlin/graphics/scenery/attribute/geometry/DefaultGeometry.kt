package graphics.scenery.attribute.geometry

import graphics.scenery.BufferUtils
import graphics.scenery.Node
import graphics.scenery.OrientedBoundingBox
import graphics.scenery.attribute.buffers.BufferType
import graphics.scenery.attribute.buffers.Buffers
import graphics.scenery.geometry.GeometryType
import graphics.scenery.utils.SystemHelpers.Companion.logger
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.real.FloatType
import org.joml.Vector3f
import java.nio.Buffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

open class DefaultGeometry(private var node: Node): Geometry {
    override var buffers: MutableMap<String, Buffer> = mutableMapOf(
        "vertices" to BufferUtils.allocateFloat(0),
        "normals" to BufferUtils.allocateFloat(0),
        "texcoords" to BufferUtils.allocateFloat(0),
        "indices" to BufferUtils.allocateInt(0)
    )
    override var description: LinkedHashMap<String, Buffers.Description> = linkedMapOf(
        "vertices" to Buffers.Description(BufferType.Primitive(FloatType()), 3),
        "normals" to Buffers.Description(BufferType.Primitive(FloatType()), 3),
        "texcoords" to Buffers.Description(BufferType.Primitive(FloatType()), 2),
        "indices" to Buffers.Description(BufferType.Primitive(IntType()), 1),
    )
    @delegate:Transient override var vertices: FloatBuffer by buffers
    @delegate:Transient override var normals: FloatBuffer by buffers
    @delegate:Transient override var texcoords: FloatBuffer by buffers
    @delegate:Transient override var indices: IntBuffer by buffers
    override var dirtySSBOs = false
    override var vertexSize = 3
    override var texcoordSize = 2
    override var dirty: Boolean = true
    override var geometryType = GeometryType.TRIANGLES

    override fun generateBoundingBox(children: List<Node>): OrientedBoundingBox? {
        val vertexBufferView = vertices.asReadOnlyBuffer()
        val boundingBoxCoords = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)

        if (vertexBufferView.capacity() == 0 || vertexBufferView.remaining() == 0) {
            val boundingBox = if(!children.none()) {
                node.getMaximumBoundingBox()
            } else {
                logger.warn("$node.name: Zero vertices currently, returning empty bounding box")
                OrientedBoundingBox(node,0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f)
            }

            return boundingBox
        } else {

            val vertex = floatArrayOf(0.0f, 0.0f, 0.0f)
            vertexBufferView.get(vertex)

            boundingBoxCoords[0] = vertex[0]
            boundingBoxCoords[1] = vertex[0]

            boundingBoxCoords[2] = vertex[1]
            boundingBoxCoords[3] = vertex[1]

            boundingBoxCoords[4] = vertex[2]
            boundingBoxCoords[5] = vertex[2]

            while (vertexBufferView.remaining() >= 3) {
                vertexBufferView.get(vertex)

                boundingBoxCoords[0] = minOf(boundingBoxCoords[0], vertex[0])
                boundingBoxCoords[2] = minOf(boundingBoxCoords[2], vertex[1])
                boundingBoxCoords[4] = minOf(boundingBoxCoords[4], vertex[2])

                boundingBoxCoords[1] = maxOf(boundingBoxCoords[1], vertex[0])
                boundingBoxCoords[3] = maxOf(boundingBoxCoords[3], vertex[1])
                boundingBoxCoords[5] = maxOf(boundingBoxCoords[5], vertex[2])
            }
            logger.debug("$node.name: Calculated bounding box with ${boundingBoxCoords.joinToString(", ")}")
            return OrientedBoundingBox(
                node, Vector3f(boundingBoxCoords[0], boundingBoxCoords[2], boundingBoxCoords[4]),
                Vector3f(boundingBoxCoords[1], boundingBoxCoords[3], boundingBoxCoords[5])
            )
        }
    }

}
