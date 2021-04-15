package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.cache.OpcNode
import at.rocworks.gateway.core.cache.OpcValue
import at.rocworks.gateway.core.cache.OpcValueHistory
import at.rocworks.gateway.core.data.Topic
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.spi.cluster.ClusterManager
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.apache.ignite.IgniteCache
import org.apache.ignite.binary.BinaryObject
import org.apache.ignite.cache.CacheMode
import org.apache.ignite.cache.CacheRebalanceMode
import org.apache.ignite.cache.CacheWriteSynchronizationMode
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.events.CacheEvent
import org.apache.ignite.events.EventType
import org.apache.ignite.lang.IgniteBiPredicate
import org.apache.ignite.lang.IgnitePredicate
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.sql.Timestamp
import java.time.Instant
import java.util.*

object ClusterCache {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private const val cacheName = "SCADA"

    private var manager: ClusterManager? = null

    private var cache: IgniteCache<String, Any>? = null

    private var vertx: Vertx? = null

    private val uuid = UUID.randomUUID().toString()

    private data class System(
        val systemType: Topic.SystemType,
        val systemName: String
    )

    private val systems = Collections.synchronizedList(mutableListOf<System>())

    private const val keepLastSeconds = 10L

    fun init(manager: ClusterManager, vertx: Vertx) {
        /*
          select table_name from information_schema.tables where table_schema='PUBLIC';
          select column_name, data_type, type_name, column_type from information_schema.columns where table_name='OPCVALUES';
         */
        this.vertx = vertx
        if (manager is IgniteClusterManager) {
            if (this.manager == null) {
                this.manager = manager
                this.cache = getOrCreateCache(manager)
                vertx.setPeriodic(1000) { purgeHistory() }

                vertx.eventBus().consumer<OpcNode>("$uuid/putOpcNode") {
                    putOpcNodeWorker(it)
                }
                vertx.eventBus().consumer<OpcValue>("$uuid/putOpcValue") {
                    putOpcValueWorker(it)
                }
            } else {
                logger.warn("Cluster cache was already initialized!")
            }
        }
    }

    private fun getOrCreateCache(manager: IgniteClusterManager): IgniteCache<String, Any> {
        val config = CacheConfiguration<String, Any>()
        config.name = cacheName
        config.sqlIndexMaxInlineSize = 1000 // TODO: should be configurable
        config.cacheMode = CacheMode.PARTITIONED
        config.backups = 1
        config.rebalanceMode = CacheRebalanceMode.ASYNC
        config.writeSynchronizationMode = CacheWriteSynchronizationMode.FULL_ASYNC
        config.setIndexedTypes(
            String::class.java, OpcNode::class.java,
            String::class.java, OpcValue::class.java,
            String::class.java, OpcValueHistory::class.java
        )
        return manager.igniteInstance.getOrCreateCache(config)
    }

    fun isEnabled() = cache != null

    fun putOpcNode(value: () -> OpcNode) {
        if (vertx!=null && cache!=null) {
            vertx?.eventBus()?.publish("$uuid/putOpcNode", value())
        }
    }

    private fun putOpcNodeWorker(value: Message<OpcNode>) {
        cache?.let {
            val node = value.body()
            it.put(node.key(), node)
        }
    }

    fun putOpcValue(value: () -> OpcValue) {
        if (vertx!=null && cache!=null) {
            vertx?.eventBus()?.publish("$uuid/putOpcValue", value())
        }
    }

    private fun putOpcValueWorker(value: Message<OpcValue>) {
        cache?.let {
            val current = value.body()
            it.put(current.key(), current)

            val history = OpcValueHistory(current)
            it.put(history.key(), history)
        }
    }

    private fun purgeHistory() {
        systems.forEach { system ->
            cache?.let {
                logger.debug("Purge [{}]",system.systemName)
                try {
                    val sql = "DELETE FROM ${OpcValueHistory::class.java.simpleName} " +
                              "WHERE systemName = ? AND sourceTime < ?"
                    val query = SqlFieldsQuery(sql).setArgs(
                        system.systemName,
                        Timestamp.from(Instant.now().minusSeconds(keepLastSeconds))
                    )
                    it.query(query).all.forEach { result ->
                        logger.debug("Purge returned [$result]")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun registerSystem(systemType: Topic.SystemType, systemName: String) {
        val manager = this.manager
        if (manager is IgniteClusterManager) {

            // This optional local callback is called for each event notification that passed remote predicate listener.
            val localListener: IgniteBiPredicate<UUID, CacheEvent> = IgniteBiPredicate { _, e ->
                try {
                    if (e.hasNewValue()) {
                        when (val value = e.newValue()) {
                            is BinaryObject -> {
                                when (value.type().typeName()) {
                                    OpcNode::class.qualifiedName ->
                                        handleOpcNodeChange(value.deserialize(), systemType, systemName)
                                    OpcValue::class.qualifiedName ->
                                        handleOpcValueChange(value.deserialize(), systemType, systemName)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                true
            }

            val remoteFilter =
                IgnitePredicate<CacheEvent> { e ->
                    try {
                        when (val value = e.newValue()) {
                            is BinaryObject -> {
                                when (value.type().typeName()) {
                                    OpcNode::class.qualifiedName -> {
                                        (value.field<Boolean>("subscribe") != null)
                                    }
                                    OpcValue::class.qualifiedName -> {
                                        (value.field<String>("updateValue") != null &&
                                                value.field<String>("systemType") == systemType.name &&
                                                value.field<String>("systemName") == systemName)
                                    }
                                    else -> false
                                }
                            }
                            else -> false
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }

            logger.info("Register [{}] [{}] [{}]", systemType.name, systemName, Thread.currentThread())
            try {
                systems.add(System(systemType, systemName))

                // Subscribe to specified cache events on all nodes that have cache running.
                manager.igniteInstance.let {
                    val group = it.cluster().forCacheNodes(cache?.name)
                    it.events(group).remoteListen<CacheEvent>(
                        localListener, remoteFilter,
                        EventType.EVT_CACHE_OBJECT_PUT
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleOpcNodeChange(
        opcNode: OpcNode,
        systemType: Topic.SystemType,
        systemName: String
    ) {
        if (opcNode.subscribe != null) {
            val topic = Topic.parseTopic("${systemType.name}/$systemName/node/${opcNode.nodeId}")
            val data = JsonObject()
                .put("ClientId", uuid)
                .put("Topic", topic.encodeToJson())
            val action = if (opcNode.subscribe) "Subscribe" else "Unsubscribe"
            vertx?.eventBus()?.publish("${systemType.name}/$systemName/$action", data)
        }
    }

    private fun handleOpcValueChange(
        opcValue: OpcValue,
        systemType: Topic.SystemType,
        systemName: String
    ) {
        if (opcValue.updateValue != null) {
            val data = JsonObject()
                .put("NodeId", opcValue.nodeId)
                .put("Value", opcValue.updateValue)
            vertx?.eventBus()?.publish("${systemType.name}/$systemName/Write", data)
        }
    }

    private fun commonCacheTest(clusterManager: ClusterManager) {
        // Clustered Map
        val map: MutableMap<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map
        map["test"] = "test"
    }
}