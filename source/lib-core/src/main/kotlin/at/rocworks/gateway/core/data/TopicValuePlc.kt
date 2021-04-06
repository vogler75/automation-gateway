package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValuePlc(
    val value: Any?,
    val time: Instant = Instant.now(),
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null, Instant.MAX)

    override fun dataTypeName() = value?.javaClass?.simpleName ?: ""

    override fun hasValue() = value!=null

    override fun valueAsObject() = value
    override fun statusAsString() = ""
    override fun valueAsString() = value?.toString() ?: ""

    override fun valueAsDouble(): Double? = when (value) {
        is Boolean -> if (value) 1.0 else 0.0
        else -> valueAsString().toDoubleOrNull()
    }

    override fun serverTime() = time
    override fun sourceTime() = time

    override fun hasStruct() = false

    override fun asFlatMap(): Map<String, Any> {
        return if (value!=null) mapOf(Pair("value", value)) else mapOf()
    }

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValuePlc = json.mapTo(TopicValuePlc::class.java)
    }
}
