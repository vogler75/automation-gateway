package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValueGeneric (
    val value: Any?,
    val statusCode: Long = 0,
    val sourceTime: Instant = Instant.now(),
    val serverTime: Instant = Instant.now(),
    val sourcePicoseconds: Int = 0,
    val serverPicoseconds: Int = 0
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null,0, Instant.MIN, Instant.MIN, 0, 0)

    override fun hasValue() = value!=null

    override fun valueAsObject() = value
    override fun statusAsString() = statusCode.toString()
    override fun valueAsString() = value?.toString() ?: ""
    override fun serverTime() = serverTime
    override fun sourceTime() = sourceTime


    companion object {
        fun fromJsonObject(json: JsonObject): TopicValueGeneric = json.mapTo(TopicValueGeneric::class.java)
    }
}
