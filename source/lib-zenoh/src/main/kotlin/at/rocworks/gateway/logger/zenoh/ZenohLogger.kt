package at.rocworks.gateway.logger.zenoh

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerBase
import at.rocworks.gateway.core.logger.LoggerPublisher
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject

import java.time.format.DateTimeFormatter

import io.zenoh.Session

class ZenohLogger(config: JsonObject) : LoggerPublisher(config) {
    private val topicName = config.getString("TopicName", "Gateway")

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            logger.info("Zenoh connected.")
            result.complete()
        } catch (e: Exception) {
            logger.severe("Zenoh connect failed! [${e.message}]", )
            e.printStackTrace()
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        promise.complete()
        return promise.future()
    }

    override fun publish(topic: Topic, payload: Buffer) {
        val destination = topicName?:topic.systemName
//        val key = keyName?:topic.getBrowsePathOrNode().toString()
//        val record = KafkaProducerRecord.create<String, String>(destination, key, payload.toString())
//        producer?.write(record)?.onComplete {
//            valueCounterOutput++
//        }
    }

    override fun publish(topics: List<Topic>, payload: Buffer) {
//        val record = KafkaProducerRecord.create<String, String>(topicName, payload.toString())
//        producer?.write(record)?.onComplete {
//            valueCounterOutput+=topics.size
//        }
    }
}