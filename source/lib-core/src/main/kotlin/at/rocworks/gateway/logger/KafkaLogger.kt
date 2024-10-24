package at.rocworks.gateway.logger

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerPublisher
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject

import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord

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
        producer = null
        promise.complete()
        return promise.future()
    }

    override fun isEnabled(): Boolean {
        return producer != null
    }

    override fun publish(topic: Topic, payload: Buffer) {
        val destination = topicName?:topic.systemName
        val key = keyName?:topic.getBrowsePathOrNode().toString()
        val record = KafkaProducerRecord.create<String, String>(destination, key, payload.toString())
        producer?.write(record)?.onComplete {
            valueCounterOutput++
        }?.onFailure {
            logger.severe("Error writing record [${it.message}]")
        }
    }

    override fun publish(topics: List<Topic>, payload: Buffer) {
        val record = KafkaProducerRecord.create<String, String>(topicName, payload.toString())
        producer?.write(record)?.onComplete {
            valueCounterOutput+=topics.size
        }?.onFailure {
            logger.severe("Error writing record [${it.message}]")
        }
    }
}