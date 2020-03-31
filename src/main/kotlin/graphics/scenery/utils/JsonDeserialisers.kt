package graphics.scenery.utils

import org.joml.Vector3f
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import org.joml.Vector2f
import org.joml.Vector4f

/**
 * A collection of deserialisers to use with Jackson.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class JsonDeserialisers {
    /**
     * Deserialiser for pairs of floats, separated by commas.
     */
    class FloatPairDeserializer : JsonDeserializer<Pair<Float, Float>>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): Pair<Float, Float> {
            val pair = p.text.split(",").map { it.trim().trimStart().toFloat() }

            return Pair(pair[0], pair[1])
        }
    }

    /**
     * Deserialiser for vectors of various lengths, separated by commas.
     */
    class VectorDeserializer : JsonDeserializer<Any>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): Any {
            val text = if(p.currentToken == JsonToken.START_ARRAY) {
                var token = p.nextToken()
                var result = ""
                while(token != JsonToken.END_ARRAY) {
                    result += p.text
                    token = p.nextToken()
                    if(token != JsonToken.END_ARRAY) {
                        result += ", "
                    }
                }
                result
            } else {
                p.text
            }

            val floats = text.split(",").map { it.trim().trimStart().toFloat() }.toFloatArray()

            return when(floats.size) {
                2 -> Vector2f(floats[0], floats[1])
                3 -> Vector3f(floats[0], floats[1], floats[2])
                4 -> Vector4f(floats[0], floats[1], floats[2], floats[3])
                else -> throw IllegalStateException("Don't know how to deserialise a vector of dimension ${floats.size}")
            }
        }
    }

    /**
     * Eye description deserialiser, turns "LeftEye" to 0, "RightEye" to 1
     */
    class VREyeDeserializer : JsonDeserializer<Int>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): Int {
            return when (p.text.trim().trimEnd()) {
                "LeftEye" -> 0
                "RightEye" -> 1
                else -> -1
            }
        }
    }
}
