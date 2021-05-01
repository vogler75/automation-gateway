import at.rocworks.gateway.core.graphql.GraphQLServer
import at.rocworks.gateway.core.mqtt.MqttVerticle
import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaVerticle
import at.rocworks.gateway.core.service.Common

import at.rocworks.gateway.logger.influx.InfluxDBLogger
import at.rocworks.gateway.logger.iotdb.IoTDBLogger
import at.rocworks.gateway.logger.kafka.KafkaLogger

import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.lang.Exception

import org.slf4j.LoggerFactory

object App {

    // TODO: add TLS and login to MQTT
    // TODO: add TLS and security to GraphQL

    // TODO: add HTTP as query interface
    // TODO: add statistics (read/writes per second) and publish it on a topic

    // TODO: add option for sampling interval - as part of the topic?

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        KeyStoreLoader.init()
        Common.initLogging()
        Common.initVertx(args, Vertx.vertx(), App::createServices)
    }

    private fun createLogger(vertx: Vertx, config: JsonObject) {
        val logger = LoggerFactory.getLogger(javaClass.simpleName)
        when (val type = config.getString("Type")) {
            "InfluxDB" -> {
                vertx.deployVerticle(InfluxDBLogger(config))
            }
            "IoTDB" -> {
                vertx.deployVerticle(IoTDBLogger(config))
            }
            "Kafka" -> {
                vertx.deployVerticle(KafkaLogger(config))
            }
            else -> logger.error("Unknown database type [{}]", type)
        }
    }

    private fun createServices(vertx: Vertx, config: JsonObject) {
        // OPC UA Server
        val enabled = config.getJsonArray("OpcUaClient")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled") }
            ?: listOf()
        enabled.map {
            vertx.deployVerticle(OpcUaVerticle(it))
        }

        val defaultSystem = if (enabled.isNotEmpty()) enabled.first().getString("Id") else "default"

        // Mqtt Server
        config.getJsonObject("MqttServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                MqttVerticle.create(vertx, it)
            }

        // Start GraphQL Server
        config.getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
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

        // Start HTTP Webserver
        /*
        run {
            val router = Router.router(vertx)
            router.route("/").handler { ctx ->
                val response = ctx.response()
                response.putHeader("content-type", "text/plain")
                response.end("Hello World from Reactive SCADA.")
            }
            val httpServer = vertx.createHttpServer()
            httpServer.requestHandler(router).listen(8080)
        }
        */
    }
}