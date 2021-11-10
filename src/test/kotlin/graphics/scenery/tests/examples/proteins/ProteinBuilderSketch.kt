package graphics.scenery.tests.examples.proteins

import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Renderer
import graphics.scenery.flythroughs.ProteinBuilder
import graphics.scenery.numerics.Random
import graphics.scenery.proteins.Protein
import graphics.scenery.proteins.RibbonDiagram
import graphics.scenery.tests.examples.basic.PictureDisplayExample
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import org.joml.Vector3f

class ProteinBuilderSketch: SceneryBase("ProteinBuilder", wantREPL = true, windowWidth = 1280, windowHeight = 720) {
    private val protein = Protein.fromID("3nir")
    private val ribbon = RibbonDiagram(protein, false)
    //private val cross = Cross()

    override fun init() {

        //initialize ribbondiagram
        ribbon.name = "3nir"
        ribbon.visible = false
        scene.addChild(ribbon)


        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val rowSize = 20f

        val lightbox = Box(Vector3f(50.0f, 50.0f, 50.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material {
            diffuse = Vector3f(0.1f, 0.1f, 0.1f)
            roughness = 1.0f
            metallic = 0.0f
            cullingMode = Material.CullingMode.None
        }
        scene.addChild(lightbox)
        val lights = (0 until 8).map {
            val l = PointLight(radius = 80.0f)
            l.spatial().position = Vector3f(
                Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                Random.randomFromRange(1.0f, 5.0f)
            )
            l.emissionColor = Random.random3DVectorFromRange( 0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            lightbox.addChild(l)
            l
        }

        //lights.forEach { lightbox.addChild(it) }

        val light = PointLight(radius = 15.0f)
        light.spatial().position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cameraLight = PointLight(radius = 3.0f)
        cameraLight.name = "CameraLight"
        cameraLight.emissionColor = Vector3f(1.0f, 1.0f, 0.0f)
        cameraLight.intensity = 2.0f


        val cam: Camera = DetachedHeadCamera()
        cam.name = "camera"
        cam.spatial().position = Vector3f(0.0f, 0.0f, 15.0f)
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)


        scene.addChild(cam)


        /*
        //add a cross to prevent motion sickness
        cam.addChild(cross)
        //move the cross
        val quaternion = Quaternionf()
        cam.rotation.conjugate(quaternion).transform(cross.position)
        val forwardTimesTwo = Vector3f()
        if(cam.targeted) {
            cross.position.add(cam.target.mul(-1f, forwardTimesTwo))
        }
        else {
            cross.position.add(cam.forward.mul(-1f, forwardTimesTwo))
        }

         */
        //cam.addChild(cameraLight)
    }

    override fun inputSetup() {
        super.inputSetup()
        val builder = ProteinBuilder( ribbon, {scene.activeObserver}, scene, ribbon.name )
        inputHandler?.addBehaviour("builder", builder)
        inputHandler?.addKeyBinding("builder", "E")

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ProteinBuilderSketch().main()
        }
    }
}
