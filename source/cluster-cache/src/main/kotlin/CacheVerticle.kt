import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.apache.ignite.IgniteCache
import org.apache.ignite.binary.BinaryObject
import org.apache.ignite.cache.CacheMode
import org.apache.ignite.cache.CacheRebalanceMode
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.events.CacheEvent
import org.apache.ignite.events.EventType
import org.apache.ignite.lang.IgniteBiPredicate
import org.apache.ignite.lang.IgnitePredicate
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

import at.rocworks.gateway.cache.OpcNode
import at.rocworks.gateway.cache.OpcValue
import at.rocworks.gateway.cache.OpcValueHistory
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.service.Cluster
import at.rocworks.gateway.core.service.ServiceHandler
import io.vertx.core.eventbus.Message
import io.vertx.servicediscovery.Record

class CacheVerticle(private val config: JsonObject) : AbstractVerticle() {
    private val id = config.getString("Id", "Cache")
    private val uuid = UUID.randomUUID().toString()
    private val logger = LoggerFactory.getLogger(id)
    private val cacheName = id.toUpperCase()
    private val systemsAsJson = config.getJsonArray("Systems", JsonArray()) ?: JsonArray()
    private val sqlIndexMaxInlineSize = config.getInteger("SqlIndexMaxInlineSize", 1000)
    private val storeHistoryValues = config.getBoolean("StoreHistoryValues", false)
    private val cacheBackups = config.getInteger("CacheBackups", 0)
    private val cacheMode = when (config.getString("CacheMode", "PARTITIONED").toUpperCase()) {
        "PARTITIONED" -> CacheMode.PARTITIONED
        "REPLICATED" -> CacheMode.REPLICATED
        "LOCAL" -> CacheMode.LOCAL
        else -> CacheMode.PARTITIONED
    }

    private val systems = systemsAsJson.filterIsInstance<JsonObject>().map {
        System(
            systemType = Topic.SystemType.valueOf(it.getString("SystemType")),
            systemName = it.getString("SystemName"),
            keepLastSeconds = it.getLong("KeepLastSeconds", 0),
            purgeEverySeconds = it.getLong("PurgeEverySeconds", 0)
        )
    }

    private val topics : List<Topic> = config
        .getJsonArray("Logging")
        ?.asSequence()
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { it.getString("Topic") }
        ?.map { Topic.parseTopic(it) }
        ?.filter { it.format == Topic.Format.Json }
        ?.toList()
        ?:listOf()

    private val services = topics.map { Pair(it.systemType, it.systemName) }.distinct()

    private data class System(
        val systemType: Topic.SystemType,
        val systemName: String,
        val keepLastSeconds: Long,
        val purgeEverySeconds: Long
    )

    private var clusterManager: IgniteClusterManager? = null
    private var cache: IgniteCache<String, Any>? = null

    init {
        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
    }

    override fun start(startPromise: Promise<Void>) {
        val manager = Cluster.clusterManager
        if (manager is IgniteClusterManager) {
            if (this.clusterManager == null) {
                this.clusterManager = manager
                cache = getOrCreateCache(manager)
                systems.forEach { system ->
                    startPurger(system)
                    startObserver(system)
                    //startListener(system) TODO: Does not really work well...
                }
            } else {
                logger.warn("Cluster cache was already initialized!")
            }
            startPromise.complete()
        } else {
            startPromise.fail("This cache only works with Apache Ignite Cluster Manager!")
        }
    }

    private fun getOrCreateCache(manager: IgniteClusterManager): IgniteCache<String, Any> {
        val config = CacheConfiguration<String, Any>()
        config.name = cacheName
        config.sqlIndexMaxInlineSize = sqlIndexMaxInlineSize // TODO: should be configurable
        config.cacheMode = cacheMode
        config.backups = cacheBackups
        config.rebalanceMode = CacheRebalanceMode.ASYNC
        //config.writeSynchronizationMode = CacheWriteSynchronizationMode.FULL_ASYNC
        logger.info("Create cache")
        config.setIndexedTypes(
            String::class.java, OpcNode::class.java,
            String::class.java, OpcValue::class.java,
            String::class.java, OpcValueHistory::class.java
        )
        return manager.igniteInstance.getOrCreateCache(config)
    }

