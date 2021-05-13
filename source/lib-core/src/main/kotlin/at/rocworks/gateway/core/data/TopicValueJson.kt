package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValueJson (
    val value: JsonObject?,
    val time: Instant = Instant.now(),
    val state: Int = 0
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null, Instant.MIN, 0)

    override fun dataTypeName() = value?.javaClass?.simpleName ?: ""
    override fun hasValue() = value!=null

    override fun valueAsObject() = value
    override fun statusAsString() = state.toString()
    override fun valueAsString() = value?.encode() ?: ""
    override fun valueAsDouble(): Double? = null

    override fun serverTime() = time
    override fun sourceTime() = time

    override fun hasStruct() = true

    override fun asFlatMap(): Map<String, Any> {
        val result = HashMap<String, Any>()
        val recursion = object {
            fun flatten(key: String, value: Any) {
                when (value) {
                    is JsonObject -> flattenObject("${key}_", value)
                    is JsonArray -> flattenArray("${key}_", value)
                    else -> result[key] = value
                }
            }
            fun flattenObject(key: String, o: JsonObject) {
                o.forEach { flatten("${key}${it.key}", it.value) }
            }
            fun flattenArray(key: String, o: JsonArray) {
                o.forEachIndexed { i, v -> flatten("${key}${i}", v) }
            }
        }
        if (value!=null)
            recursion.flattenObject("", value)
        return result
    }

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValueJson {
            return TopicValueJson(
                value = json.getJsonObject("value", null) as JsonObject,
                time = json.getInstant("time", Instant.MIN),
                state = json.getInteger("state", 0)
            )
        }
    }
}