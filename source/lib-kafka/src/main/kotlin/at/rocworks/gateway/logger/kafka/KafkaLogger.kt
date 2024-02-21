package at.rocworks.gateway.logger.kafka

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerPublisher
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject

import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord

import java.util.concurrent.TimeUnit

class KafkaLogger(config: JsonObject) : LoggerPublisher(config) {
    private val servers = config.getString("Servers", "localhost:9092")
    private val configs = config.getJsonObject("Configs")
    private val topicName = config.getString("TopicName", "Gateway")
    private val keyName = config.getString("KeyName", null)

    @Volatile
    private var producer: KafkaProducer<String, String>? = null

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            val config: MutableMap<String, String> = HashMap()
            config["bootstrap.servers"] = servers

            config["key.serializer"] = "org.apache.kafka.common.serialization.StringSerializer"
            config["value.serializer"] = "org.apache.kafka.common.serialization.StringSerializer"

            configs?.forEach {
                logger.info("Kafka config: ${it.key}=${it.value}")
                config[it.key] = it.value.toString()
            }

            producer = KafkaProducer.create(vertx, config)
            logger.info("Kafka connected.")
            result.complete()
        } catch (e: Exception) {
            logger.severe("Kafka connect failed! [${e.message}]", )
            e.printStackTrace()
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        producer?.close()
        promise.complete()
        return promise.future()
    }

    override fun publish(point: DataPoint, payload: Buffer) {
        val topic = topicName?:point.topic.systemName
        val key = keyName?:point.topic.browsePath
        val record = KafkaProducerRecord.create<String, String>(topic, key, payload.toString())
        producer?.write(record)?.onComplete {
            valueCounterOutput++
        }
    }

    override fun publish(points: List<DataPoint>, payload: Buffer) {
        val record = KafkaProducerRecord.create<String, String>(topicName, payload.toString())
        producer?.write(record)?.onComplete {
            valueCounterOutput+=points.size
        }
    }
}