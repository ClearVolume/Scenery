package graphics.scenery.controls.behaviours

import graphics.scenery.BoundingGrid
import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.scijava.ui.behaviour.DragBehaviour
import kotlin.reflect.KProperty

/**
 * Drag nodes roughly along the view plane axis by mouse.
 * Implements algorithm from https://forum.unity.com/threads/implement-a-drag-and-drop-script-with-c.130515/
 *
 * @author Jan Tiemann <j.tiemann@hzdr.de>
 */
open class MouseDrag(
    protected val name: String,
    protected val camera: () -> Camera?,
    protected var debugRaycast: Boolean = false,
    var ignoredObjects: List<Class<*>> = listOf<Class<*>>(BoundingGrid::class.java)
) : DragBehaviour {

    protected val logger by LazyLogger()
    protected val cam: Camera? by CameraDelegate()

    protected var currentNode: Node? = null
    protected var distance: Float = 0f

    /** Camera delegate class, converting lambdas to Cameras. */
    protected inner class CameraDelegate {
        /** Returns the [graphics.scenery.Camera] resulting from the evaluation of [camera] */
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Camera? {
            return camera.invoke()
        }

        /** Setting the value is not supported */
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Camera?) {
            throw UnsupportedOperationException()
        }
    }

    override fun init(x: Int, y: Int) {
        cam?.let { cam ->
            val matches = cam.getNodesForScreenSpacePosition(x, y, ignoredObjects, debugRaycast)
            currentNode = matches.matches.firstOrNull()?.node

            distance = currentNode?.position?.distance(cam.position) ?: 0f
        }
    }

    override fun drag(x: Int, y: Int) {
        if (distance <= 0)
            return

        cam?.let {
            val (rayStart, rayDir) = it.screenPointToRay(x, y)
            rayDir.normalize()
            val newPos = rayStart + rayDir * distance

            currentNode?.position = newPos
        }
    }

    override fun end(x: Int, y: Int) {}
}
