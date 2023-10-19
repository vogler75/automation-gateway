package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject

data class DataPoint(
    val topic: Topic,
    val value: TopicValue
) {
    fun encodeToJson(): JsonObject = JsonObject()
        .put("topic", topic.encodeToJson())
        .put("value", value.encodeToJson())

    companion object {
        fun fromJsonObject(json: JsonObject): DataPoint = DataPoint(
            Topic.decodeFromJson(json.getJsonObject("topic")),
            TopicValue.decodeFromJson(json.getJsonObject("value"))
        )
    }
}
