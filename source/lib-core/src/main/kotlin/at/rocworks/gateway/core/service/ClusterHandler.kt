package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.data.*
import at.rocworks.gateway.core.data.CodecTopic
import at.rocworks.gateway.core.data.CodecTopicValueOpc

import org.slf4j.LoggerFactory


import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject

import java.io.FileNotFoundException
import java.util.logging.LogManager

import kotlin.concurrent.thread
import kotlin.system.exitProcess
import io.vertx.core.spi.cluster.ClusterManager
import io.vertx.core.spi.cluster.NodeListener

import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import com.hazelcast.config.FileSystemYamlConfig

object ClusterHandler {
    var manager : ClusterManager? = null
    fun setup(args: Array<String>, services: (Vertx, JsonObject) -> Unit) {
        val stream = ClusterHandler::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = (LoggerFactory.getLogger(javaClass.simpleName))

        val clusterManager = try {
            val fileName = "hazelcast.yaml"
            val clusterConfig = FileSystemYamlConfig(fileName)
            logger.info("Cluster config file [{}]", fileName)
            HazelcastClusterManager(clusterConfig)
        } catch (e: FileNotFoundException) {
            logger.info("Cluster default configuration.")
            HazelcastClusterManager()
        }

        val vertxOptions = VertxOptions().setClusterManager(clusterManager)
        val vertxClusterResult = Vertx.clusteredVertx(vertxOptions)

        vertxClusterResult.onComplete {
            manager = clusterManager
            logger.info("Cluster nodeId: ${manager?.nodeId}")

            val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
            logger.info("Gateway config file: $configFilePath")

            val vertx = it.result()

            val serviceHandler = ServiceHandler(vertx, logger)

            /*
            val listener = object : MembershipListener {
                override fun memberAdded(membershipEvent: MembershipEvent) {
                    logger.info("Added nodeId: ${membershipEvent.member.uuid}")
                }

                override fun memberRemoved(membershipEvent: MembershipEvent) {
                    logger.info("Removed nodeId: ${membershipEvent.member.uuid}")
                    serviceHandler.removeClusterNode(membershipEvent.member.uuid)
                }
            }

            clusterManager.hazelcastInstance.cluster.addMembershipListener(listener)
            */



            val nodeListener = object : NodeListener {
                override fun nodeAdded(nodeID: String) {
                    logger.info("Added nodeId: ${nodeID}")
                }

                override fun nodeLeft(nodeID: String) {
                    logger.info("Removed nodeId: ${nodeID}")
                    serviceHandler.removeClusterNode(nodeID)
                }

            }
            clusterManager.nodeListener(nodeListener)

            try {
                // Register Message Types
                vertx.eventBus().registerDefaultCodec(Topic::class.java, CodecTopic())
                vertx.eventBus().registerDefaultCodec(TopicValueOpc::class.java, CodecTopicValueOpc())
                vertx.eventBus().registerDefaultCodec(TopicValuePlc::class.java, CodecTopicValuePlc())
                vertx.eventBus().registerDefaultCodec(TopicValueDds::class.java, CodecTopicValueDds())

                // Retrieve Config
                val config = Globals.retrieveConfig(vertx, configFilePath)

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
