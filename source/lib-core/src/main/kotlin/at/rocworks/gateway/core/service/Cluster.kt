package at.rocworks.gateway.core.service

import org.slf4j.LoggerFactory

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject

import java.io.FileNotFoundException

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

object Cluster {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val envClusterHost = System.getenv("GATEWAY_CLUSTER_HOST") ?: ""
    private val envClusterPort = System.getenv("GATEWAY_CLUSTER_PORT") ?: ""
    private val envClientMode = System.getenv("GATEWAY_CLUSTER_CLIENT") ?: ""

    private val clusterType = (System.getenv("GATEWAY_CLUSTER_TYPE") ?: "ignite").toLowerCase()
    private val clusterHost = if (envClusterHost=="*") InetAddress.getLocalHost().hostAddress else envClusterHost
    private val clusterPort = envClusterPort
    private val clientMode = (envClientMode == "true")

    private var clusterManager: ClusterManager? = null

    fun init(args: Array<String>, clientMode: Boolean? = null, services: (Vertx, JsonObject) -> Unit) {
        Common.initLogging()

        logger.info("Cluster type [{}] host [{}] port [{}]", clusterType, clusterHost, clusterPort)

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

        getClusterManager(clientMode ?: this.clientMode).let { clusterManager ->
            this.clusterManager = clusterManager
            val vertxOptions = VertxOptions()
                .setEventBusOptions(eventBusOptions)
                .setClusterManager(clusterManager)

            Vertx.clusteredVertx(vertxOptions).onComplete { result ->
                if (result.succeeded()) {
                    val vertx = result.result()
                    initCluster(clusterManager, vertx)
                    ClusterCache.init(clusterManager, vertx)
                    Common.initVertx(args, vertx, services)
                } else {
                    logger.error("Error initializing cluster!")
                }
            }
        }
    }

    fun getNodeId(): String {
        return clusterManager?.nodeId ?: "?"
    }

    private fun initCluster(clusterManager: ClusterManager, vertx: Vertx) {
        logger.info("Cluster nodeId: ${clusterManager.nodeId}")

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
    }

    private fun getClusterManager(clientMode: Boolean): ClusterManager {
        return when (clusterType) {
            "hazelcast" -> getHazelcastClusterManager()
            "ignite" -> getIgniteClusterManager(clientMode)
            else -> throw IllegalArgumentException("Unknown cluster type '$clusterType'")
        }
    }

    private fun getIgniteClusterManager(clientMode: Boolean) = try {
        val fileName = File("ignite.xml")
        val clusterConfig = URL(fileName.readText())
        logger.info("Cluster config file [{}]", fileName)
        IgniteClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration [{}]", (if (clientMode) "Client" else "Server"))
        val config = IgniteConfiguration()
        config.isClientMode = clientMode
        config.gridLogger = VertxLogger()
        config.metricsLogFrequency = 0
        config.setIncludeEventTypes(
            EventType.EVT_CACHE_OBJECT_PUT,
            EventType.EVT_CACHE_OBJECT_READ,
            EventType.EVT_CACHE_OBJECT_REMOVED,
            EventType.EVT_CACHE_NODES_LEFT,
            EventType.EVT_CLIENT_NODE_DISCONNECTED,
            EventType.EVT_CLIENT_NODE_RECONNECTED,
            EventType.EVT_NODE_JOINED,
            EventType.EVT_NODE_LEFT,
            EventType.EVT_NODE_FAILED
        )
        if (clusterHost!="")
            config.localHost = clusterHost

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
