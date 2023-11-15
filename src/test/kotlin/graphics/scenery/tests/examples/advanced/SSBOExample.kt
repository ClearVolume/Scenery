package graphics.scenery.tests.examples.advanced

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Renderer
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.primitives.SSBOCainTest
import graphics.scenery.primitives.SSBOTest
import graphics.scenery.tests.examples.basic.TexturedCubeExample
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import org.joml.Vector3i
import org.joml.Vector4f
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * <Description>
 *
 * @author Konrad Michel <konrad.michel@mailbox.tu-dresden.de>
 */
class SSBOExample : SceneryBase("SSBOExample", wantREPL = false) {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val hull = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        hull.material {
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(hull)

        val light = PointLight(radius = 20.0f)
        light.spatial().position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 1.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        val compute = SSBOCainTest()
        compute.buffers {
            updateSSBOEntry("ssbosOutput", 0, "Color1", Vector4f(1.0f, 0.5f, 0.3f, 1.0f))
        }
        val ssboTestObj = SSBOTest()
        compute.addChild(ssboTestObj)
        scene.addChild(compute)

        thread {
            var x = 0.0f
            var y = 0.0f
            var z = 0.0f
            var runner = 0
            while (running) {
                //do buffer updating here
                runner++
                Thread.sleep(20)
                x = (sin(runner / 100.0f) + 1.0f) / 2.0f
                compute.buffers {
                    updateSSBOEntry("ssbosInput", 0, "Color1", Vector4f(x, y, z, 1.0f))
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SSBOExample().main()
        }
    }
}


