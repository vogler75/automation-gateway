package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.data.*
import at.rocworks.gateway.core.graphql.ConfigServer
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.Security
import java.util.logging.LogManager
import java.util.logging.Logger

object Common {
    private val logger = Logger.getLogger(javaClass.simpleName)
    private var configFileName : String = "config"
    private var configFileAvailable : Boolean = false
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

            // Config file format
            fun getConfigFileName(): Boolean {
                configFileName = if (args.isNotEmpty()) args[0] else System.getenv("GATEWAY_CONFIG") ?: configFileName
                try {
                    getConfigFileFormat()
                    if (File(configFileName).exists()) {
                        configFileAvailable = true
                    } else {
                        logger.warning("Config file $configFileName does not exist.")
                        configFileAvailable = false
                    }
                } catch (e: Exception) {
                    if (File("$configFileName.json").exists()) {
                        configFileName = "$configFileName.json"
                        configFileAvailable = true
                    } else  if (File("$configFileName.yaml").exists()) {
                        configFileName = "$configFileName.yaml"
                        configFileAvailable = true
                    } else {
                        logger.warning("No config file $configFileName.[json|yaml] found.")
                        configFileAvailable = false
                    }
                }
                return configFileAvailable
            }

            val http = (System.getenv("GATEWAY_CONFIG_HTTP") ?: "0").toIntOrNull() ?: 0
            val server = if (http > 0) createHttpServer(vertx, http) else null

            val retry = (System.getenv("GATEWAY_CONFIG_RETRY") ?: "0").toIntOrNull() ?: 0
            while (!getConfigFileName() && retry>0) Thread.sleep(retry*1000L);

            if (!configFileAvailable && server!=null) {
                logger.info("Stopping Gateway.")
                server.close()
                vertx.close()
            }
            else if (configFileAvailable) {
                // Retrieve Config
                retrieveConfig(vertx)?.let { config ->
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

    fun ensureYamlExtension(configFileName: String): String {
        // Regex to check if the file has an extension
        val regex = Regex(".*\\.[a-zA-Z0-9]+$")

        return if (regex.matches(configFileName)) {
            // If the file has an extension, return it as is
            configFileName
        } else {
            // If the file does not have an extension, add .yaml to it
            "$configFileName.yaml"
        }
    }

    private fun createHttpServer(vertx: Vertx, port: Int) : HttpServer {
        val index = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>File Upload</title>
        </head>
        <body>
            <h1>Upload Config File</h1>
            <form action="/upload" method="post" enctype="multipart/form-data">
                <input type="file" name="file" accept=".yaml">
                <button type="submit">Upload</button>
            </form>
        </body>
        </html>            
        """.trimIndent()
        val router = Router.router(vertx)

        // Serve the static HTML page
        router.get("/").handler { ctx ->
            ctx.response()
                .putHeader("Content-Type", "text/html")
                .end(index)
        }

        // Handle file uploads
        router.route().handler(BodyHandler.create().setUploadsDirectory("config"))

        router.post("/upload").handler { ctx ->
            val upload = ctx.fileUploads().iterator().next()
            val uploadedFileName = upload.uploadedFileName()
            val fileName = upload.fileName()

            if (fileName.isEmpty()) {
                    ctx.response()
                        .putHeader("Content-Type", "text/html")
                        .end("""
                    <html>
                    <body>
                        <h1>Error: No file selected</h1>
                        <button onclick="window.location.href='/'">Return to Upload Page</button>
                    </body>
                    </html>
                """.trimIndent())
            } else {
                // Move the file to a new location
                val targetPath = Paths.get(ensureYamlExtension(configFileName))
                Files.move(Paths.get(uploadedFileName), targetPath, StandardCopyOption.REPLACE_EXISTING)
                ctx.response()
                    .putHeader("Content-Type", "text/html")
                    .end("""
                    <html>
                    <body>
                        <h1>File uploaded to ${targetPath.toAbsolutePath()}</h1>
                        <button onclick="window.location.href='/'">Return to Upload Page</button>
                    </body>
                    </html>
                """.trimIndent())
            }
        }

        val server = vertx.createHttpServer().requestHandler(router).listen(port) {
            if (it.succeeded()) {
                logger.info("HTTP Server started on port $port")
            } else {
                logger.warning("Failed to start HTTP Server: ${it.cause()}")
            }
        }

        return server
    }

    private fun getConfigFileFormat() : String {
        return if (configFileName.endsWith(".yaml")) "yaml"
        else if (configFileName.endsWith(".json")) "json"
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