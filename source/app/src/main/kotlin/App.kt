
import at.rocworks.gateway.core.graphql.GraphQLServer
import at.rocworks.gateway.core.mqtt.MqttDriver
import at.rocworks.gateway.core.mqtt.MqttLogger
import at.rocworks.gateway.core.mqtt.MqttServer
import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaDriver
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.logger.influx.InfluxDBLogger
import at.rocworks.gateway.logger.jdbc.JdbcLogger
import at.rocworks.gateway.logger.kafka.KafkaLogger
import at.rocworks.gateway.logger.iotdb.IoTDBLogger

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.logging.Logger

object App {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        KeyStoreLoader.init()
        Common.initLogging()
        Common.initVertx(args, Vertx.vertx(), App::createServices)
    }

    private fun createServices(vertx: Vertx, config: JsonObject) {
        // Drivers
        val enabled = config.getJsonObject("Drivers")
            ?.filter { it.value is JsonArray }
            ?.flatMap { (type, list) ->
                (list as JsonArray)
                    .filterIsInstance<JsonObject>()
                    .filter { it.getBoolean("Enabled", true) }
                    .map {
                        when (type) {
                            "OpcUa" -> vertx.deployVerticle(OpcUaDriver(it))
                            "Mqtt" -> vertx.deployVerticle(MqttDriver(it))
                        }
                        it
                    }
            } ?: listOf()
        val defaultSystem = if (enabled.isNotEmpty()) enabled.first().getString("Id") else ""

        // Servers
        config.getJsonObject("Servers")
            ?.filter { it.value is JsonArray }
            ?.forEach { (type, list) ->
                (list as JsonArray)
                    .filterIsInstance<JsonObject>()
                    .filter { it.getBoolean("Enabled", true) }
                    .forEach { config ->
                        when (type) {
                            "Mqtt" -> MqttServer.create(vertx, config)
                            "GraphQL" -> GraphQLServer.create(vertx, config, defaultSystem)
                        }
                    }
            }

        // Loggers
        config.getJsonObject("Loggers")
            ?.filter { it.value is JsonArray }
            ?.forEach { (type, list) ->
                (list as JsonArray)
                    .filterIsInstance<JsonObject>()
                    .filter { it.getBoolean("Enabled", true) }
                    .forEach { config -> createLogger(vertx, type, config)
                }
            }
    }

    private fun createLogger(vertx: Vertx, type: String, config: JsonObject) {
        val logger = Logger.getLogger(javaClass.simpleName)
        when (type) {
            "Mqtt" -> vertx.deployVerticle(MqttLogger(config))
            "Kafka" ->  vertx.deployVerticle(KafkaLogger(config))
            "Jdbc" -> vertx.deployVerticle(JdbcLogger(config))
            "InfluxDB" -> vertx.deployVerticle(InfluxDBLogger(config))
            "IoTDB" -> vertx.deployVerticle(IoTDBLogger(config))
            else -> logger.severe("Unknown database type [${type}]")
        }
    }
}