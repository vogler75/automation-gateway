package at.rocworks

import at.rocworks.data.*
import at.rocworks.dblogger.DbLogger
import at.rocworks.graphql.GraphQLServer
import at.rocworks.mqtt.*
import at.rocworks.opcua.OpcUaHandler
import at.rocworks.opcua.OpcUaVerticle
import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.lang.Exception
import java.util.logging.LogManager
import kotlin.system.exitProcess

import io.vertx.config.ConfigStoreOptions
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigRetriever
import io.vertx.core.AsyncResult
import org.slf4j.LoggerFactory
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread


object Main {

    // TODO: add TLS and login to MQTT
    // TODO: add TLS and security to GraphQL

    // TODO: add HTTP as query interface
    // TODO: add statistics (read/writes per second) and publish it on a topic

    // TODO: add option for sampling interval - as part of the topic?

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val stream = Main::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = LoggerFactory.getLogger(javaClass.simpleName)

        Logger.getLogger("Mqtt").level = Level.ALL

        val vertx = Vertx.vertx()

        // Register Message Types
        vertx.eventBus().registerDefaultCodec(Topic::class.java, TopicCodec())
        vertx.eventBus().registerDefaultCodec(Value::class.java, ValueCodec())

        // Read config file
        val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
        println("Using config file: $configFilePath")
        val config = ConfigRetriever.create(
            vertx,
            ConfigRetrieverOptions().addStore(
                ConfigStoreOptions()
                    .setType("file")
                    .setFormat("yaml")
                    .setConfig(JsonObject().put("path", configFilePath))
            )
        )

        OpcUaVerticle.initKeyStoreLoader()

        // Go through the configuration file
        config.getConfig { cfg ->
            if (cfg==null || cfg.failed()) {
                println("Missing or invalid $configFilePath file!")
                config.close()
                vertx.close()
            } else {
                thread { // because it will block
                    createServices(vertx, cfg)
                }
            }
        }
    }

    private fun createServices(vertx: Vertx, config: AsyncResult<JsonObject>) {
        // OPC UA Server
        val enabled: List<JsonObject> = config.result().getJsonArray("OpcUaClient")
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
        val defaultSystem = enabled.first()
        enabled.map {
            vertx.deployVerticle(OpcUaHandler(it))
        }

        // Mqtt Server
        config.result().getJsonObject("MqttServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                MqttVerticle.create(vertx, it)
            }

        // Start GraphQL Server
        config.result().getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                GraphQLServer.create(vertx, it, defaultSystem.getString("Id"))
            }

        // DB Logger
        config.result().getJsonObject("Database")
            ?.getJsonArray("Logger")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                DbLogger.create(vertx, it)
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