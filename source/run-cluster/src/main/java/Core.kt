import at.rocworks.data.*
import at.rocworks.mqtt.MqttVerticle
import at.rocworks.opcua.OpcUaHandler
import at.rocworks.opcua.OpcUaVerticle
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.slf4j.LoggerFactory
import java.util.logging.LogManager
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Core {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val stream = Core::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = (LoggerFactory.getLogger(javaClass.simpleName));

        val clusterManager = IgniteClusterManager()

        val vertxOptions = VertxOptions().setClusterManager(clusterManager)

        val vertxClusterResult = Vertx.clusteredVertx(vertxOptions)

        vertxClusterResult.onComplete {
            val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
            println("Using config file: $configFilePath")

            val vertx = it.result()

            // Register Message Types
            vertx.eventBus().registerDefaultCodec(Topic::class.java, TopicCodec())
            vertx.eventBus().registerDefaultCodec(Value::class.java, ValueCodec())

            val config = Globals.RetrieveConfig(vertx, configFilePath)

            OpcUaVerticle.initKeyStoreLoader()

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

            // Clustered Map
            val map: Map<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map

        }
    }

    private fun createServices(vertx: Vertx, config: JsonObject) {
        // OPC UA Server
        val enabled: List<JsonObject> = config.getJsonArray("OpcUaClient")
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
        val defaultSystem = enabled.first()
        enabled.map {
            vertx.deployVerticle(OpcUaHandler(it))
        }

        // Mqtt Server
        config.getJsonObject("MqttServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                MqttVerticle.create(vertx, it)
            }
    }
}