package graphics.scenery.geometry.curve
import graphics.scenery.Mesh
import graphics.scenery.geometry.Spline
import org.joml.Vector3f

/**
 * Constructs a geometry along the calculates points of a Spline.
 * The number n corresponds to the number of segments you wish to have between your control points.
 * The spline and the baseShape lambda must both have the same number of elements, otherwise, the curve is no
 * longer well-defined. Concerning the individual baseShapes, no lines must cross for the body of the curve to
 * be visualized flawlessly.
 *
 * In this implementation, a single mesh for the whole curve is created and added to [this] class as a child.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 * @param [baseShape] a lambda which returns all the baseShapes along the curve
 * @param [spline] the spline along which the geometry will be rendered
 */
class SingleMeshCurve(override val spline: Spline,
                      override val baseShapes: () -> List<List<Vector3f>>,
                      override val firstPerpendicularVector3f: Vector3f= Vector3f(0f, 0f, 0f),
                      countDifferentShapes: Int = 15
): FrenetOrientedCurve, Mesh("DefaultCurve") {

    override val frenetFrames: () -> List<FrenetFrame> =
        { FrenetFrameCalculation.computeFrenetFrames(spline, firstPerpendicularVector3f) }
    private val shapes = baseShapes.invoke()
    private val countList = ArrayList<Int>(countDifferentShapes).toMutableList()
    private val geometryCalculator = CurveGeometryCalculation
    init {
        //case of a single baseShape by construction
        if (countDifferentShapes == 1) {
            val transformedBaseShapes = transformedBaseShapes(baseShapes.invoke(), frenetFrames.invoke())
            val trianglesAndNormals = geometryCalculator.calculateTriangles(transformedBaseShapes, cover = CurveCover.Both)
            val partialCurveMesh = PartialCurveMesh(trianglesAndNormals.first, trianglesAndNormals.second)
            this.addChild(partialCurveMesh)
        }
        else {
            var partialCurveSize = 1
            shapes.windowed(2, 1) { frame ->
                when (frame[0].size) {
                    frame[1].size -> {
                        partialCurveSize++
                    }

                    else -> {
                        countList.add(partialCurveSize)
                        partialCurveSize = 1
                    }
                }
            }
            countList.add(partialCurveSize)
            //if there is only one baseShape, the countlist will have size one, making the computation simpler
            if (countList.size == 1) {
                val transformedBaseShapes = transformedBaseShapes(baseShapes.invoke(), frenetFrames.invoke())
                val trianglesAndNormals = geometryCalculator.calculateTriangles(transformedBaseShapes, cover = CurveCover.Both)
                val partialCurveMesh = PartialCurveMesh(trianglesAndNormals.first, trianglesAndNormals.second)
                this.addChild(partialCurveMesh)
            }
            var position = 0
            var lastShapeUnique = false
            if (countList.last() == 1) {
                countList.removeAt(countList.lastIndex)
                lastShapeUnique = true
            }
            else {
                countList.forEachIndexed { index, count ->
                    val incompleteShapes = baseShapes.invoke()
                    val shapes = ArrayList<List<Vector3f>>(incompleteShapes.size+countList.size)
                    val incompleteFrames = frenetFrames.invoke()
                    val frames = ArrayList<FrenetFrame>(shapes.size)
                    for (j in 0 until count) {
                        shapes.add(incompleteShapes[position])
                        frames.add(incompleteFrames[position])
                        position++
                    }
                    val helpPosition = position
                    //fill the gaps between the different shapes
                    if (helpPosition < frames.lastIndex) {
                        shapes.add(incompleteShapes[helpPosition - 1])
                        frames.add(incompleteFrames[helpPosition-1])
                    }
                    //edge case: the last shape is different from its predecessor
                    if (lastShapeUnique && helpPosition == incompleteFrames.lastIndex) {
                        shapes.add(incompleteShapes[helpPosition - 1])
                        frames.add(incompleteFrames[helpPosition-1])
                    }
                    val cover = when (index) {
                        0 -> {
                            CurveCover.Top
                        }
                        countList.size - 1 -> {
                            CurveCover.Bottom
                        }
                        else -> {
                            CurveCover.None
                        }
                    }
                    val transformedShapes = transformedBaseShapes(shapes, frames)
                    val trianglesAndNormals = geometryCalculator.calculateTriangles(transformedShapes, cover = cover)
                    val partialCurveMesh = PartialCurveMesh(trianglesAndNormals.first, trianglesAndNormals.second)
                    this.addChild(partialCurveMesh)
                }
            }
        }
    }
}
