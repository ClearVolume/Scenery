package graphics.scenery.controls

import cleargl.GLVector
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.readValue
import graphics.scenery.Camera
import graphics.scenery.Node
import graphics.scenery.backends.Display
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Numerics
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMsg
import org.zeromq.ZPoller
import java.io.Serializable
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PupilEyeTracker(val calibrationType: CalibrationType, val host: String = "localhost", val port: Int = 50020) {
    enum class CalibrationType { ScreenSpace, WorldSpace}

    private val logger by LazyLogger()

    private val zmqContext = ZContext(4)
    private val req = zmqContext.createSocket(ZMQ.REQ)
    private val objectMapper = ObjectMapper(MessagePackFactory())

    private val subscriptionPort: Int

    var isCalibrated = false
        private set
    private var calibrating = false

    var onGazeReceived: ((Gaze) -> Any)? = null

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Gaze(var confidence: Float = 0.0f,
                    var timestamp: Float = 0.0f,

                    var norm_pos: FloatArray = floatArrayOf(),
                    var gaze_point_3d: FloatArray? = floatArrayOf(),
                    var eye_centers_3d: HashMap<Int, FloatArray>? = hashMapOf(),
                    var gaze_normals_3d: HashMap<Int, FloatArray>? = hashMapOf()) {


        fun normalizedPosition() = GLVector(*this.norm_pos)
        fun gazePoint() = gaze_point_3d?.let { GLVector(*it) }

        fun leftEyeCenter() = eye_centers_3d?.let { GLVector(*it.getOrDefault(0, floatArrayOf(0.0f, 0.0f, 0.0f))) }
        fun rightEyeCenter() = eye_centers_3d?.let { GLVector(*it.getOrDefault(1, floatArrayOf(0.0f, 0.0f, 0.0f))) }

        fun leftGazeNormal() = gaze_normals_3d?.let { GLVector(*it.getOrDefault(0, floatArrayOf(0.0f, 0.0f, 0.0f))) }
        fun rightGazeNormal() = gaze_normals_3d?.let { GLVector(*it.getOrDefault(1, floatArrayOf(0.0f, 0.0f, 0.0f))) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Gaze

            if (confidence != other.confidence) return false
            if (timestamp != other.timestamp) return false
            if (!Arrays.equals(norm_pos, other.norm_pos)) return false
            if (!Arrays.equals(gaze_point_3d, other.gaze_point_3d)) return false
            if (eye_centers_3d != other.eye_centers_3d) return false
            if (gaze_normals_3d != other.gaze_normals_3d) return false

            return true
        }

        override fun hashCode(): Int {
            var result = confidence.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + Arrays.hashCode(norm_pos)
            result = 31 * result + Arrays.hashCode(gaze_point_3d)
            result = 31 * result + (eye_centers_3d?.hashCode() ?: 0)
            result = 31 * result + (gaze_normals_3d?.hashCode() ?: 0)
            return result
        }
    }


    private var currentPupilDatumLeft = HashMap<Any, Any>()
    private var currentPupilDatumRight = HashMap<Any, Any>()

    var currentGaze: Gaze? = null
        private set

    init {
        logger.info("Connecting to $host:$port ...")
        req.connect("tcp://$host:$port")
        req.send("SUB_PORT")

        subscriptionPort = req.recvStr().toInt()
        logger.info("Subscription port received as $subscriptionPort")

        val module = SimpleModule()
        module.addKeyDeserializer(Int::class.java, object : KeyDeserializer() {
            override fun deserializeKey(key: String?, ctxt: DeserializationContext?): Any {
                return Integer.valueOf(key)
            }
        })
        objectMapper.registerModule(module)
    }

    protected fun getPupilTimestamp(): Float {
        req.send("t")
        return req.recvStr().toFloat()
    }

    protected fun notify(dict: HashMap<String, Any>): String {
        req.sendMore("notify.${dict["subject"]}")
        req.send(objectMapper.writeValueAsBytes(dict))

        val answer = req.recvStr()
        logger.debug(answer)
        return answer
    }

    private val subscriberSockets = HashMap<String, Job>()
    private fun subscribe(topic: String) {
        if(!subscriberSockets.containsKey(topic)) {

            val job = launch {
                val socket = zmqContext.createSocket(ZMQ.SUB)
                val poller = ZPoller(zmqContext)
                poller.register(socket, ZMQ.Poller.POLLIN)

                try {
                    socket.connect("tcp://$host:$subscriptionPort")
                    socket.subscribe(topic)
                    logger.debug("Subscribed to topic $topic")

                    while (isActive) {
                        poller.poll(10)

                        if(poller.isReadable(socket)) {
                            val msg = ZMsg.recvMsg(socket)
                            val msgType = msg.popString()

                            when(msgType) {
                                "notify.calibration.successful" -> {
                                    logger.info("Calibration successful.")
                                    calibrating = false
                                    isCalibrated = true
                                }

                                "notify.calibration.failed" -> {
                                    logger.error("Calibration failed.")
                                    calibrating = false
                                }

                                "pupil.0", "pupil.1" -> {
                                    val bytes = msg.pop().data
                                    val dict = objectMapper.readValue<HashMap<Any, Any>>(bytes)
                                    val confidence = (dict["confidence"] as Double).toFloat()
                                    val eyeId = dict["id"] as Int

                                    if(confidence > 0.8) {
                                        when(eyeId) {
                                            0 -> currentPupilDatumLeft = dict
                                            1 -> currentPupilDatumRight = dict
                                        }
                                    }
                                }

                                "gaze" -> {
                                    val bytes = msg.pop().data
                                    val g = objectMapper.readValue(bytes, Gaze::class.java)

                                    if(g.confidence > 0.6f) {
                                        currentGaze = g
                                        onGazeReceived?.invoke(g)
                                    }
                                }
                            }

                            msg.destroy()
                        }
                    }
                } finally {
                    logger.debug("Closing topic socket for $topic")
                    poller.unregister(socket)
                    poller.close()
                    socket.close()
                }
            }

            subscriberSockets.put(topic, job)
        }
    }

    private fun unsubscribe(topic: String) = runBlocking {
        if(subscriberSockets.containsKey(topic)) {
            logger.debug("Cancelling subscription of $topic")
            subscriberSockets.get(topic)?.cancel()
            subscriberSockets.get(topic)?.join()

            subscriberSockets.remove(topic)
        }
    }

    fun calibrate(cam: Camera, hmd: Display, generateReferenceData: Boolean = false, calibrationTarget: Node? = null): Boolean {
        subscribe("notify.calibration.successful")
        subscribe("notify.calibration.failed")
        subscribe("pupil.")

        notify(hashMapOf(
            "subject" to "eye_process.should_start.0",
            "eye_id" to 0,
            "args" to emptyMap<String, Any>()
        ))

        notify(hashMapOf(
            "subject" to "eye_process.should_start.1",
            "eye_id" to 1,
            "args" to emptyMap<String, Any>()
        ))

        Thread.sleep(2000)


        when(calibrationType) {
            CalibrationType.ScreenSpace -> {
                notify(hashMapOf(
                    "subject" to "start_plugin",
                    "name" to "HMD_Calibration",
                    "args" to emptyMap<String, Any>()
                ))

                notify(hashMapOf(
                    "subject" to "calibration.should_start",
                    "hmd_video_frame_size" to listOf(hmd.getRenderTargetSize().x().toInt(), hmd.getRenderTargetSize().y().toInt()),
                    "outlier_threshold" to 35
                ))
            }

            CalibrationType.WorldSpace -> {
                notify(hashMapOf(
                    "subject" to "start_plugin",
                    "name" to "HMD_Calibration_3D",
                    "args" to emptyMap<String, Any>()
                ))

                val shiftLeftEye = floatArrayOf(
                    hmd.getHeadToEyeTransform(0)[0, 3],
                    hmd.getHeadToEyeTransform(0)[1, 3],
                    hmd.getHeadToEyeTransform(0)[2, 3])

                val shiftRightEye = floatArrayOf(
                    hmd.getHeadToEyeTransform(1)[0, 3],
                    hmd.getHeadToEyeTransform(1)[1, 3],
                    hmd.getHeadToEyeTransform(1)[2, 3])

                notify(hashMapOf(
                    "subject" to "calibration.should_start",
                    "hmd_video_frame_size" to listOf(hmd.getRenderTargetSize().x().toInt(), hmd.getRenderTargetSize().y().toInt()),
                    "outlier_threshold" to 35,
                    "translation_eye0" to shiftLeftEye,
                    "translation_eye1" to shiftRightEye
                ))
            }
        }

        calibrating = true
        val referenceData = arrayListOf<HashMap<String, Serializable>>()

        if(generateReferenceData) {
            val numReferencePoints = 15
            val samplesPerPoint = 120

            val (posKeyName, posGenerator: ((Camera, Int, Int) -> Pair<GLVector, GLVector>)) = when(calibrationType) {
                CalibrationType.ScreenSpace -> "norm_pos" to PupilEyeTracker.EquidistributedScreenSpaceCalibrationPointGenerator
                CalibrationType.WorldSpace -> "mm_pos" to PupilEyeTracker.DefaultWorldSpaceCalibrationPointGenerator
            }

            val positionList = (0 until numReferencePoints).map {
                posGenerator.invoke(cam, it, numReferencePoints)
            }

            positionList.map { normalizedScreenPos ->
                logger.info("Subject looking at ${normalizedScreenPos.first}/${normalizedScreenPos.second}")

                calibrationTarget?.position = normalizedScreenPos.second

                (0 until samplesPerPoint).forEach {
                    val timestamp = getPupilTimestamp()

                    val datum0 = hashMapOf<String, Serializable>(
                        posKeyName to normalizedScreenPos.first.toFloatArray(),
                        "timestamp" to timestamp,
                        "id" to 0
                    )

                    val datum1 = hashMapOf<String, Serializable>(
                        posKeyName to normalizedScreenPos.first.toFloatArray(),
                        "timestamp" to timestamp,
                        "id" to 1
                    )

                    referenceData.add(datum0)
                    referenceData.add(datum1)

                    Thread.sleep(16)
                }
            }

            logger.info("Generated ${referenceData.size} calibration points ($samplesPerPoint samples x $numReferencePoints points)")
        }

        notify(hashMapOf<String, Any>(
            "subject" to "calibration.add_ref_data",
            "ref_data" to referenceData.toArray()
        ))

        notify(hashMapOf(
            "subject" to "calibration.should_stop"
        ))

        while(calibrating) {
            Thread.sleep(100)
        }

        unsubscribe("notify.calibration.successful")
        unsubscribe("notify.calibration.failed")

        if(isCalibrated) {
            logger.info("Calibration succeeded, subscribing to gaze data")
            subscribe("gaze")

            return true
        }

        return false
    }

    companion object {
        val SpiralScreenSpaceCalibrationPointGenerator = { cam: Camera, index: Int, referencePointCount: Int ->
            val spiralOrigin = 0.5f
            val spiralRadius = 0.3f

            val v = GLVector(
                spiralOrigin + spiralRadius * (index.toFloat()/referencePointCount) * cos(2 * PI.toFloat() * index.toFloat()/referencePointCount),
                spiralOrigin + spiralRadius * (index.toFloat()/referencePointCount) * sin(2 * PI.toFloat() * index.toFloat()/referencePointCount),
                cam.nearPlaneDistance + 1.0f)
            v to cam.viewportToWorld(GLVector(v.x() * 2.0f - 1.0f, v.y() * 2.0f - 1.0f))
        }

        val EquidistributedScreenSpaceCalibrationPointGenerator = { cam: Camera, index: Int, _: Int ->
            val points = arrayOf(
                GLVector(0.0f, 0.5f),
                GLVector(0.0f, 0.5f),

                GLVector(-0.5f, 0.5f),
                GLVector(-0.5f, -0.5f),

                GLVector(0.5f, 0.5f),
                GLVector(0.5f, -0.5f),

                GLVector(-0.25f, 0.0f),
                GLVector(0.25f, 0.0f)
            )

            val v = GLVector(
                0.5f + 0.3f * points[index % (points.size - 1)].x(),
                0.5f + 0.3f * points[index % (points.size - 1)].y(),
                cam.nearPlaneDistance + 0.5f)
            v to cam.viewportToWorld(GLVector(v.x() * 2.0f - 1.0f, v.y() * 2.0f - 1.0f))
        }

        val DefaultWorldSpaceCalibrationPointGenerator = { _: Camera, _: Int, _: Int ->
            val v = GLVector(Numerics.randomFromRange(-4.0f, 4.0f),
                Numerics.randomFromRange(-4.0f, 4.0f),
                Numerics.randomFromRange(0.0f, -3.0f))
            v to v
        }
    }
}
