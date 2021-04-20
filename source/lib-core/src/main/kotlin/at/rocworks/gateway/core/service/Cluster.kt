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
import org.apache.ignite.configuration.DeploymentMode
import java.net.InetAddress

object Cluster {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val cfgClusterType = (System.getenv("GATEWAY_CLUSTER_TYPE") ?: "hazelcast").toLowerCase()

    private val envIgniteClusterHost = System.getenv("IGNITE_CLUSTER_HOST") ?: ""
    private val envIgniteClusterPort = System.getenv("IGNITE_CLUSTER_PORT") ?: ""
    private val envIgniteClientMode = System.getenv("IGNITE_CLIENT_MODE") ?: ""
    private val envIgnitePeerClassLoading = System.getenv("IGNITE_PEER_CLASS_LOADING") ?: "TRUE"
    private val envIgniteDeploymentMode = System.getenv("IGNITE_DEPLOYMENT_MODE") ?: "SHARED"

    private val cfgIgniteClusterHost = if (envIgniteClusterHost=="*") InetAddress.getLocalHost().hostAddress else envIgniteClusterHost
    private val cfgIgniteClusterPort = envIgniteClusterPort
    private val cfgIgniteClientMode = (envIgniteClientMode.toUpperCase() == "TRUE")
    private val cfgIgnitePeerClassLoading = (envIgnitePeerClassLoading.toUpperCase() == "TRUE")
    private val cfgIgniteDeploymentMode = when (envIgniteDeploymentMode.toUpperCase()) {
        "PRIVATE" -> DeploymentMode.PRIVATE
        "ISOLATED" -> DeploymentMode.ISOLATED
        "SHARED" -> DeploymentMode.SHARED
        "CONTINUOUS" -> DeploymentMode.CONTINUOUS
        else -> DeploymentMode.PRIVATE
    }

    var clusterManager: ClusterManager? = null

    fun init(args: Array<String>, clientMode: Boolean? = null, services: (Vertx, JsonObject) -> Unit) {
        Common.initLogging()

        logger.info("Cluster type [{}] host [{}] port [{}]", cfgClusterType, cfgIgniteClusterHost, cfgIgniteClusterPort)

        val eventBusOptions = EventBusOptions()
        if (cfgIgniteClusterHost!="") {
            logger.info("Ignite cluster host [{}]", cfgIgniteClusterHost)
            eventBusOptions.host = cfgIgniteClusterHost
            eventBusOptions.clusterPublicHost = cfgIgniteClusterHost
        }

        if (cfgIgniteClusterPort!="") {
            logger.info("Ignite cluster port to [{}]", cfgIgniteClusterPort)
            eventBusOptions.port = cfgIgniteClusterPort.toInt()
            eventBusOptions.clusterPublicPort = cfgIgniteClusterPort.toInt()
        }

        getClusterManager(clientMode ?: this.cfgIgniteClientMode).let { clusterManager ->
            this.clusterManager = clusterManager
            val vertxOptions = VertxOptions()
                .setEventBusOptions(eventBusOptions)
                .setClusterManager(clusterManager)

            Vertx.clusteredVertx(vertxOptions).onComplete { result ->
                if (result.succeeded()) {
                    val vertx = result.result()
                    initCluster(clusterManager, vertx)
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
        return when (cfgClusterType) {
            "hazelcast" -> getHazelcastClusterManager()
            "ignite" -> getIgniteClusterManager()
            else -> throw IllegalArgumentException("Unknown cluster type '$cfgClusterType'")
        }
    }

    private fun getIgniteClusterManager() = try {
        val fileName = File("ignite.xml")
        val clusterConfig = URL(fileName.readText())
        logger.info("Cluster config file [{}]", fileName)
        IgniteClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration [{}]", (if (cfgIgniteClientMode) "Client" else "Server"))
        val config = IgniteConfiguration()

        config.isClientMode = cfgIgniteClientMode

        config.gridLogger = VertxLogger()
        config.metricsLogFrequency = 0

        logger.info("PeerClassLoading [{}] DeploymentMode [{}]", cfgIgnitePeerClassLoading, cfgIgniteDeploymentMode.toString())
        config.isPeerClassLoadingEnabled = cfgIgnitePeerClassLoading;
        config.deploymentMode = cfgIgniteDeploymentMode

        config.setIncludeEventTypes(
            EventType.EVT_CACHE_OBJECT_PUT,
            EventType.EVT_CACHE_OBJECT_READ,
            EventType.EVT_CACHE_OBJECT_REMOVED,
            EventType.EVT_NODE_JOINED,
            EventType.EVT_NODE_LEFT,
            EventType.EVT_NODE_FAILED,
            EventType.EVT_CLIENT_NODE_DISCONNECTED,
            EventType.EVT_CLIENT_NODE_RECONNECTED,
        )

        if (cfgIgniteClusterHost!="")
            config.localHost = cfgIgniteClusterHost

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
