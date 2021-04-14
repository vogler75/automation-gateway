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
import org.slf4j.Logger

import io.vertx.spi.cluster.ignite.IgniteClusterManager
import io.vertx.spi.cluster.ignite.impl.VertxLogger
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager

import org.apache.ignite.events.EventType
import org.apache.ignite.configuration.IgniteConfiguration

import com.hazelcast.config.FileSystemYamlConfig
import io.vertx.core.eventbus.EventBusOptions
import java.net.InetAddress
import org.apache.ignite.configuration.DataStorageConfiguration




object ClusterHandler {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val envClusterType = (System.getenv("GATEWAY_CLUSTER_TYPE") ?: "ignite").toLowerCase()
    private val envClusterHost = System.getenv("GATEWAY_CLUSTER_HOST") ?: ""
    private val envClusterPort = System.getenv("GATEWAY_CLUSTER_PORT") ?: ""

    private val envConfigFile = System.getenv("CONFIG") ?: "config.yaml"

    private val clusterHost = if (envClusterHost=="*") InetAddress.getLocalHost().hostAddress else envClusterHost
    private val clusterPort = envClusterPort

    private val clusterManager: ClusterManager = when (envClusterType) {
        "hazelcast" -> getHazelcastClusterManager()
        "ignite" -> getIgniteClusterManager()
        else -> throw IllegalArgumentException("Unknown cluster type '$envClusterType'")
    }

    fun init(args: Array<String>, services: (Vertx, JsonObject) -> Unit) {
        val stream = ClusterHandler::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        logger.info("Config cluster host [{}] port [{}]", clusterHost, clusterPort)

        val eventBusOptions = EventBusOptions()
        if (clusterHost!="") {
            logger.info("Set cluster host to [{}]", clusterHost)
            eventBusOptions.host = clusterHost
            eventBusOptions.clusterPublicHost = clusterHost
        }

        if (clusterPort!="") {
            logger.info("Set cluster port to [{}]", clusterPort)
            eventBusOptions.port = clusterPort.toInt()
            eventBusOptions.clusterPublicPort = clusterPort.toInt()
        }

        val vertxOptions = VertxOptions()
            .setEventBusOptions(eventBusOptions)
            .setClusterManager(clusterManager)

        Vertx.clusteredVertx(vertxOptions).onComplete { vertx ->
            if (vertx.succeeded()) {
                initCluster(args, vertx.result(), services)
                ClusterCache.init(clusterManager, vertx.result())
            } else {
                logger.error("Error initializing cluster!")
            }
        }
    }

    fun getNodeId(): String {
        return clusterManager.nodeId
    }

    private fun initCluster(args: Array<String>, vertx: Vertx, services: (Vertx, JsonObject) -> Unit) {
        logger.info("Cluster nodeId: ${clusterManager.nodeId}")

        val configFilePath = if (args.isNotEmpty()) args[0] else envConfigFile
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

    private fun getIgniteClusterManager() = try {
        val fileName = File("ignite.xml")
        val clusterConfig = URL(fileName.readText())
        logger.info("Cluster config file [{}]", fileName)
        IgniteClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration.")
        val config = IgniteConfiguration()
        config.gridLogger = VertxLogger()
        config.metricsLogFrequency = 0
        config.setIncludeEventTypes(
            EventType.EVT_CACHE_OBJECT_PUT,
            EventType.EVT_CACHE_OBJECT_READ,
            EventType.EVT_CACHE_OBJECT_REMOVED,
            EventType.EVT_NODE_JOINED,
            EventType.EVT_NODE_LEFT,
            EventType.EVT_NODE_FAILED
        )
        if (clusterHost!="") config.localHost = clusterHost

        // https://ignite.apache.org/docs/2.9.1/security/authentication
        //val storageConfig = DataStorageConfiguration()
        //storageConfig.defaultDataRegionConfiguration.isPersistenceEnabled = true
        //config.dataStorageConfiguration = storageConfig
        //config.isAuthenticationEnabled = false

        IgniteClusterManager(config)
    }

    private fun getHazelcastClusterManager() = try {
        val fileName = "hazelcast.yaml"
        val clusterConfig = FileSystemYamlConfig(fileName)
        logger.info("Cluster config file [{}]", fileName)
        HazelcastClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration.")
        HazelcastClusterManager()
    }
}
