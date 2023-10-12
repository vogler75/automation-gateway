package at.rocworks.gateway.logger.kafka

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject

import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord

import java.util.concurrent.TimeUnit

class KafkaLogger(config: JsonObject) : LoggerBase(config) {
    private val servers = config.getString("Servers", "localhost:9092")
    private val configs = config.getJsonObject("Configs")
    private val topicName = config.getString("TopicName", null)
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

    override fun close() {
        producer?.close()
    }

    override fun writeExecutor() {
        var counter = 0
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null) {
            try {
                val payload = JsonObject()
                payload.put("nodeId", point.topic.node)
                payload.put("systemName", point.topic.systemName)
                payload.put("topicName", point.topic.topicName)
                payload.put("browsePath", point.topic.browsePath)
                payload.put("sourceTime", point.value.sourceTimeAsISO())
                payload.put("serverTime", point.value.serverTimeAsISO())
                payload.put("sourceTimeMs", point.value.sourceTimeMs())
                payload.put("serverTimeMs", point.value.serverTimeMs())
                payload.put("value", point.value.valueAsObject())
                payload.put("valueAsString", point.value.valueAsString())
                payload.put("valueAsDouble", point.value.valueAsDouble())
                payload.put("statusCode", point.value.statusAsString())
                val record: KafkaProducerRecord<String, String> = KafkaProducerRecord.create(
                    topicName?:point.topic.systemName,
                    keyName?:point.topic.browsePath,
                    payload.encodePrettily()
                )
                producer?.write(record)?.onComplete {
                    valueCounterOutput++
                }

            } catch (e: Exception) {
                logger.severe(e.message)
            }
            point = if (++counter < writeParameterBlockSize) writeValueQueue.poll() else null
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