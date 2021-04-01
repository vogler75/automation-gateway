package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValueDds (
    val value: JsonObject?,
    val time: Instant = Instant.now(),
    val state: Int
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null, Instant.MAX, 0)

    override fun dataTypeName() = value?.javaClass?.simpleName ?: ""

    override fun statusAsString() = state.toString()

    override fun valueAsString() = value?.encode() ?: ""

    override fun valueAsDouble(): Double? = null

    override fun serverTime() = time
    override fun sourceTime() = time

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValueDds = json.mapTo(TopicValueDds::class.java)
    }
}