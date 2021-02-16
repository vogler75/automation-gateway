import at.rocworks.data.*
import at.rocworks.graphql.GraphQLServer
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

object GraphQL {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val stream = GraphQL::class.java.classLoader.getResourceAsStream("logging.properties")
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
        // Start GraphQL Server
        config.getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                GraphQLServer.create(vertx, it, "oa1")
            }
    }
}