package at.rocworks.gateway.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.eventbus.EventBusOptions

import io.vertx.core.spi.cluster.ClusterManager
import io.vertx.core.spi.cluster.NodeListener

import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager

import java.io.File
import java.io.FileNotFoundException

import java.net.InetAddress
import java.net.URL

import com.hazelcast.config.FileSystemYamlConfig

object Cluster {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val cfgClusterType = (System.getenv("GATEWAY_CLUSTER_TYPE") ?: "hazelcast").toLowerCase()

    var clusterManager: ClusterManager? = null

    fun init(args: Array<String>, services: (Vertx, JsonObject) -> Unit) {
        Common.initLogging()

        logger.info("Cluster type [{}]", cfgClusterType)

        val eventBusOptions = EventBusOptions()

        getMyClusterManager().let { clusterManager ->
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

    private fun getMyClusterManager(): ClusterManager {
        return when (cfgClusterType) {
            "hazelcast" -> getHazelcastClusterManager()
            else -> throw IllegalArgumentException("Unknown cluster type '$cfgClusterType'")
        }
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
