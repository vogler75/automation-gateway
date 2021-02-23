package at.rocworks.cluster

import at.rocworks.data.*
import at.rocworks.opcua.OpcUaVerticle
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
//import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.slf4j.LoggerFactory
import java.util.logging.LogManager
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Cluster {
    fun setup(args: Array<String>, services: (Vertx, JsonObject) -> Unit) {
        val stream = Gateway::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = (LoggerFactory.getLogger(javaClass.simpleName));

        OpcUaVerticle.initKeyStoreLoader()

        val clusterManager = IgniteClusterManager()
        //val clusterManager = HazelcastClusterManager()

        val vertxOptions = VertxOptions().setClusterManager(clusterManager)

        val vertxClusterResult = Vertx.clusteredVertx(vertxOptions)

        vertxClusterResult.onComplete {
            val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
            println("Using config file: $configFilePath")

            val vertx = it.result()

            try {
                // Register Message Types
                vertx.eventBus().registerDefaultCodec(Topic::class.java, TopicCodec())
                vertx.eventBus().registerDefaultCodec(Value::class.java, ValueCodec())

                // Retrieve Config
                val config = Globals.RetrieveConfig(vertx, configFilePath)

                // Go through the configuration file
                config.getConfig { cfg ->
                    if (cfg == null || cfg.failed()) {
                        println("Missing or invalid $configFilePath file!")
                        config.close()
                        vertx.close()
                    } else {
                        thread { // because it will block
                            services(vertx, cfg.result())
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Clustered Map
            //val map: Map<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map
        }
    }
}