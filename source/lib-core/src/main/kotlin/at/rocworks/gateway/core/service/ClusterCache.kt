package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.cache.OpcNode
import at.rocworks.gateway.core.cache.OpcValue
import io.vertx.core.spi.cluster.ClusterManager
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.apache.ignite.Ignite
import org.apache.ignite.IgniteCache
import org.apache.ignite.binary.BinaryObject
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.events.CacheEvent
import org.apache.ignite.events.EventType
import org.apache.ignite.lang.IgniteBiPredicate
import org.apache.ignite.lang.IgnitePredicate
import java.util.*

object ClusterCache {
    private var manager: ClusterManager? = null
    
    private var cache: IgniteCache<String, Any>? = null

    fun init(manager: ClusterManager) {
        /*
          select table_name from information_schema.tables where table_schema='PUBLIC';
          select column_name, data_type, type_name, column_type from information_schema.columns where table_name='OPCVALUES';
         */
        if (manager is IgniteClusterManager) {
            this.manager = manager
            this.cache = getCache(manager)
        }
    }

    private fun getCache(manager: IgniteClusterManager): IgniteCache<String, Any> {
        val config = CacheConfiguration<String, Any>()
        config.name = "SCADA"
        config.sqlIndexMaxInlineSize = 100
        config.setIndexedTypes(
            String::class.java, OpcNode::class.java,
            String::class.java, OpcValue::class.java
        )
        return manager.igniteInstance.getOrCreateCache(config)
    }

    fun isEnabled() = cache != null

    fun put(key: String, value: Any) {
        cache?.let {
            it.put(key, value)
        }
    }

    fun registerUpdateSourceEvents(systemName: String) {
        val manager = this.manager
        if (manager is IgniteClusterManager) {
            try {
                // This optional local callback is called for each event notification that passed remote predicate listener.
                val localListener: IgniteBiPredicate<UUID, CacheEvent> = IgniteBiPredicate { _, e ->
                    if (e.hasNewValue()) {
                        println("REMOTE LOCAL LISTENER")
                    }
                    true
                }

                val remoteFilter =
                    IgnitePredicate<CacheEvent> { evt ->
                        when (val value = evt.newValue()) {
                            is BinaryObject -> {
                                //val opcValue = value.deserialize<OpcValue>()
                                value.type().typeName() == OpcValue::class.qualifiedName &&
                                value.field<Boolean>("updateSource") == true &&
                                value.field<String>("systemName") == systemName
                            }
                            is OpcValue -> {
                                value.updateSource &&
                                value.systemName == systemName
                            }
                            else -> false
                        }
                    }

                // Subscribe to specified cache events on all nodes that have cache running.
                manager.igniteInstance.let {
                    val group = it.cluster().forCacheNodes(cache?.name)
                    it.events(group).remoteListen<CacheEvent>(
                        localListener, remoteFilter,
                        EventType.EVT_CACHE_OBJECT_PUT
                    )
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun commonCacheTest(clusterManager: ClusterManager) {
        // Clustered Map
        val map: MutableMap<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map
        map["test"] = "test"
    }    
}