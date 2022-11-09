package graphics.scenery.controls.behaviours

import graphics.scenery.Node
import graphics.scenery.Scene
import graphics.scenery.attribute.spatial.Spatial
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDevice
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.VRPress.Companion.createAndSet
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import org.joml.Quaternionf
import org.joml.Vector3f
import org.scijava.ui.behaviour.DragBehaviour


/**
 * Grab and Drag nodes with a VR controller.
 *
 * Use the [createAndSet] method to create.
 *
 * When triggered and [controllerHitbox] is intersecting a node with a [Grabable] attribute
 * [onGrab] and then the respective functions of the Grabable attribute are called.
 * Also translations and rotations of the controller will be also applied to the node.
 *
 * @param targets Only nodes in this list may be dragged. They must have a [Grabable] attribute.
 * @param multiTarget If this is true all targets which collide with [controllerHitbox] will be dragged otherwise only one.
 *
 * @author Jan Tiemann
 */
open class VRGrab(
    protected val name: String,
    protected val controllerHitbox: Node,
    protected val targets: () -> List<Node>,
    protected val multiTarget: Boolean = false,
    protected val onGrab: ((Node) -> Unit)? = null,
    protected val onDrag: ((Node) -> Unit)? = null,
    protected val onRelease: ((Node) -> Unit)? = null
) : DragBehaviour, Enablable{

    override var enabled: Boolean = true

    protected val controllerSpatial: Spatial = controllerHitbox.spatialOrNull()
        ?: throw IllegalArgumentException("controller hitbox needs a spatial attribute")

    protected var selected = emptyList<Node>()

    protected var lastPos = Vector3f()
    protected var lastRotation = Quaternionf()

    /**
     * Called on the first frame this behavior is triggered.
     *
     * @param x invalid - residue from parent behavior. Use [controllerSpatial] instead.
     * @param y invalid - residue from parent behavior. Use [controllerSpatial] instead.
     */
    override fun init(x: Int, y: Int) {
        if (!enabled) return
        selected = targets().filter { box -> controllerHitbox.spatialOrNull()?.intersects(box) ?: false }
        if (!multiTarget) {
            selected = selected.take(1)
        }
        if (selected.isNotEmpty()) {
            onGrab?.let { it(selected.first()) }
        }
        selected.forEach {
            onGrab?.invoke(it)
            it.getAttributeOrNull(Grabable::class.java)?.onGrab?.invoke()
        }
        lastPos = controllerSpatial.worldPosition()
        lastRotation = controllerSpatial.worldRotation()
    }

    /**
     * Called on every frame this behavior is triggered.
     *
     * @param x invalid - residue from parent behavior. Use [controllerSpatial] instead.
     * @param y invalid - residue from parent behavior. Use [controllerSpatial] instead.
     */
    override fun drag(x: Int, y: Int) {
        if (!enabled) return
        val newPos = controllerHitbox.spatialOrNull()?.worldPosition() ?: Vector3f()
        val diffTranslation = newPos - lastPos
        val diffRotation = Quaternionf(controllerSpatial.worldRotation()).mul(lastRotation.conjugate())

        selected.forEach { node ->
            node.getAttributeOrNull(Grabable::class.java)?.let { grabable ->
                    val target = (grabable.target ?: node)
                    target.spatialOrNull()?.let { spatial ->
                    // apply parent world rotation to diff if available
                    val translationWorld = target.parent?.spatialOrNull()?.worldRotation()?.let { q -> diffTranslation.rotate(q) }
                        ?: diffTranslation
                    val parentScale = target.parent?.spatialOrNull()?.worldScale() ?: Vector3f(1f)
                    spatial.position += translationWorld / parentScale

                    if (!grabable.lockRotation) {
                        target.parent?.spatialOrNull()?.let { pSpatial ->
                            // if there is a parent spatial
                            // reverse parent rotation, apply diff rotation, apply parent rotation again
                            val worldRotationCache = pSpatial.worldRotation()
                            val newRotation = Quaternionf(worldRotationCache)
                                .mul(diffRotation)
                                .mul(worldRotationCache.conjugate())

                            spatial.rotation.premul(newRotation)
                        } ?: spatial.let {
                            spatial.rotation.premul(diffRotation)
                        }
                    }

                    onDrag?.invoke(node)
                    grabable.onDrag?.invoke()
                }
            }
        }

        lastPos = controllerSpatial.worldPosition()
        lastRotation = controllerSpatial.worldRotation()
    }

    /**
     * Called on the last frame this behavior is triggered.
     *
     * @param x invalid - residue from parent behavior. Use [controllerSpatial] instead.
     * @param y invalid - residue from parent behavior. Use [controllerSpatial] instead.
     */
    override fun end(x: Int, y: Int) {
        if (!enabled) return
        selected.forEach {
            onRelease?.invoke(it)
            it.getAttributeOrNull(Grabable::class.java)?.onRelease?.invoke()
        }
        selected = emptyList()
    }


    /**
     * Contains Convenience method for adding grab behaviour
     */
    companion object {

        /**
         * Convenience method for adding grab behaviour
         */
        fun createAndSet(
            scene: Scene,
            hmd: OpenVRHMD,
            button: List<OpenVRHMD.OpenVRButton>,
            controllerSide: List<TrackerRole>,
            onGrab: ((Node, TrackedDevice) -> Unit)? = { _, device -> (hmd as? OpenVRHMD)?.vibrate(device) },
            onDrag: ((Node, TrackedDevice) -> Unit)? = null,
            onRelease: ((Node, TrackedDevice) -> Unit)? = null
        ) : List<VRGrab>{
            val future = mutableListOf<VRGrab>()
            hmd.events.onDeviceConnect.add { _, device, _ ->
                if (device.type == TrackedDeviceType.Controller) {
                    device.model?.let { controller ->
                        if (controllerSide.contains(device.role)) {
                            val name = "VRGrab:${hmd.trackingSystemName}:${device.role}:$button"
                            val grabBehaviour = VRGrab(
                                name,
                                controller.children.first(),
                                { scene.discover(scene, { n -> n.getAttributeOrNull(Grabable::class.java) != null }) },
                                false,
                                { n -> onGrab?.invoke(n, device) },
                                { n -> onDrag?.invoke(n, device) },
                                { n -> onRelease?.invoke(n, device) }
                            )

                            hmd.addBehaviour(name, grabBehaviour)
                            button.forEach {
                                hmd.addKeyBinding(name, device.role, it)
                            }
                            future.add(grabBehaviour)
                        }
                    }
                }
            }
            return future
        }
    }
}

/**
 * Attribute which marks a node that can be grabbed by the [VRGrab] behavior.
 *
 * @param onGrab called in the first frame of the interaction
 * @param onDrag called each frame of the interaction
 * @param onRelease called in the last frame of the interaction
 * @param lockRotation if set to true dragging will only change the position not rotation
 * @param target set to apply movements not to this node but to another
 * */
open class Grabable(
    val onGrab: (() -> Unit)? = null,
    val onDrag: (() -> Unit)? = null,
    val onRelease: (() -> Unit)? = null,
    val lockRotation: Boolean = false,
    val target: Node? = null
)
