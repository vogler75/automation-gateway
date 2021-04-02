package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.Instant

data class TopicValueDds (
    val value: JsonObject?,
    val time: Instant = Instant.now(),
    val state: Int = 0
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null, Instant.MAX, 0)

    override fun dataTypeName() = value?.javaClass?.simpleName ?: ""
    override fun isNull() = value==null

    override fun statusAsString() = state.toString()
    override fun valueAsString() = value?.encode() ?: ""
    override fun valueAsDouble(): Double? = null

    override fun serverTime() = time
    override fun sourceTime() = time

    override fun isStruct() = true

    override fun asFlatMap(): Map<String, Any> {
        val result = HashMap<String, Any>()
        val recursion = object {
            fun flattenObject(base: String, o: JsonObject) {
                o.forEach { kv ->
                    val key = "${base}${kv.key}"
                    when (kv.value) {
                        is JsonObject -> flattenObject("${key}_", kv.value as JsonObject)
                        is JsonArray -> flattenArray("${key}_", kv.value as JsonArray)
                        else -> result[key] = kv.value
                    }
                }
            }

            fun flattenArray(base: String, o: JsonArray) {
                o.forEachIndexed { i, v ->
                    val key = "${base}${i}"
                    when (v) {
                        is JsonObject -> flattenObject("${key}_", v as JsonObject)
                        is JsonArray -> flattenArray("${key}_", v as JsonArray)
                        else -> result[key] = v
                    }
                }
            }
        }

        if (value!=null)
            recursion.flattenObject("", value)
        return result
    }

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValueDds {
            return TopicValueDds(
                value = json.getJsonObject("value", null) as JsonObject,
                time = json.getInstant("time", Instant.MIN),
                state = json.getInteger("state", 0)
            )
        }
    }
}