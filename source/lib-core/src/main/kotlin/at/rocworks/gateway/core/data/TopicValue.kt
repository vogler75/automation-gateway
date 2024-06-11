package at.rocworks.gateway.core.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValue (
    val value: Any?,
    val statusCode: String = "",
    val sourceTime: Instant = Instant.now(),
    val serverTime: Instant = Instant.now()
    //val sourcePicoseconds: Int = 0,
    //val serverPicoseconds: Int = 0
) {
    // default constructor needed for json to object mapping
    fun hasValue() = value!=null
    fun hasNoValue() = value==null

    fun valueAsObject() = value
    fun statusAsString() = statusCode
    fun valueAsString() = value?.toString() ?: ""
    fun valueAsDouble(): Double? = when (value) {
        is Number -> value.toDouble()
        else -> valueAsString().toDoubleOrNull()
    }
    fun stringValue(): String = if (value is String) value else ""

    fun isStatusGood() = statusCode == TopicStatus.GOOD

    fun serverTime() = serverTime
    fun sourceTime() = sourceTime
    fun sourceTimeMs(): Long = sourceTime.toEpochMilli()
    fun serverTimeMs(): Long = serverTime.toEpochMilli()
    fun serverTimeAsISO(): String = serverTime.toString()
    fun sourceTimeAsISO(): String = sourceTime.toString()

    fun dataTypeName(): String = valueAsObject()?.javaClass?.simpleName ?: ""

    override fun toString(): String = encodeToJson().toString()

    fun encodeToJson(): JsonObject {
        //JsonObject.mapFrom(this)
        return JsonObject()
            .put("value", value)
            .put("dataType", dataTypeName())
            .put("statusCode", statusCode)
            .put("sourceTime", sourceTimeAsISO())
            .put("serverTime", serverTimeAsISO())
            .put("sourceTimeMs", sourceTimeMs())
            .put("serverTimeMs", serverTimeMs())
            //.put("sourcePicoseconds", sourcePicoseconds)
            //.put("serverPicoseconds", serverPicoseconds)
    }

    companion object {
        fun decodeFromJson(json: JsonObject): TopicValue {
            //json.mapTo(TopicValue::class.java)
            fun getTime(name: String) : Instant {
                val ms = json.getLong("${name}Ms")
                return if (ms != null) Instant.ofEpochMilli(ms)
                else {
                    val iso = json.getString(name)
                    if (iso == null) Instant.now()
                    else Instant.parse(iso)
                }
            }

            return TopicValue(
                json.getValue("value", null),
                json.getString("statusCode", ""),
                getTime("sourceTime"),
                getTime("serverTime")
                //json.getInteger("sourcePicoseconds", 0),
                //json.getInteger("serverPicoseconds", 0)
            )
        }
    }
}
