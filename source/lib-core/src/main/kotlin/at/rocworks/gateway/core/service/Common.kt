package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.data.*
import at.rocworks.gateway.core.graphql.ConfigServer
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.Security
import java.util.logging.LogManager
import java.util.logging.Logger

object Common {
    const val BUS_ROOT_URI_LOG = "Log"

    private val logger = Logger.getLogger(javaClass.simpleName)

    private lateinit var configFileName : String

    //private var configFileName : String = "config"
    //private var configFileAvailable : Boolean = false

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

    private fun getConfigFileName() = getConfigFileName(configFileName)

    private fun getConfigFileName(configFileName: String): Pair<Boolean, String> {
        var configFileAvailable: Boolean
        var effectiveConfigFileName: String?

        try {
            getConfigFileFormat(configFileName) // This may throw an exception and its handling is required
            effectiveConfigFileName = configFileName.takeIf { File(it).exists() }

            if (effectiveConfigFileName != null) {
                configFileAvailable = true
            } else {
                logger.warning("Config file $configFileName does not exist.")
                configFileAvailable = false
                effectiveConfigFileName = null
            }
        } catch (e: Exception) {
            effectiveConfigFileName = when {
                File("$configFileName.json").exists() -> "$configFileName.json"
                File("$configFileName.yaml").exists() -> "$configFileName.yaml"
                else -> {
                    logger.warning("No config file $configFileName.[json|yaml] found.")
                    null
                }
            }
            configFileAvailable = effectiveConfigFileName != null
        }

        return Pair(configFileAvailable, effectiveConfigFileName ?: "")
    }

    fun initGateway(args: Array<String>,
                    vertx: Vertx,
                    factory: (Component.ComponentType, JsonObject) -> Component?) {
        try {
            // Required for SecurityPolicy.Aes256_Sha256_RsaPss
            Security.addProvider(BouncyCastleProvider())

            // Register Message Types
            vertx.eventBus().registerDefaultCodec(Topic::class.java, CodecTopic())
            vertx.eventBus().registerDefaultCodec(TopicValue::class.java,CodecTopicValue())
            vertx.eventBus().registerDefaultCodec(DataPoint::class.java,CodecDataPoint())

            // Config Filename
            configFileName = if (args.isNotEmpty()) args[0] else System.getenv("GATEWAY_CONFIG") ?: "config"

            // Http Server
            val httpPort = (System.getenv("GATEWAY_CONFIG_HTTP") ?: "0").toIntOrNull() ?: 0
            if (httpPort > 0) vertx.deployVerticle(WebConfig(httpPort, configFileName))

            // Config file
            val retry = (System.getenv("GATEWAY_CONFIG_RETRY") ?: "0").toIntOrNull() ?: 0
            var result = getConfigFileName(configFileName)
            while (!result.first && retry > 0) {
                Thread.sleep(retry * 1000L)
                result = getConfigFileName(configFileName)
            }

            if (!result.first) {
                logger.info("Stopping Gateway.")
                vertx.close()
            } else {
                // Retrieve Config
                retrieveConfig(vertx, result.second)?.let { config ->
                    // Go through the configuration file
                    config.getConfig { cfg ->
                        logger.info("Loading Configuration...")
                        if (cfg != null && cfg.succeeded()) {
                            val componentHandler = ComponentHandler(vertx, cfg.result(), factory)
                            val envConfigName = "GATEWAY_CONFIG_PORT"
                            val envConfigPort = System.getenv(envConfigName) ?: "empty"
                            val port = envConfigPort.toIntOrNull()
                            if (port != null) {
                                logger.info("Config GraphQL Server listening on port ${port}.")
                                vertx.deployVerticle(ConfigServer(componentHandler, port))
                            } else {
                                logger.info("Config GraphQL Server not started [GATEWAY_CONFIG_PORT=${envConfigPort}].")
                            }
                        } else {
                            println("Missing or invalid $configFileName file! ${cfg.cause().message}")
                            config.close()
                            vertx.close()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getConfigFileFormat(configFileName: String) : String {
        return if (configFileName.endsWith(".yaml")) "yaml"
        else if (configFileName.endsWith(".json")) "json"
        else {
            throw Exception("Unknown config file format ${configFileName}!")
        }
    }

    private fun retrieveConfig(vertx: Vertx, configFileName: String): ConfigRetriever? {
        logger.info("Gateway config file: $configFileName")
        val format = getConfigFileFormat(configFileName)

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
        val configFileName = configFileName.removeSuffix("."+getConfigFileFormat(configFileName)) + ".json"
        val file = File(configFileName)
        try {
            file.writeText(config.encodePrettily())
            logger.info("Config file successfully written to ${configFileName}.")
        } catch (e: Exception) {
            logger.severe("Error writing to the file: ${e.message}")
        }
    }
}