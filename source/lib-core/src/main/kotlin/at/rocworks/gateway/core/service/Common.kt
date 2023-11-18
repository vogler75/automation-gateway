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

object Common {
    private val logger = Logger.getLogger(javaClass.simpleName)
    private var configFileName : String = "config"
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

    fun initGateway(args: Array<String>, vertx: Vertx, onSuccess: (JsonObject)->Unit) {
        try {
            // Register Message Types
            vertx.eventBus().registerDefaultCodec(Topic::class.java, CodecTopic())
            vertx.eventBus().registerDefaultCodec(TopicValue::class.java,CodecTopicValue())
            vertx.eventBus().registerDefaultCodec(DataPoint::class.java,CodecDataPoint())

            // Config file format
            configFileName = if (args.isNotEmpty()) args[0] else System.getenv("GATEWAY_CONFIG") ?: configFileName
            try {
                getConfigFileFormat()
            } catch (e: Exception) {
                if (File("$configFileName.json").exists()) {
                    configFileName = "$configFileName.json"
                } else  if (File("$configFileName.yaml").exists()) {
                    configFileName = "$configFileName.yaml"
                } else {
                    throw Exception("No config file $configFileName.[json|yaml] found.")
                }
            }

            // Retrieve Config
            retrieveConfig(vertx)?.let { config ->
                // Go through the configuration file
                config.getConfig { cfg ->
                    if (cfg != null && cfg.succeeded()) {
                        onSuccess(cfg.result())
                    } else {
                        println("Missing or invalid $configFileName file! ${cfg.cause().message}")
                        config.close()
                        vertx.close()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getConfigFileFormat() : String {
        return if (configFileName.endsWith(".yaml")) "yaml"
        else if (configFileName.endsWith("json")) "json"
        else {
            throw Exception("Unknown config file format ${configFileName}!")
        }
    }

    private fun retrieveConfig(vertx: Vertx): ConfigRetriever? {
        logger.info("Gateway config file: $configFileName")
        val format = getConfigFileFormat()

        return ConfigRetriever.create(
            vertx,
            ConfigRetrieverOptions().addStore(
                ConfigStoreOptions()
                    .setType("file")
                    .setFormat(format)
                    .setConfig(JsonObject().put("path", configFileName))
            )
        )
    }

    fun saveConfigToFile(config: JsonObject) {
        val configFileName = configFileName.removeSuffix("."+getConfigFileFormat()) + ".json"
        val file = File(configFileName)
        try {
            file.writeText(config.encodePrettily())
            logger.info("Config file successfully written to ${configFileName}.")
        } catch (e: Exception) {
            logger.severe("Error writing to the file: ${e.message}")
        }
    }
}