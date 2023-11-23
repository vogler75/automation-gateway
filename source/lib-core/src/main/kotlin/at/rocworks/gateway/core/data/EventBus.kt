package at.rocworks.gateway.core.data

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.logging.Logger

class EventBus(val logger: Logger) {
    fun requestPublishTopicBuffer(
        vertx: Vertx,
        topic: Topic,
        data: Buffer
    ) {
        val target = topic.copy(format=Topic.Format.Value)
        val address = "${target.systemType.name}/${target.systemName}/Publish"

        val request = JsonObject()
        request.put("Topic", target.encodeToJson())
        request.put("Buffer", data)

        vertx.eventBus().request<JsonObject>(address, request) {
            logger.finest { "Publish response [${it.succeeded()}] [${it.result()?.body()}]" }
        }
    }

    fun requestPublishTopicValue(
        vertx: Vertx,
        topic: Topic,
        value: TopicValue
    ) {
        requestPublishDataPoint(vertx, DataPoint(topic, value))
    }

    fun requestPublishDataPoint(
        vertx: Vertx,
        data: DataPoint
    ) {
        val address = "${data.topic.systemType.name}/${data.topic.systemName}/Publish"

        val request = JsonObject()
        request.put("Topic", data.topic.encodeToJson())
        request.put("Value", data.value.encodeToJson())

        vertx.eventBus().request<JsonObject>(address, request) {
            logger.finest { "Publish response [${it.succeeded()}] [${it.result()?.body()}]" }
        }
    }

    fun requestSubscribeTopic(
        vertx: Vertx,
        clientId: String,
        topic: Topic,
        onComplete: (Boolean, MessageConsumer<DataPoint>)->Unit,
        onMessage: (Topic, Message<DataPoint>)->Unit
    )
    {
        val consumer = vertx.eventBus().consumer<DataPoint>(topic.topicName) {
            onMessage(topic, it)
        }
        val address = "${topic.systemType}/${topic.systemName}/Subscribe"
        val request = JsonObject()
            .put("ClientId", clientId)
            .put("Topic", topic.encodeToJson())
        vertx.eventBus().request<JsonObject>(address, request) {
            logger.finest { "Subscribe response [${it.succeeded()}] [${it.result()?.body()}]" }
            if (it.succeeded() && it.result().body().getBoolean("Ok")) {
                onComplete(true, consumer)
            } else {
                consumer.unregister()
                onComplete(false, consumer)
            }
        }
    }

    fun requestUnsubscribeTopic(
        vertx: Vertx,
        clientId: String,
        topic: Topic,
        onComplete: (Boolean)->Unit
    ) {
        val request = JsonObject().put("ClientId", clientId)
        request.put("Topic", topic.encodeToJson())
        val address = "${topic.systemType}/${topic.systemName}"
        logger.fine("Unsubscribe from [${address}] [$topic]")
        vertx.eventBus().request<JsonObject>("${address}/Unsubscribe", request) {
            logger.finest { "Unsubscribe response [${it.succeeded()}] [${it.result()?.body()}]" }
            val ok = it.succeeded() && it.result().body().getBoolean("Ok")
            onComplete(ok)
        }
    }

    fun requestUnsubscribeTopics(
        vertx: Vertx,
        clientId: String,
        topics: List<Topic>,
        onComplete: (Boolean, List<Topic>)->Unit
    ) {
        topics.groupBy { "${it.systemType}/${it.systemName}"  }.forEach { group ->
            val request = JsonObject().put("ClientId", clientId)
            request.put("Topics", JsonArray(group.value.map { it.encodeToJson() }))
            val address = "${group.key}/Unsubscribe"
            vertx.eventBus().request<JsonObject>(address, request) {
                logger.finest { "Unsubscribe response [${it.succeeded()}] [${it.result()?.body()}]" }
                val ok = it.succeeded() && it.result().body().getBoolean("Ok")
                onComplete(ok, group.value)
            }
        }
    }

    fun publishDataPoint(
        vertx: Vertx,
        dataPoint: DataPoint
    ) {
        vertx.eventBus().publish(dataPoint.topic.topicName, dataPoint)
    }

    fun publishBufferValue(
        vertx: Vertx,
        topic: String,
        value: Buffer
    ) {
        vertx.eventBus().publish(topic, value)
    }

    fun publishJsonValue(
        vertx: Vertx,
        topic: String,
        value: JsonObject
    ) {
        vertx.eventBus().publish(topic, value)
    }
}