package scenery.tests.examples

import cleargl.GLMatrix
import cleargl.GLVector
import com.jogamp.opengl.GLAutoDrawable
import org.junit.Test
import scenery.*
import scenery.rendermodules.opengl.DeferredLightingRenderer

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class FontRenderingExample: SceneryDefaultApplication("FontRenderingExample") {
    override fun init(pDrawable: GLAutoDrawable) {
        deferredRenderer = DeferredLightingRenderer(pDrawable.gl.gL4, glWindow!!.width, glWindow!!.height)
        hub.add(SceneryElement.RENDERER, deferredRenderer!!)

        var lights = (0..5).map {
            PointLight()
        }

        lights.mapIndexed { i, light ->
            light.position = GLVector(2.0f * i, 2.0f * i, 2.0f * i)
            light.emissionColor = GLVector(1.0f, 0.0f, 1.0f)
            light.intensity = 0.2f * (i + 1);
            scene.addChild(light)
        }

        val hullbox = Box(GLVector(900.0f, 900.0f, 900.0f))
        hullbox.position = GLVector(0.1f, 0.1f, 0.1f)
        val hullboxM = Material()
        hullboxM.ambient = GLVector(1.0f, 1.0f, 1.0f)
        hullboxM.diffuse = GLVector(1.0f, 1.0f, 1.0f)
        hullboxM.specular = GLVector(1.0f, 1.0f, 1.0f)
        hullboxM.doubleSided = true
        hullbox.material = hullboxM

        scene.addChild(hullbox)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = GLVector(0.0f, 0.0f, -25.0f)
            view = GLMatrix().setCamera(cam.position, cam.position + cam.forward, cam.up)
            projection = GLMatrix()
                    .setPerspectiveProjectionMatrix(
                            70.0f / 180.0f * Math.PI.toFloat(),
                            1024f / 1024f, 0.1f, 1000.0f)
            active = true

            scene.addChild(this)
        }

        val board = FontBoard()
        board.text = "hello, world!"

        scene.addChild(board)

        deferredRenderer?.initializeScene(scene)

        repl.addAccessibleObject(scene)
        repl.addAccessibleObject(deferredRenderer!!)
        repl.addAccessibleObject(board)
        repl.start()
        repl.eval("var fontBoard = objectLocator(\"FontBoard\");")
        repl.eval("print(\"Font Example - try e.g. fontBoard.fontColor = new GLVector(1.0, 0.0, 0.0);\");")

        repl.showConsoleWindow()
    }

    @Test override fun main() {
        super.main()
    }
}
