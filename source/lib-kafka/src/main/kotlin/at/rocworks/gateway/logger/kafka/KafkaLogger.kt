package at.rocworks.gateway.logger.kafka

import at.rocworks.gateway.core.logger.LoggerBase

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit


class KafkaLogger(config: JsonObject) : LoggerBase(config) {
    private val host = config.getString("Host", "localhost")
    private val port = config.getInteger("Port", 6667)
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")


    override fun open(): Boolean {
        return try {
            // TODO
            logger.info("Kafka connected.")
            true
        } catch (e: Exception) {
            logger.error("Kafka connect failed! [{}]", e.message)
            false
        }
    }

    override fun close() {
        // TODO
    }

    override fun writeExecutor() {
        val points = mutableListOf<Any>() // TODO: list of data
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && deviceIds.size <= writeParameterBlockSize) {
            try {
                val path = point.topic.systemName+"."+point.topic.browsePath.replace("/", ".")
                val time = point.value.sourceTime().toEpochMilli()
                val value = point.value.valueAsDouble() ?: point.value.valueAsString()
                val status = point.value.statusAsString()
                // TODO: add point
            } catch (e: Exception) {
                logger.error(e.message)
            }
            point = writeValueQueue.poll()
        }
        if (points.size > 0) {
            // TODO: insert
            valueCounterOutput+=deviceIds.size
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeNano: Long,
        toTimeNano: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}