    private fun startPurger(system: System) {
        if (system.purgeEverySeconds > 0) {
            var running = false
            vertx.setPeriodic(system.purgeEverySeconds * 1000L) {
                if (!running) {
                    running = true
                    vertx.executeBlocking<Void> {
                        purgeHistory(system)
                        it.complete()
                    }.onComplete {
                        running = false
                    }
                }
            }
        }
    }

    private fun startObserver(system: System) {
        ServiceHandler(vertx, logger).observeService(system.systemType.name, system.systemName) { service ->
            if (service.status == Status.UP) {
                fetchSchema(service)
                subscribeTopics(service)
            }
        }
    }

    private fun fetchSchema(service: Record) {
        logger.info("Request schema [{}]...", service.name)
        val systemType = service.type
        val systemName = service.name
        vertx.eventBus().request<JsonObject>(
            "${systemType}/${systemName}/Schema",
            JsonObject(),
            DeliveryOptions().setSendTimeout(60000*3))
        {
            logger.info("Schema response [{}] [{}] [{}]", systemName, it.succeeded(), it.cause()?.message ?: "")
            if (it.succeeded()) {
                val response = it.result()?.body() ?: JsonObject()
                vertx.executeBlocking<Void> {  promise ->
                    updateSchema(systemName, response.getJsonArray("Objects", JsonArray()) ?: JsonArray())
                    promise.complete()
                }
            }
        }
    }

    private fun updateSchema(system: String, tree: JsonArray) {
        fun add(parentNodeId: String, path: String, node: JsonObject) {
            val browseName = node.getString("BrowseName", "")
            val browsePath = "$path/$browseName"
            val nodeId = node.getString("NodeId", "")
            val data = OpcNode(
                systemName = system,
                nodeId = nodeId,
                nodeClass = node.getString("NodeClass", ""),
                browsePath = browsePath,
                parentNodeId = parentNodeId,
                browseName = browseName,
                displayName = node.getString("DisplayName", ""),
            )
            cache?.put(data.key(), data)

            node.getJsonArray("Nodes")?.filterIsInstance<JsonObject>()?.forEach { it ->
                add(nodeId, browsePath, it)
            }
        }
        tree.filterIsInstance<JsonObject>().forEach { add("", "Root/Objects", it) }
    }

    private fun subscribeTopics(service: Record) {
        topics
            .filter { it.systemType.name == service.type && it.systemName == service.name }
            .forEach { topic ->
                vertx.eventBus().consumer<Any>(topic.topicName, ::valueConsumer)
                subscribeTopic(ServiceHandler.endpointOf(service), topic)
            }
    }

