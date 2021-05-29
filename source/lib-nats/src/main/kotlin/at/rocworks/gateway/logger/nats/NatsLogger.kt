package at.rocworks.gateway.logger.nats

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit

import io.nats.client.Nats

class NatsLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "nats://localhost:4222")

    private val session = Nats.connect(url)

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        promise.complete()
        return promise.future()
    }

    override fun close() {
        session.close()
    }

    override fun writeExecutor() {
        var counter = 0
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && ++counter <= writeParameterBlockSize) {
            session.publish(point.topic.topicName, point.value.encodeToJson().toString().toByteArray())
            point = writeValueQueue.poll()
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}