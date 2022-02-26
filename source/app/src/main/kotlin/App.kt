import at.rocworks.gateway.core.graphql.GraphQLServer
//import at.rocworks.gateway.core.mqtt.MqttDriver
import at.rocworks.gateway.core.mqtt.MqttLogger
import at.rocworks.gateway.core.mqtt.MqttServer
import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaDriver
import at.rocworks.gateway.core.service.Common

import at.rocworks.gateway.logger.influx.InfluxDBLogger
//import at.rocworks.gateway.logger.iotdb.IoTDBLogger
import at.rocworks.gateway.logger.jdbc.JdbcLogger
import at.rocworks.gateway.logger.kafka.KafkaLogger
import at.rocworks.gateway.logger.neo4j.Neo4jLogger

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

    private fun createLogger(vertx: Vertx, config: JsonObject) {
        val logger = Logger.getLogger(javaClass.simpleName)
        if (config.getBoolean("Enabled", true)) {
            when (val type = config.getString("Type")) {
                "InfluxDB" -> {
                    vertx.deployVerticle(InfluxDBLogger(config))
                }
                //"IoTDB" -> {
                //    vertx.deployVerticle(IoTDBLogger(config))
                //}
                "Jdbc" -> {
                    vertx.deployVerticle(JdbcLogger(config))
                }
                "Kafka" -> {
                    vertx.deployVerticle(KafkaLogger(config))
                }
                "Mqtt" -> {
                    vertx.deployVerticle(MqttLogger(config))
                }
                "Neo4j" -> {
                    vertx.deployVerticle(Neo4jLogger(config))
                }
                else -> logger.severe("Unknown database type [${type}]")
            }
        }
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

        // Mqtt Client
        /*
        config.getJsonArray("MqttClient")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                vertx.deployVerticle(MqttDriver(it))
            }
         */

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