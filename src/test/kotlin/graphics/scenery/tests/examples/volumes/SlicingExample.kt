package graphics.scenery.tests.examples.volumes

import bdv.spimdata.XmlIoSpimDataMinimal
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.SlicingPlane
import graphics.scenery.volumes.Volume
import org.joml.Quaternionf
import org.joml.Vector3f
import org.scijava.ui.behaviour.ClickBehaviour
import tpietzsch.example2.VolumeViewerOptions
import kotlin.concurrent.thread
import graphics.scenery.numerics.Random

/**
 * Volume Slicing Example using the "BDV Rendering Example loading a RAII"
 *
 * Press "R" to add a moving slicing plane and "T" to remove one.
 *
 * @author  Jan Tiemann <j.tiemann@hzdr.de>
 */
class SlicingExample : SceneryBase("Volume Slicing example", 1280, 720) {
    lateinit var volume: Volume
    var slicingPlanes = mapOf<SlicingPlane,Animator>()


    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            position = Vector3f(0.0f, 0.0f, 5.0f)
            scene.addChild(this)
        }

        val file = "export.xml"
        val options = VolumeViewerOptions().maxCacheSizeInMB(512)
        
        // slicing works with this line
        for(i in 0..4) {
        // slicing does not work with this line
//        for(i in 0..5) {
            volume = Volume.fromSpimData(XmlIoSpimDataMinimal().load(file), hub, options)
            volume.scale = Vector3f(Random.randomFromRange(5.0f, 25.0f))
            volume.rotation = Random.randomQuaternion()
            volume.transferFunction.addControlPoint(0.0f, 0.0f)
            scene.addChild(volume)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.None
        shell.material.diffuse = Vector3f(0.2f, 0.2f, 0.2f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        scene.addChild(shell)

        Light.createLightTetrahedron<PointLight>(spread = 4.0f, radius = 15.0f, intensity = 0.5f)
            .forEach { scene.addChild(it) }

        val origin = Box(Vector3f(0.1f, 0.1f, 0.1f))
        origin.material.diffuse = Vector3f(0.8f, 0.0f, 0.0f)
        scene.addChild(origin)

        addAnimatedSlicingPlane()

        thread {
            while (running) {
                slicingPlanes.entries.forEach { it.value.animate() }
                Thread.sleep(20)
            }
        }
    }

    class Animator(val node: Node) {
        private var moveDir = Random.random3DVectorFromRange(-1.0f, 1.0f) - node.position
        private var rotationStart = Quaternionf(node.rotation)
        private var rotationTarget = Random.randomQuaternion()
        private var startTime = System.currentTimeMillis()

        fun animate() {
            val relDelta = (System.currentTimeMillis() - startTime) / 5000f

            val scaledMov = moveDir * (20f / 5000f)
            node.position = node.position + scaledMov

            rotationStart.nlerp(rotationTarget, relDelta, node.rotation)

            node.needsUpdate = true

            if (startTime + 5000 < System.currentTimeMillis()) {
                moveDir = Random.random3DVectorFromRange(-1.0f, 1.0f) - node.position
                rotationStart = Quaternionf(node.rotation)
                rotationTarget = Random.randomQuaternion()
                startTime = System.currentTimeMillis()
            }
        }
    }

    private fun addAnimatedSlicingPlane(){

        val slicingPlane = createSlicingPlane()
        slicingPlane.addTargetVolume(volume.volumeManager)
        val animator = Animator(slicingPlane)

        slicingPlanes = slicingPlanes + (slicingPlane to animator)
    }

    private fun removeAnimatedSlicingPlane(){
        if (slicingPlanes.isEmpty())
            return
        val (plane,_) = slicingPlanes.entries.first()
        scene.removeChild(plane)
        plane.removeTargetVolume(volume.volumeManager)
        slicingPlanes = slicingPlanes.toMutableMap().let { it.remove(plane); it}
    }

    private fun createSlicingPlane(): SlicingPlane {

        val slicingPlaneFunctionality = SlicingPlane()
        scene.addChild(slicingPlaneFunctionality)

        val slicingPlaneVisual: Node
        slicingPlaneVisual = Box(Vector3f(1f, 0.01f, 1f))
        slicingPlaneVisual.material.diffuse = Vector3f(0.0f, 0.8f, 0.0f)
        slicingPlaneFunctionality.addChild(slicingPlaneVisual)

        val nose = Box(Vector3f(0.1f, 0.1f, 0.1f))
        nose.material.diffuse = Vector3f(0.8f, 0.0f, 0.0f)
        nose.position = Vector3f(0f, 0.05f, 0f)
        slicingPlaneVisual.addChild(nose)

        return slicingPlaneFunctionality
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        inputHandler?.addBehaviour("addPlane", ClickBehaviour { _, _ -> addAnimatedSlicingPlane() })
        inputHandler?.addKeyBinding("addPlane", "R")
        inputHandler?.addBehaviour("removePlane", ClickBehaviour { _, _ -> removeAnimatedSlicingPlane() })
        inputHandler?.addKeyBinding("removePlane", "T")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SlicingExample().main()
        }
    }
}