    private fun subscribeTopic(endpoint: String, topic: Topic) { // TODO: same function in influx
        val request = JsonObject().put("ClientId", this.id).put("Topic", topic.encodeToJson())
        if (endpoint!="") {
            logger.info("Subscribe to endpoint [{}]", endpoint)
            vertx.eventBus().request<JsonObject>("${endpoint}/Subscribe", request) {
                logger.debug("Subscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
            }
        }
    }

    private fun valueConsumer(message: Message<Any>) { // TODO: same function in influx
        try {
            when (val value = message.body()) {
                is Buffer -> valueConsumer(Json.decodeValue(value) as JsonObject)
                is JsonObject -> valueConsumer(value)
                else -> logger.warn("Got unhandled class of instance []", value.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }

    private fun valueConsumer(data: JsonObject) {
        try {
            val topic = Topic.decodeFromJson(data.getJsonObject("Topic"))
            val value = TopicValue.fromJsonObject(data.getJsonObject("Value"))
            if (!value.hasValue()) return

            val current = OpcValue(topic, value)
            cache?.put(current.key(), current)

            if (storeHistoryValues) {
                val history = OpcValueHistory(current)
                cache?.put(history.key(), history)
            }

        } catch (e: IllegalStateException) {
            logger.error(e.message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun purgeHistory(system: System) {
        cache?.let {
            try {
                val sql = "DELETE FROM ${OpcValueHistory::class.java.simpleName} " +
                        "WHERE systemName = ? AND sourceTime < ?"
                val query = SqlFieldsQuery(sql).setArgs(
                    system.systemName,
                    Timestamp.from(Instant.now().minusSeconds(system.keepLastSeconds))
                )
                it.query(query).all.forEach { result ->
                    logger.debug("Purge returned [$result]")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startListener(system: System) {
        val manager = clusterManager
        if (manager is IgniteClusterManager) {
            try {
                logger.info("Listener [{}] [{}] [{}]", system.systemType.name, system.systemName, Thread.currentThread())

                val localListener = localListener(system.systemType.name, system.systemName)
                val remoteFilter = remoteFilter(system.systemType.name, system.systemName)

                // Subscribe to specified cache events on all nodes that have cache running.
                // TODO: we only get events from the local cache, but we should get it from any node...
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

    private fun remoteFilter(systemType: String, systemName: String) = IgnitePredicate<CacheEvent> { e ->
        try {
            logger.debug("remoteFilter: $e")
            when (val value = e.newValue()) {
                is BinaryObject -> {
                    when (value.type().typeName()) {
                        OpcNode::class.qualifiedName -> {
                            (value.field<Boolean>("subscribe") != null &&
                                    value.field<String>("systemType") == systemType &&
                                    value.field<String>("systemName") == systemName)
                        }
                        OpcValue::class.qualifiedName -> {
                            (value.field<String>("updateValue") != null &&
                                    value.field<String>("systemType") == systemType &&
                                    value.field<String>("systemName") == systemName)
                        }
                        else -> false
                    }
                }
                is OpcNode -> {
                    (value.subscribe != null &&
                            value.systemType == systemType &&
                            value.systemName == systemName)
                }
                else -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun localListener(systemType: String, systemName: String): IgniteBiPredicate<UUID, CacheEvent> =
        IgniteBiPredicate { _, e ->
            try {
                logger.debug("localListener: $e")
                if (e.hasNewValue()) {
                    when (val value = e.newValue()) {
                        is BinaryObject -> {
                            when (value.type().typeName()) {
                                OpcNode::class.qualifiedName -> {
                                    val newNode = value.deserialize<OpcNode>()
                                    val oldNode = (e.oldValue() as? BinaryObject)?.deserialize<OpcNode>()
                                    handleOpcNodeChange(systemType, systemName, newNode, oldNode)
                                }
                                OpcValue::class.qualifiedName -> {
                                    handleOpcValueChange(systemType, systemName, value.deserialize())
                                }
                            }
                        }
                        is OpcNode -> {
                            val oldNode = e.oldValue() as? OpcNode
                            handleOpcNodeChange(systemType, systemName, value, oldNode)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        }

    private fun handleOpcNodeChange(
        systemType: String,
        systemName: String,
        newNode: OpcNode,
        oldNode: OpcNode?
    ) {
        if (newNode.subscribe != null) {
            try {
                logger.info("Opc node has changed! [${systemType}/$systemName/node/${newNode.nodeId}] [${newNode.subscribe}] [${oldNode?.subscribe}]")
                val topic = Topic.parseTopic("${systemType}/$systemName/node/${newNode.nodeId}")
                val data = JsonObject()
                    .put("ClientId", uuid)
                    .put("Topic", topic.encodeToJson())
                if (newNode.subscribe) {
                    vertx?.eventBus()?.consumer<Any>(topic.topicName, ::valueConsumer)

                    /*  TODO: if we do this the record is not in the table anymore ...
                    vertx?.eventBus()?.publish("${systemType}/$systemName/Subscribe", data)
                     */
                } else {
                    // TODO: remove consumer ..
                    vertx?.eventBus()?.publish("${systemType}/$systemName/Unsubscribe", data)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleOpcValueChange(
        systemType: String,
        systemName: String,
        opcValue: OpcValue
    ) {
        if (opcValue.updateValue != null) {
            val data = JsonObject()
                .put("NodeId", opcValue.nodeId)
                .put("Value", opcValue.updateValue)
            vertx?.eventBus()?.publish("${systemType}/$systemName/Write", data)
        }
    }
}