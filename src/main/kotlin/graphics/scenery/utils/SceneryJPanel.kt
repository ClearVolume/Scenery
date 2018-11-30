package graphics.scenery.utils

import com.sun.jna.Pointer
import org.lwjgl.system.MemoryUtil
import java.awt.Graphics
import java.awt.Point
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.*
import java.nio.ByteBuffer
import java.util.*
import javax.swing.JPanel
import java.awt.image.DataBuffer



class SceneryJPanel : JPanel(), SceneryPanel {
    private val logger by LazyLogger()

    override var panelWidth: Int
        get() = super.getWidth()
        set(value) {}
    override var panelHeight: Int
        get() = super.getHeight()
        set(value) {}

    protected var images =  Array<BufferedImage?>(2, { null })
    protected var currentReadImage = 0
    protected var currentWriteImage = 0

    protected var renderingReady = false

    override var refreshRate = 60
        set(value) {
            field = value
            rescheduleRepaintTimer(field)
        }
    var refreshTimer = Timer()

    init {
        rescheduleRepaintTimer(refreshRate)
    }

    protected fun rescheduleRepaintTimer(fps: Int) {
        logger.info("Resetting timer repeat rate to ${1.0f/fps * 1000}")
        refreshTimer.cancel()

        refreshTimer = Timer()
        refreshTimer.scheduleAtFixedRate(object: TimerTask() {
            override fun run() {
                this@SceneryJPanel.repaint()
            }
        }, 0, (1.0f/fps * 1000).toLong() )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        if(!renderingReady) {
            return
        }

        var image = images[currentReadImage]

        if(image == null) {
            return
        }

        val start = System.nanoTime()
//        g.drawImage(image, 0, 0, image.width, image.height, null)
        g.drawImage(image, 0, 0, null)
        val duration = (System.nanoTime() - start)/1_000_000
        logger.info("Draw duration: $duration ms")

        currentReadImage = (currentReadImage+1) % images.size
    }

    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        super.reshape(x, y, w, h)

        for(i in 0 until images.size) {
            images[i] = null
        }
    }

    override fun update(buffer: ByteBuffer, id: Int) {
        var image = images[currentWriteImage]

        if(image == null) {
            logger.info("Recreating image $currentWriteImage")
            // BGRA color model
            val cs = ColorSpace.getInstance(ColorSpace.CS_sRGB)
            val nBits = intArrayOf(8, 8, 8, 8)
            val bOffs = intArrayOf(2, 1, 0, 3)
            val colorModel = ComponentColorModel(cs, nBits, true, false,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE)
            val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE,
                panelWidth, panelHeight,
                panelWidth * 4, 4,
                bOffs, null)

            image = BufferedImage(colorModel, raster, false, null)
            images[currentWriteImage] = image
        }


        val address = MemoryUtil.memAddress(buffer)
        val p = Pointer(address)
        val arr = (image.raster.dataBuffer as DataBufferByte).data
        p.read(0, arr, 0, buffer.remaining())

        currentWriteImage = (currentWriteImage+1) % images.size
        renderingReady = true

        if(refreshRate == 0) {
            repaint()
        }
    }

    override var displayedFrames: Long = 0L

    override var imageScaleY: Float = 1.0f

    override fun setPreferredDimensions(w: Int, h: Int) {
        logger.info("Preferred dimensions=$w,$h")
    }

}
