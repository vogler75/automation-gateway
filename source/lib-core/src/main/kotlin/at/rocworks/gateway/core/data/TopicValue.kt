package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValue (
    val value: Any?,
    val statusCode: Long = 0,
    val sourceTime: Instant = Instant.now(),
    val serverTime: Instant = Instant.now(),
    val sourcePicoseconds: Int = 0,
    val serverPicoseconds: Int = 0,
    val dataType: Class<Any>? = value?.javaClass
) {
    // default constructor needed for json to object mapping
    constructor() : this(null, 0, Instant.MIN, Instant.MIN, 0, 0)

    fun hasValue() = value!=null

    fun valueAsObject() = value
    fun statusAsString() = statusCode.toString()
    fun valueAsString() = value?.toString() ?: ""
    fun valueAsDouble(): Double? = valueAsString().toDoubleOrNull()

    fun serverTime() = serverTime
    fun sourceTime() = sourceTime
    fun sourceTimeMs(): Long = sourceTime().toEpochMilli()
    fun serverTimeMs(): Long = serverTime().toEpochMilli()
    fun serverTimeAsISO(): String = serverTime().toString()
    fun sourceTimeAsISO(): String = sourceTime().toString()

    fun dataTypeName(): String = valueAsObject()?.javaClass?.simpleName ?: ""

    fun encodeToJson(): JsonObject = JsonObject.mapFrom(this)

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValue = json.mapTo(TopicValue::class.java)
    }
}
