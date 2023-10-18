package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject

data class DataPoint(
    val topic: Topic,
    val value: TopicValue
) {
    fun encodeToJson(): JsonObject = JsonObject.mapFrom(this)

    companion object {
        fun fromJsonObject(json: JsonObject): DataPoint = json.mapTo(DataPoint::class.java)
    }
}
