package graphics.scenery.controls.behaviours

import cleargl.GLVector
import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.utils.LazyLogger
import net.java.games.input.Component
import kotlin.reflect.KProperty

/**
 * Implementation of GamepadBehaviour for Camera Movement Control
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[name] Name of the behaviour
 * @property[axis] List of axis that are assigned to this behaviour
 * @property[cam] The camera to control
 */
open class GamepadMovementControl(private val name: String,
                             override val axis: List<Component.Identifier>,
                             private val camera: () -> Node?) : GamepadBehaviour {
    /** Speed multiplier for camera movement */
    var speedMultiplier = 0.04f
    /** Threshold below which the behaviour does not trigger */
    var threshold = 0.05f

    private val cam: Node? by NodeDelegate()
    private val logger by LazyLogger()

    protected inner class NodeDelegate {
        /** Returns the [graphics.scenery.Camera] resulting from the evaluation of [camera] */
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Node? {
            return camera.invoke()
        }

        /** Setting the value is not supported */
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Node?) {
            throw UnsupportedOperationException()
        }
    }

    /**
     * This function is triggered upon arrival of an axis event that
     * concerns this behaviour. It takes the event's value to move the camera
     * in the corresponding direction.
     *
     * @param[axis] The gamepad axis.
     * @param[value] The absolute value of the gamepad axis.
     */
    @Synchronized
    override fun axisEvent(axis: Component.Identifier, value: Float) {
        cam?.let { cam ->
            if (Math.abs(value) < threshold) {
                return
            }

            if(cam is Camera) {
                when (axis) {
                    Component.Identifier.Axis.Y -> {
                        cam.position = cam.position + cam.forward * -1.0f * value * speedMultiplier
                    }
                    Component.Identifier.Axis.X -> {
                        cam.position = cam.position + cam.forward.cross(cam.up).normalized * value * speedMultiplier
                    }
                    Component.Identifier.Axis.Z -> {
                        cam.position = cam.position + cam.up * value * speedMultiplier
                    }
                }
            } else {
                when (axis) {
                    Component.Identifier.Axis.Y -> {
                        cam.position = cam.position + GLVector(0.0f, 0.0f, -1.0f) * -1.0f * value * speedMultiplier
                    }
                    Component.Identifier.Axis.X -> {
                        cam.position = cam.position + GLVector(1.0f, 0.0f, 0.0f) * value * speedMultiplier
                    }
                    Component.Identifier.Axis.Z -> {
                        cam.position = cam.position + GLVector(0.0f, 1.0f, 0.0f) * value * speedMultiplier
                    }
                }
            }
        }
//        System.err.println("Camera.position=${cam.position.x()}/${cam.position.y()}/${cam.position.z()}")
    }

}

