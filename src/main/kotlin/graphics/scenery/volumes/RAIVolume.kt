package graphics.scenery.volumes

import bdv.tools.brightness.ConverterSetup
import bdv.viewer.SourceAndConverter
import bvv.core.VolumeViewerOptions
import graphics.scenery.Hub
import graphics.scenery.OrientedBoundingBox
import graphics.scenery.Origin
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.FloatType
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3i
import kotlin.math.floor
import kotlin.math.roundToInt

class RAIVolume(@Transient val ds: VolumeDataSource, options: VolumeViewerOptions, hub: Hub): Volume(
    ds,
    options,
    hub
) {
    private constructor() : this(VolumeDataSource.RAISource(UnsignedByteType(), emptyList(), ArrayList<ConverterSetup>(), 0, null), VolumeViewerOptions.options(), Hub()) {

    }

    init {
        name = "Volume (RAI source)"
        if((ds as? VolumeDataSource.RAISource<*>)?.cacheControl != null) {
            logger.debug("Adding cache control")
            cacheControls.addCacheControl(ds.cacheControl)
        }

        timepointCount = when(ds) {
            is VolumeDataSource.RAISource<*> -> ds.numTimepoints
            is VolumeDataSource.SpimDataMinimalSource -> ds.numTimepoints
            else -> throw UnsupportedOperationException("Can't determine timepoint count of ${ds.javaClass}")
        }

        boundingBox = generateBoundingBox()
    }

    override fun generateBoundingBox(): OrientedBoundingBox {
        return OrientedBoundingBox(this,
            Vector3f(-0.0f, -0.0f, -0.0f),
            Vector3f(getDimensions()))
    }

    override fun localScale(): Vector3f {
        val size = getDimensions()
        logger.info("Sizes are $size")

        return Vector3f(
            size.x() * pixelToWorldRatio / 10.0f,
                -1.0f * size.y() * pixelToWorldRatio / 10.0f,
            size.z() * pixelToWorldRatio / 10.0f
        )
    }

    private fun firstSource(): SourceAndConverter<out Any>? {
        return when(ds) {
            is VolumeDataSource.RAISource<*> -> ds.sources.firstOrNull()
            is VolumeDataSource.SpimDataMinimalSource -> ds.sources.firstOrNull()
            else -> throw UnsupportedOperationException("Can't handle data source of type ${ds.javaClass}")
        }
    }

    override fun getDimensions(): Vector3i {
        val source = firstSource()

        return if(source != null) {
            val s = source.spimSource.getSource(0, 0)
            val min = Vector3i(s.min(0).toInt(), s.min(1).toInt(), s.min(2).toInt())
            val max = Vector3i(s.max(0).toInt(), s.max(1).toInt(), s.max(2).toInt())
            max.sub(min)
        } else {
            Vector3i(1, 1, 1)
        }
    }

    override fun createSpatial(): VolumeSpatial {
        return RAIVolumeSpatial(this)
    }

    class RAIVolumeSpatial(volume: RAIVolume): VolumeSpatial(volume) {
        override fun composeModel() {
            @Suppress("SENSELESS_COMPARISON")
            if (position != null && rotation != null && scale != null ) {
                val volume = (node as? RAIVolume) ?: return
                val source = volume.firstSource()

                val shift = if (source != null) {
                    val s = source.spimSource.getSource(0, 0)
                    val min = Vector3f(s.min(0).toFloat(), s.min(1).toFloat(), s.min(2).toFloat())
                    val max = Vector3f(s.max(0).toFloat(), s.max(1).toFloat(), s.max(2).toFloat())
                    (max - min) * (-0.5f)
                } else {
                    Vector3f(0.0f, 0.0f, 0.0f)
                }

                model.translation(position)
                model.mul(Matrix4f().set(this.rotation))
                model.scale(scale)
                model.scale(volume.localScale())
                if (volume.origin == Origin.Center) {
                    model.translate(shift)
                }
            }
        }
    }


    override fun sampleRay(rayStart: Vector3f, rayEnd: Vector3f): Pair<List<Float?>, Vector3f>? {
        val d = getDimensions()
        val dimensions = Vector3f(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())

        val start = rayStart
        val end = rayEnd

        if (start.x !in 0.0f..1.0f || start.y !in 0.0f..1.0f || start.z !in 0.0f..1.0f) {
            logger.debug("Invalid UV coords for ray start: {} -- will clamp values to [0.0, 1.0].", start)
        }

        if (end.x !in 0.0f..1.0f || end.y !in 0.0f..1.0f || end.z !in 0.0f..1.0f) {
            logger.debug("Invalid UV coords for ray end: {} -- will clamp values to [0.0, 1.0].", end)
        }

        val startClamped = Vector3f(
            start.x.coerceIn(0.0f, 1.0f),
            start.y.coerceIn(0.0f, 1.0f),
            start.z.coerceIn(0.0f, 1.0f)
        )

        val endClamped = Vector3f(
            end.x.coerceIn(0.0f, 1.0f),
            end.y.coerceIn(0.0f, 1.0f),
            end.z.coerceIn(0.0f, 1.0f)
        )

        val direction = (endClamped - startClamped)
        val maxSteps = (Vector3f(direction).mul(dimensions).length() * 2.0f).roundToInt()
        val delta = direction * (1.0f / maxSteps.toFloat())

        logger.info("Sampling from $startClamped to ${startClamped + maxSteps.toFloat() * delta}")
        direction.normalize()

        return (0 until maxSteps).map {
            sample(startClamped + (delta * it.toFloat()))
        } to delta

    }

    private fun NumericType<*>.maxValue(): Float = when(this) {
        is UnsignedByteType -> 255.0f
        is UnsignedShortType -> 65536.0f
        is FloatType -> 1.0f
        else -> 1.0f
    }

    /**
    This sample function is not finished yet, transferRangeMax function should be improved to fit different data type
     **/
    override fun sample(uv: Vector3f, interpolate: Boolean): Float? {
         val d = getDimensions()

        val absoluteCoords = Vector3f(uv.x() * d.x(), uv.y() * d.y(), uv.z() * d.z())
        val absoluteCoordsD = Vector3i(floor(absoluteCoords.x()).toInt(), floor(absoluteCoords.y()).toInt(), floor(absoluteCoords.z()).toInt())

        val r = when(ds) {
            is VolumeDataSource.RAISource<*> -> ds.sources.get(0).spimSource.getSource(
                currentTimepoint,
                0
            ).randomAccess()
            is VolumeDataSource.SpimDataMinimalSource -> ds.sources.get(0).spimSource.getSource(
                currentTimepoint,
                0
            ).randomAccess()
            else -> throw UnsupportedOperationException("Can't handle data source of type ${ds.javaClass}")
        }
        r.setPosition(absoluteCoordsD.x(),0)
        r.setPosition(absoluteCoordsD.y(),1)
        r.setPosition(absoluteCoordsD.z(),2)

        val value = r.get()

        val finalresult = when(value) {
            is UnsignedShortType -> value.realFloat
            else -> throw java.lang.IllegalStateException("Can't determine density for ${value?.javaClass} data")
        }

        val transferRangeMax = when(ds)
        {
            is VolumeDataSource.RAISource<*> -> ds.converterSetups.firstOrNull()?.displayRangeMax?.toFloat()?:ds.type.maxValue()
            is VolumeDataSource.SpimDataMinimalSource -> ds.converterSetups.firstOrNull()?.displayRangeMax?.toFloat()?:255.0f
            else -> throw UnsupportedOperationException("Can't handle data source of type ${ds.javaClass}")
        }

        val tf = transferFunction.evaluate(finalresult/transferRangeMax)
        logger.info("Sampled at $uv: $finalresult/$transferRangeMax/$tf")
        return tf
    }
}
