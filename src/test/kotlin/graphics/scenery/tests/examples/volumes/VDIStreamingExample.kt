package graphics.scenery.tests.examples.volumes

import graphics.scenery.Camera
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.SceneryBase
import graphics.scenery.backends.Renderer
import graphics.scenery.volumes.*
import graphics.scenery.volumes.vdi.VDIStreamer
import org.joml.Vector3f
import java.nio.file.Paths
import org.zeromq.ZContext
import kotlin.math.max

class VDIStreamingExample : SceneryBase("VDI Streaming Example", 512, 512) {

    val cam: Camera = DetachedHeadCamera()

    val maxSupersegments = 20
    override fun init() {

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        //Step 1: create necessary components: camera, volume, volumeManager
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.5f, 5.0f)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            scene.addChild(this)
        }

        val volume = Volume.fromPathRaw(Paths.get(getDemoFilesPath() + "/volumes/box-iso/"), hub)
        volume.name = "volume"
        volume.colormap = Colormap.get("viridis")
        volume.spatial {
            position = Vector3f(0.0f, 0.0f, -3.5f)
            rotation = rotation.rotateXYZ(0.05f, 0.05f, 0.05f)
            scale = Vector3f(20.0f, 20.0f, 20.0f)
        }
        volume.transferFunction = TransferFunction.ramp(0.1f, 0.5f)
        scene.addChild(volume)

        // Step 2: Create VDI Volume Manager
        val vdiVolumeManager = VDIVolumeManager( hub, windowWidth, windowHeight, maxSupersegments, scene).createVDIVolumeManger()

        //step 3: switch the volume's current volume manager to VDI volume manager
        volume.volumeManager = vdiVolumeManager

        // Step 4: add the volume to VDI volume manager
        vdiVolumeManager.add(volume)

        // Step 5: add the VDI volume manager to the hub
        hub.add(vdiVolumeManager)

        //Step  6: transmitting the VDI
        val volumeDimensions = Vector3f(volume.getDimensions().x.toFloat(),volume.getDimensions().y.toFloat(),volume.getDimensions().z.toFloat())
        val model = volume.spatial().world

        val vdiStreamer = VDIStreamer()

        vdiStreamer.vdiStreaming = true
        vdiStreamer.streamVDI("tcp://0.0.0.0:6655", cam, volumeDimensions, model, maxSupersegments, vdiVolumeManager,
            renderer!!
        )
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VDIStreamingExample().main()
        }
    }
}
