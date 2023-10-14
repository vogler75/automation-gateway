import at.rocworks.gateway.core.graphql.GraphQLServer
import at.rocworks.gateway.core.mqtt.MqttLogger
import at.rocworks.gateway.core.mqtt.MqttServer
import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaDriver
import at.rocworks.gateway.core.service.Common

import at.rocworks.gateway.logger.influx.InfluxDBLogger
import at.rocworks.gateway.logger.jdbc.JdbcLogger
import at.rocworks.gateway.logger.kafka.KafkaLogger

import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.lang.Exception
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
        // OPC UA Client
        val enabled = config.getJsonArray("OpcUaClient")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled", true) }
            ?: listOf()
        enabled.map {
            vertx.deployVerticle(OpcUaDriver(it))
        }

        val defaultSystem = if (enabled.isNotEmpty()) enabled.first().getString("Id") else "default"

        // Mqtt Server
        config.getJsonObject("MqttServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled", true) }
            ?.forEach {
                MqttServer.create(vertx, it)
            }

        // Start GraphQL Server
        config.getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled", true) }
            ?.forEach {
                GraphQLServer.create(vertx, it, defaultSystem)
            }

        // DB Logger
        config.getJsonObject("Database")
            ?.getJsonArray("Logger")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                createLogger(vertx, it)
            }

        // Plc4x
        config.getJsonObject("Plc4x")
            ?.getJsonArray("Drivers")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled") }
            ?.forEach {
                vertx.deployVerticle(Plc4xDriver(it))
            }
    }

    private fun createLogger(vertx: Vertx, config: JsonObject) {
        val logger = Logger.getLogger(javaClass.simpleName)
        if (config.getBoolean("Enabled", true)) {
            when (val type = config.getString("Type")) {
                "Mqtt" -> vertx.deployVerticle(MqttLogger(config))
                "Kafka" ->  vertx.deployVerticle(KafkaLogger(config))
                "Jdbc" -> vertx.deployVerticle(JdbcLogger(config))
                "InfluxDB" -> vertx.deployVerticle(InfluxDBLogger(config))
                else -> logger.severe("Unknown database type [${type}]")
            }
        }
    }
}