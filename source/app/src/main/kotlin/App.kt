import at.rocworks.gateway.core.data.*
import at.rocworks.gateway.graphql.GraphQLServer
import at.rocworks.gateway.logger.influx.InfluxDBLogger
import at.rocworks.gateway.core.mqtt.MqttVerticle
import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaVerticle

import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.lang.Exception
import java.util.logging.LogManager
import kotlin.system.exitProcess

import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

object App {

    // TODO: add TLS and login to MQTT
    // TODO: add TLS and security to GraphQL

    // TODO: add HTTP as query interface
    // TODO: add statistics (read/writes per second) and publish it on a topic

    // TODO: add option for sampling interval - as part of the topic?

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val stream = App::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = LoggerFactory.getLogger(javaClass.simpleName)

        val vertx = Vertx.vertx()

        // Register Message Types
        vertx.eventBus().registerDefaultCodec(Topic::class.java, TopicCodec())
        vertx.eventBus().registerDefaultCodec(Value::class.java, ValueCodec())

        // Read config file
        val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
        logger.info("Gateway config file: $configFilePath")
        val config = Globals.RetrieveConfig(vertx, configFilePath)

        KeyStoreLoader.init()

        // Go through the configuration file
        config.getConfig { cfg ->
            if (cfg==null || cfg.failed()) {
                println("Missing or invalid $configFilePath file!")
                config.close()
                vertx.close()
            } else {
                thread { // because it will block
                    createServices(vertx, cfg.result())
                }
            }
        }
    }

    private fun createLogger(vertx: Vertx, config: JsonObject) {
        val logger = LoggerFactory.getLogger(javaClass.simpleName)
        when (val type = config.getString("Type")) {
            "InfluxDB" -> vertx.deployVerticle(InfluxDBLogger(config))
            else -> logger.error("Unknown database type [{}]", type)
        }
    }

    private fun createServices(vertx: Vertx, config: JsonObject) {
        // OPC UA Server
        val enabled: List<JsonObject> = config.getJsonArray("OpcUaClient")
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
        val defaultSystem = enabled.first()
        enabled.map {
            vertx.deployVerticle(OpcUaVerticle(it))
        }

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
                GraphQLServer.create(vertx, it, defaultSystem.getString("Id"))
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