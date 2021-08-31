package graphics.scenery.flythroughs

import graphics.scenery.*
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.geometry.Curve
import graphics.scenery.primitives.Arrow
import graphics.scenery.primitives.Cylinder
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.minus
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import org.scijava.ui.behaviour.ClickBehaviour
import kotlin.math.acos

/**
 * class that lets a curve or spline object fly in front of the camera from beginning to end
 */
class ReverseRollercoaster(val scene: Scene, val cam: ()->Camera?, val name: String): ClickBehaviour {
    val logger by LazyLogger()
    val curve: Node = scene.children.filter{it.name == name}[0]
    val camera = cam.invoke()
    val frames = if(curve is Curve) { curve.frenetFrames } else { ArrayList() }
    private val spaceBetweenFrames = ArrayList<Float>(frames.size-1)

    var i = 0
    init {
        val arrows = Mesh("arrows")
        //debug arrows
        val matFaint = DefaultMaterial()
        matFaint.diffuse  = Vector3f(0.0f, 0.6f, 0.6f)
        matFaint.ambient  = Vector3f(1.0f, 1.0f, 1.0f)
        matFaint.specular = Vector3f(1.0f, 1.0f, 1.0f)
        matFaint.cullingMode = Material.CullingMode.None
        frames.forEachIndexed { index, it ->
            if(index%20 == 0) {
                val arrowX = Arrow(it.binormal - Vector3f())
                arrowX.edgeWidth = 0.5f
                arrowX.addAttribute(Material::class.java, matFaint)
                arrowX.spatial().position = it.translation
                arrows.addChild(arrowX)
                val arrowY = Arrow(it.normal - Vector3f())
                arrowY.edgeWidth = 0.5f
                arrowY.addAttribute(Material::class.java, matFaint)
                arrowY.spatial().position = it.translation
                arrows.addChild(arrowY)
                val arrowZ = Arrow(it.tangent - Vector3f())
                arrowZ.edgeWidth = 0.5f
                arrowZ.addAttribute(Material::class.java, matFaint)
                arrowZ.spatial().position = it.translation
                arrows.addChild(arrowZ)
            }
        }
        scene.addChild(arrows)
        frames.windowed(2, 1) {
            val frameToFrame = Vector3f()
            it[0].translation.sub(it[1].translation, frameToFrame)
            spaceBetweenFrames.add(frameToFrame.length())
        }
    }
    
    override fun click(x: Int, y: Int) {
        val forward = if(camera != null) { Vector3f(camera.forward) } else { logger.warn("Cam is Null!"); Vector3f() }
        val up = if(camera != null) { Vector3f(camera.up) } else { logger.warn("Cam is Null!"); Vector3f() }
        if (i <= frames.lastIndex) {
            //rotation
            val tangent = frames[i].tangent
            //tangent.mul(-1f)
            // euler angles
            val angleX = calcAngle(Vector2f(forward.y, forward.z), Vector2f(tangent.y, tangent.z))
            val angleY = calcAngle(Vector2f(forward.x, forward.z), Vector2f(tangent.x, tangent.z))
            val angleZ = calcAngle(Vector2f(forward.x, forward.y), Vector2f(tangent.x, tangent.y))
            //val curveRotation = Quaternionf().rotateXYZ(angleX, angleY, angleZ).normalize()
            val curveRotation = Quaternionf().lookAlong(tangent, up).normalize()

            scene.children.filter{it.name == name}[0].ifSpatial {
                rotation = curveRotation
            }
            scene.children.filter { it.name == "arrows" }[0].ifSpatial { rotation = curveRotation }
            //position
            scene.children.filter{it.name == name}[0].ifSpatial {
                if(i == 0) {
                    //initial position right before camera
                    val stretchedForward = Vector3f(forward).mul(0.5f)
                    val beforeCam = (Vector3f(camera?.spatial()?.position!!)) //.add(stretchedForward)
                    val frameToBeforeCam = Vector3f(frames[0].translation).sub(beforeCam)
                    val initialPosition = Vector3f(position).add(frameToBeforeCam)
                    position = initialPosition
                    scene.children.filter { it.name == "arrows" }[0].ifSpatial { position = initialPosition }
                    //debug arrows
                    val cylinder = Cylinder(0.05f, beforeCam.length(), 6)
                    cylinder.spatial().position = beforeCam
                    cylinder.spatial().orientBetweenPoints(beforeCam, frames[0].translation)
                    scene.addChild(cylinder)

                }
                else {
                    val index = i
                    val frame = frames[index-1]
                    val nextFrame = frames[index]
                    val translation = Vector3f(frame.tangent).mul(-1f).mul(Vector3f(Vector3f(nextFrame.translation).sub(Vector3f(frame.translation))).length())
                    val position1 = Vector3f(position).add(translation)
                    position = position1
                    scene.children.filter { it.name == "arrows" }[0].ifSpatial { position = position1 }
                }
            }
            i += 1
        }
    }

    private fun calcAngle(vec1: Vector2f, vec2: Vector2f): Float {
        vec1.normalize()
        vec2.normalize()
        // normalize will return NaN if one vector is the null vector
        return if(!vec1.x.isNaN() && !vec1.y.isNaN() && !vec2.x.isNaN() && !vec2.y.isNaN()) {
            val cosAngle = vec1.dot(vec2).toDouble()
            var angle = if(cosAngle > 1) { 0.0 } else { acos(cosAngle) }
            /*
            // negative angle?
            vec1.x = -vec1.x
            if(vec2.dot(vec1) > 0) { angle *= -1.0}

             */
            angle.toFloat()
        } else  {
            0f
        }
    }
}
