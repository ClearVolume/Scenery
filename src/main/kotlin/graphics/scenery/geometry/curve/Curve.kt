package graphics.scenery.geometry.curve

import graphics.scenery.geometry.Spline
import org.joml.Vector2f
import org.joml.Vector3f

data class Vertex(val v: Vector3f, val n: Vector3f, val uv: Vector2f)
data class Shape(val vertices: List<Vertex>)
typealias SegmentedShapeList = List<Shape>

fun List<Vector3f>.toShape(): Shape = Shape(this.map { Vertex(it, it, Vector2f()) })

/**
 * Interface providing the functionality of creating a geometry which evolves along a spline object.
 *
 * @author Justin Buerger
 */
interface Curve {

    /**
     * Spline object along which the curve is rendered.
     */
    val spline: Spline


    /**
     * List of shapes that will be drawn along the spline. Shapes are simple Polygons that act like a sleeve pulled
     * along the spline. The polygon points of a polygon and its predecessor are connected by the triangles.
     * Consider a simple curve, consisting of two triangles as base shapes (triangle1: {00, 01, 02},
     * triangle2: {10, 11, 12}. If we cut open resulting curve mesh (to view it in 2D) it would look like this:
     *
     *  00_______01_______02_______00
     *  | \     | \       | \      |
     *  |  \    |  \      |  \     |
     *  |   \   |   \     |   \    |
     *  |    \  |    \    |    \   |
     *  |     \ |     \   |     \  |
     *  10 ___ 11 _____ 12 ______ 10
     *
     */
    val baseShapes: () -> SegmentedShapeList
}
