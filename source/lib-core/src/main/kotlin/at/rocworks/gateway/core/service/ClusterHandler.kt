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
import java.io.File
import java.net.URL

import io.vertx.spi.cluster.ignite.IgniteClusterManager

import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import com.hazelcast.config.FileSystemYamlConfig
import org.apache.ignite.Ignite
import org.slf4j.Logger
import org.apache.ignite.IgniteCache
import org.apache.ignite.configuration.CacheConfiguration


object ClusterHandler {
    val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    var clusterManager: ClusterManager = igniteClusterManager()
    //val clusterManager: ClusterManager = hazelcastClusterManager()

    var piOpcCache: IgniteCache<String, PiOpcValue>? = null

    fun init(args: Array<String>, services: (Vertx, JsonObject) -> Unit) {
        val stream = ClusterHandler::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val vertxOptions = VertxOptions().setClusterManager(clusterManager)
        Vertx.clusteredVertx(vertxOptions).onComplete { vertx ->
            if (vertx.succeeded()) {
                setupCluster(args, vertx.result(), services)

                if (clusterManager is IgniteClusterManager) {
                    createCache(clusterManager as IgniteClusterManager)
                }
            } else {
                logger.error("Error initializing cluster!")
            }
        }
    }

    private fun setupCluster(args: Array<String>, vertx: Vertx, services: (Vertx, JsonObject) -> Unit) {
        logger.info("Cluster nodeId: ${clusterManager.nodeId}")

        val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
        logger.info("Gateway config file: $configFilePath")

        val serviceHandler = ServiceHandler(vertx, logger)

        val nodeListener = object : NodeListener {
            override fun nodeAdded(nodeID: String) {
                logger.info("Added nodeId: $nodeID")
            }

            override fun nodeLeft(nodeID: String) {
                logger.info("Removed nodeId: $nodeID")
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
    }

    private fun igniteClusterManager() = try {
        val fileName = File("ignite.xml")
        val clusterConfig = URL(fileName.readText())
        logger.info("Cluster config file [{}]", fileName)
        IgniteClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration.")
        IgniteClusterManager()
    }

    private fun hazelcastClusterManager() = try {
        val fileName = "hazelcast.yaml"
        val clusterConfig = FileSystemYamlConfig(fileName)
        logger.info("Cluster config file [{}]", fileName)
        HazelcastClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration.")
        HazelcastClusterManager()
    }

    private fun commonCacheTest(clusterManager: ClusterManager) {
        // Clustered Map
        val map: MutableMap<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map
        map["test"] = "test"
    }

    private fun createCache(clusterManager: IgniteClusterManager) {
        /*
          select table_name from information_schema.tables where table_schema='PUBLIC';
          select column_name, data_type, type_name, column_type from information_schema.columns where table_name='OPCCACHE';
         */
        try {
            val ignite: Ignite = clusterManager.igniteInstance
            val topicCacheCfg = CacheConfiguration<String, PiOpcValue>()
            topicCacheCfg.name = "PUBLIC"
            topicCacheCfg.setIndexedTypes(String::class.java, PiOpcValue::class.java)
            piOpcCache = ignite.getOrCreateCache(topicCacheCfg)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    fun storeTopicValue(topic: Topic, value: TopicValue) {
        try {
            val piValue = PiOpcValue(topic, value)
            piOpcCache?.put(piValue.key(), piValue)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }
}
