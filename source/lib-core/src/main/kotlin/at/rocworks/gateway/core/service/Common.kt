package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.cache.OpcNode
import at.rocworks.gateway.core.cache.OpcValue
import at.rocworks.gateway.core.data.*
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.logging.LogManager
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Common {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val configFileName = System.getenv("GATEWAY_CONFIG") ?: "config.yaml"

    const val BUS_ROOT_URI_LOG = "Log"

    fun initLogging() {
        val stream = Cluster::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }
    }

    fun initVertx(args: Array<String>, vertx: Vertx, services: (Vertx, JsonObject) -> Unit) {
        try {
            val configFilePath = if (args.isNotEmpty()) args[0] else configFileName
            logger.info("Gateway config file: $configFilePath")

            // Register Message Types
            vertx.eventBus().registerDefaultCodec(Topic::class.java, CodecTopic())
            vertx.eventBus().registerDefaultCodec(TopicValueOpc::class.java, CodecTopicValueOpc())
            vertx.eventBus().registerDefaultCodec(TopicValuePlc::class.java, CodecTopicValuePlc())
            vertx.eventBus().registerDefaultCodec(TopicValueDds::class.java, CodecTopicValueDds())

            vertx.eventBus().registerDefaultCodec(OpcNode::class.java, GenericCodec(OpcNode::class.java))
            vertx.eventBus().registerDefaultCodec(OpcValue::class.java, GenericCodec(OpcValue::class.java))

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