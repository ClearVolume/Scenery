package graphics.scenery.tests.examples.stresstests

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.utils.Numerics
import org.junit.Test
import java.io.IOException
import kotlin.concurrent.thread

/**
* <Description>
*
* @author Ulrik Günther <hello@ulrik.is>
*/
class PowerplantExample: SceneryDefaultApplication("PowerplantExample", windowWidth = 1280, windowHeight = 720) {
    override fun init() {
        try {
            val lightCount = 127

            renderer = Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
            hub.add(SceneryElement.Renderer, renderer!!)

            val cam: Camera = DetachedHeadCamera()
            cam.position = GLVector(0.0f, 0.0f, 0.0f)
            cam.perspectiveCamera(50.0f, windowWidth.toFloat(), windowHeight.toFloat(), nearPlaneLocation = 0.5f, farPlaneLocation = 1000.0f)
            cam.active = true

            scene.addChild(cam)

            val boxes = (0..lightCount).map {
                Box(GLVector(0.5f, 0.5f, 0.5f))
            }

            val lights = (0..lightCount).map {
                PointLight()
            }

            boxes.mapIndexed { i, box ->
                box.material = Material()
                box.addChild(lights[i])
                scene.addChild(box)
            }

            lights.map {
                it.emissionColor = Numerics.randomVectorFromRange(3, 0.0f, 1.0f)
                it.parent?.material?.diffuse = it.emissionColor
                it.intensity = Numerics.randomFromRange(0.01f, 10f)
                it.linear = 0.01f
                it.quadratic = 0.01f

                scene.addChild(it)
            }

            val hullbox = Box(GLVector(300.0f, 300.0f, 300.0f))
            hullbox.position = GLVector(0.0f, 0.0f, 0.0f)

            val hullboxMaterial = Material()
            hullboxMaterial.ambient = GLVector(0.6f, 0.6f, 0.6f)
            hullboxMaterial.diffuse = GLVector(0.4f, 0.4f, 0.4f)
            hullboxMaterial.specular = GLVector(0.0f, 0.0f, 0.0f)
            hullboxMaterial.doubleSided = true
            hullbox.material = hullboxMaterial

            val plantMaterial = Material()
            plantMaterial.ambient = GLVector(0.8f, 0.8f, 0.8f)
            plantMaterial.diffuse = GLVector(0.5f, 0.5f, 0.5f)
            plantMaterial.specular = GLVector(0.1f, 0f, 0f)

            val plant = Mesh()
            plant.readFromOBJ(getDemoFilesPath() + "/powerplant.obj", useMTL = true)
            plant.position = GLVector(0.0f, 0.0f, 0.0f)
            // for powerplant
            plant.scale = GLVector(0.001f, 0.001f, 0.001f)
            plant.material = Material()
            plant.children.forEach { it.material = plantMaterial }
            plant.updateWorld(true, true)
            plant.name = "rungholt"

            scene.addChild(plant)

            var ticks: Int = 0

            System.out.println(scene.children)

            thread {
                while (true) {
                    boxes.mapIndexed {
                        i, box ->
                        val phi = Math.PI * 2.0f * ticks / 2500.0f

                        box.position = GLVector(
                            -128.0f+18.0f*(i+1),
                            5.0f+i*5.0f,
                            (i+1) * 50 * Math.cos(phi+(i*0.2f)).toFloat())

                        box.children[0].position = box.position

                    }

                    ticks++


                    Thread.sleep(10)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    @Test override fun main() {
        super.main()
    }

}

