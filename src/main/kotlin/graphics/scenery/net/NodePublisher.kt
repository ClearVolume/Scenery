package graphics.scenery.net

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import de.javakaffee.kryoserializers.UUIDSerializer
import graphics.scenery.*
import graphics.scenery.serialization.*
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.RAIVolume
import graphics.scenery.volumes.Volume
import graphics.scenery.volumes.VolumeManager
import net.imglib2.img.basictypeaccess.array.ByteArray
import org.joml.Vector3f
import org.objenesis.strategy.StdInstantiatorStrategy
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMQException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.concurrent.thread

/**
 * Created by ulrik on 4/4/2017.
 */
class NodePublisher(override var hub: Hub?, val address: String = "tcp://127.0.0.1:6666", val context: ZContext = ZContext(4)): Hubable {
    private val logger by LazyLogger()



    //private var publishedAt = ConcurrentHashMap<Int, Long>()
    //var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap()
    var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap() //TODO delete

    private val eventQueueTimeout = 500L
    private var publisher: ZMQ.Socket = context.createSocket(ZMQ.PUB)
    val kryo = freeze()
    var port: Int = try {
        publisher.bind(address)
        address.substringAfterLast(":").toInt()
    } catch (e: ZMQException) {
        logger.warn("Binding failed, trying random port: $e")
        publisher.bindToRandomPort(address.substringBeforeLast(":"))
    }
    private val publishedObjects = ConcurrentHashMap<Int, NetworkObject<*>>()
    private val eventQueue = LinkedBlockingQueue<NetworkEvent>()
    private var index = 1

    private var running = false

    private fun generateNetworkID() = index++

    fun register(scene: Scene){
        val sceneNo = NetworkObject(generateNetworkID(),scene, mutableListOf())
        publishedObjects[sceneNo.networkID] = sceneNo
        eventQueue.add(NetworkEvent.NewObject(sceneNo))

        //scene.onChildrenAdded["networkPublish"] = {_, child -> registerNode(child)}
        //scene.onChildrenRemoved["networkPublish"] = {_, child -> removeNode(child)}

        // abusing the discover function for a tree walk
        scene.discover(scene,{registerNode(it); false})

        //TODO sendout new stuff
        //TODO add to networkObjects
    }

    fun registerNode(node:Node){
        if (!node.wantsSync()){
            return
        }
        if (node.parent == null){
            throw IllegalArgumentException("Node not part of scene graph and cant be synchronized alone")
        }
        val parentId = node.parent?.networkID
        if (parentId == null || publishedObjects[parentId] == null){
            throw IllegalArgumentException("Node Parent not registered with publisher.")
        }

        if (publishedObjects[node.networkID] == null) {
            val netObject = NetworkObject(generateNetworkID(), node, mutableListOf(parentId))
            eventQueue.add(NetworkEvent.NewObject(netObject))
            publishedObjects[netObject.networkID] = netObject
        }

        node.getSubcomponents().forEach { subComponent ->
            val subNetObj = publishedObjects[subComponent.networkID]
            if (subNetObj != null) {
                subNetObj.parents.add(node.networkID)
                eventQueue.add(NetworkEvent.NewRelation(node.networkID, subComponent.networkID))
            } else {
                val new = NetworkObject(generateNetworkID(), subComponent, mutableListOf(node.networkID))
                publishedObjects[new.networkID] = new
                eventQueue.add(NetworkEvent.NewObject(new))
            }
        }
    }

    fun removeNode(node: Node){
        //TODO
    }

    /**
     * Should be called in the update phase of the life
     */
    fun scanForChanges(){


        for (it in publishedObjects.values) {
            if (it.obj.lastChange() >= it.publishedAt) {
                it.publishedAt = System.nanoTime()
                eventQueue.add(NetworkEvent.Update(it))
            }
        }

    }

    fun startPublishing(){
        running = true
        thread {
            while (running){
                val event = eventQueue.poll(eventQueueTimeout, TimeUnit.MILLISECONDS) ?: continue
                if (!running) break // in case of shutdown while polling
                publishEvent(event)
            }
        }
        //TODO discover node changes in update
    }

    fun debugPublish(send: (NetworkEvent) -> Unit){
        while(eventQueue.isNotEmpty()){
            send(eventQueue.poll())
        }
    }

    fun stopPublishing(waitForFinishOfPublishing: Boolean) {
        running = false
        if(waitForFinishOfPublishing){
            Thread.sleep(eventQueueTimeout*2)
        }
    }

    private fun publishEvent(event: NetworkEvent){
        var payloadSize = 0L
        val start = System.nanoTime()
        try {
            val bos = ByteArrayOutputStream()
            val output = Output(bos)
            kryo.writeClassAndObject(output, event)
            output.flush()

            val payload = bos.toByteArray()
            publisher.send(payload)
            Thread.sleep(1)
            payloadSize = payload.size.toLong()

            output.close()
            bos.close()
        } catch(e: IOException) {
            logger.warn("Error in publishing: ${event.javaClass.name}", e)
        } catch(e: AssertionError) {
            logger.warn("Error in publishing: ${event.javaClass.name}", e)
        } catch (e: Throwable){
            print(e)
        }

        val duration = (System.nanoTime() - start).toFloat()
        (hub?.get(SceneryElement.Statistics) as? Statistics)?.add("Serialise.duration", duration)
        (hub?.get(SceneryElement.Statistics) as? Statistics)?.add("Serialise.payloadSize", payloadSize, isTime = false)
    }

    fun close(waitForFinishOfPublishing: Boolean = false) {
        stopPublishing(waitForFinishOfPublishing)
        context.destroySocket(publisher)
        context.close()
    }

    companion object {
        fun freeze(): Kryo {
            val kryo = Kryo()
            kryo.instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())
            kryo.isRegistrationRequired = false
            kryo.references = true
            kryo.setCopyReferences(true)
            kryo.register(UUID::class.java, UUIDSerializer())
            kryo.register(OrientedBoundingBox::class.java, OrientedBoundingBoxSerializer())
            kryo.register(Triple::class.java, TripleSerializer())
            kryo.register(ByteBuffer::class.java, ByteBufferSerializer())

            // A little trick here, because DirectByteBuffer is package-private
            val tmp = ByteBuffer.allocateDirect(1)
            kryo.register(tmp.javaClass, ByteBufferSerializer())
            kryo.register(ByteArray::class.java, Imglib2ByteArraySerializer())
            kryo.register(ShaderMaterial::class.java, ShaderMaterialSerializer())
            kryo.register(java.util.zip.Inflater::class.java, IgnoreSerializer<Inflater>())
            kryo.register(VolumeManager::class.java, IgnoreSerializer<VolumeManager>())
            kryo.register(Vector3f::class.java, Vector3fSerializer())

            kryo.register(Volume::class.java, VolumeSerializer())
            kryo.register(RAIVolume::class.java, VolumeSerializer())
            kryo.register(BufferedVolume::class.java, VolumeSerializer())

            return kryo
        }

    }

}
