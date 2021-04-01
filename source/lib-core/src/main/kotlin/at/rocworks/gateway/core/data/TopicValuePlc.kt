package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValuePlc(
    val value: Any?,
    val time: Instant = Instant.now(),
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null, Instant.MAX)

    override fun dataTypeName() = "Unknown"

    override fun valueAsString() = value?.toString() ?: ""
    override fun statusAsString() = ""

    override fun valueAsDouble(): Double? = valueAsString().toDoubleOrNull()

    override fun serverTime() = time
    override fun sourceTime() = time

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValuePlc = json.mapTo(TopicValuePlc::class.java)
    }
}
