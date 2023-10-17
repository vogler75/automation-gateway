package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.data.*
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.logging.LogManager
import java.util.logging.Logger

import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Common {
    private val logger = Logger.getLogger(javaClass.simpleName)
    private val configFileName = System.getenv("GATEWAY_CONFIG") ?: "config.yaml"

    const val BUS_ROOT_URI_LOG = "Log"

    fun initLogging() {

        try {
            println("Loading logging.properties...")
            val initialFile = File("logging.properties")
            val targetStream: InputStream = FileInputStream(initialFile)
            LogManager.getLogManager().readConfiguration(targetStream)
        } catch (e: Exception) {
            try {
                println("Using default logging.properties...")
                val stream = this::class.java.classLoader.getResourceAsStream("logging.properties")
                LogManager.getLogManager().readConfiguration(stream)
            } catch (e: Exception) {
                println("Unable to read default logging.properties!")
            }
        }
    }

    fun initVertx(args: Array<String>, vertx: Vertx, services: (Vertx, JsonObject) -> Unit) {
        try {
            val configFilePath = if (args.isNotEmpty()) args[0] else configFileName
            logger.info("Gateway config file: $configFilePath")

            // Register Message Types
            vertx.eventBus().registerDefaultCodec(Topic::class.java, CodecTopic())
            vertx.eventBus().registerDefaultCodec(TopicValue::class.java,CodecTopicValue())

            // Retrieve Config
            val config = retrieveConfig(vertx, configFilePath)

            // Go through the configuration file
            config.getConfig { cfg ->
                if (cfg != null && cfg.succeeded()) {
                    thread { // because it will block
                        services(vertx, cfg.result())
                    }
                } else {
                    println("Missing or invalid $configFilePath file!")
                    config.close()
                    vertx.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun retrieveConfig(vertx: Vertx, configFilePath: String): ConfigRetriever = ConfigRetriever.create(
        vertx,
        ConfigRetrieverOptions().addStore(
            ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(JsonObject().put("path", configFilePath))
        )
    )
}