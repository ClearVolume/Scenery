package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.primitives.ParticleGlyphs
import graphics.scenery.attribute.material.Material


/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class ParticleGlyphsExample : SceneryBase("ParticleGlyphsExample") {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val hull = Box(Vector3f(1000.0f, 1000.0f, 1000.0f), insideNormals = true)
        hull.material {
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            cullingMode = Material.CullingMode.Front
        }
        scene.addChild(hull)

        val particlePositions = mutableListOf<Vector3f>()
        val particleProperties = mutableListOf<Vector3f>()
        for (i in 0..5) {
            for (j in 0..5) {
                for (k in 0..5) {
                    particlePositions.add(Vector3f(i.toFloat()/10.0f, j.toFloat()/10.0f, k.toFloat()/10.0f))
                    particleProperties.add(Vector3f(0.05f, 0.0f, 0.0f))
                }
            }
        }
        val particleGlyphs = ParticleGlyphs(particlePositions, particleProperties)
        particleGlyphs.name = "Particles?"
        scene.addChild(particleGlyphs)



        val light0 = PointLight(radius = 500.0f)
        light0.intensity = 10.0f
        light0.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light0)

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, 0.0f, 15.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        cam.target = Vector3f(0.0f, 0.0f, 0.0f)
        scene.addChild(cam)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ParticleGlyphsExample().main()
        }
    }
}


