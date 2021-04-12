package graphics.scenery.net

import org.joml.Matrix4f
import org.joml.Vector3f
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.*
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.objenesis.strategy.StdInstantiatorStrategy
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.io.ByteArrayInputStream
import java.io.StreamCorruptedException
import java.util.concurrent.ConcurrentHashMap


/**
 * Created by ulrik on 4/4/2017.
 */
class NodeSubscriber(override var hub: Hub?, val address: String = "tcp://localhost:6666", val context: ZContext = ZContext(4)) : Hubable {

    private val logger by LazyLogger()
    var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap()
    var subscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
    val kryo = NodePublisher.freeze()

    init {
        subscriber.connect(address)
        subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)
    }

    fun process() {
        while (true) {
            var start: Long
            var duration: Long = 0L
            try {
                start = System.nanoTime()
                val id = subscriber.recvStr().toInt()

                logger.trace("Received {} for deserializiation", id)
                duration = (System.nanoTime() - start)
                val payload = subscriber.recv()

                if (payload != null) {
                    logger.trace("payload is not null, node id={}, have={}", id, nodes.containsKey(id))
                    nodes[id]?.let { node ->
                        val bin = ByteArrayInputStream(payload)
                        val input = Input(bin)
                        val o = kryo.readClassAndObject(input) as? Node ?: return@let

                        //logger.info("Got pos=${o.position}, rotation=${o.rotation}, scale=${o.scale} for ${node.name}")
                        node.position = o.position
                        node.rotation = o.rotation
                        node.scale = o.scale
                        node.visible = o.visible

                        if (o is Volume && node is Volume && node.initialized) {
                            TODO("Reimplement changes for synchronising volumes")
                        }

                        if(o is PointLight && node is PointLight) {
                            node.emissionColor = o.emissionColor
                            node.lightRadius = o.lightRadius
                        }

                        if(o is BoundingGrid && node is BoundingGrid) {
                            node.gridColor = o.gridColor
                            node.lineWidth = o.lineWidth
                            node.numLines = o.numLines
                            node.ticksOnly = o.ticksOnly
                        }

                        input.close()
                        bin.close()
                    }
                }
            } catch(e: StreamCorruptedException) {
                logger.warn("Corrupted stream")
            } catch(e: NullPointerException) {
                logger.warn("NPE while receiving payload: $e")
            }

            hub?.get<Statistics>(SceneryElement.Statistics)?.add("Deserialise", duration.toFloat())

        }
    }

    fun close() {
        context.destroySocket(subscriber)
        context.close()
    }
}
