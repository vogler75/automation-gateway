package at.rocworks.gateway.core.service

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.*
import java.util.logging.Logger

class ComponentHandler(
    private val vertx : Vertx,
    private val config: JsonObject,
    private val factory: (Component.ComponentType, JsonObject) -> Component?)
{
    data class ComponentRecord(
        val id: String,
        val type: Component.ComponentType,
        val config: JsonObject,
        var component: Component?
    ) {
        fun isEnabled(): Boolean = config.getBoolean("Enabled", false)
        val group: Component.ComponentGroup
            get() = when (type) {
                Component.ComponentType.GraphQLServer,
                Component.ComponentType.OpcUaServer,
                Component.ComponentType.MqttServer -> Component.ComponentGroup.Server
                Component.ComponentType.OpcUaDriver,
                Component.ComponentType.MqttDriver,
                Component.ComponentType.Plc4xDriver -> Component.ComponentGroup.Driver
                Component.ComponentType.InfluxDBLogger,
                Component.ComponentType.IoTDBLogger,
                Component.ComponentType.JdbcLogger,
                Component.ComponentType.KafkaLogger,
                Component.ComponentType.MqttLogger,
                Component.ComponentType.Neo4jLogger -> Component.ComponentGroup.Logger
                Component.ComponentType.None -> Component.ComponentGroup.None
            }
    }

    companion object {
        private val logger = Logger.getLogger(ComponentHandler::class.java.simpleName)
        private val components = mutableMapOf<String, ComponentRecord>()

        fun componentStarted(component: Component) {
            logger.info("Component ${component.getComponentName()} started.")
        }

        fun componentStopped(component: Component) {
            logger.info("Component ${component.getComponentName()} stopped.")
        }
    }
    
    init {
        listOf("Drivers", "Servers", "Loggers").forEach { group ->
            config.getJsonObject(group)
                ?.filter { it.value is JsonArray }
                ?.forEach { (type, list) ->
                    (list as JsonArray)
                        .filterIsInstance<JsonObject>()
                        .forEach { config ->
                            val name = type + (group.removeSuffix("s"))
                            val componentType = try {
                                Component.ComponentType.valueOf(name)
                            } catch (e: IllegalArgumentException) {
                                logger.severe("Unknown component type [$name]!")
                                Component.ComponentType.None
                            }
                            createComponent(componentType, config)
                        }
                }
        }
    }

    fun getConfig(): JsonObject {
        val drivers = JsonObject()
        val servers = JsonObject()
        val loggers = JsonObject()

        fun add(group: JsonObject, component: ComponentRecord) {
            val key = component.type.name.removeSuffix(component.group.name)
            if (group.containsKey(key))
                group.getJsonArray(key).add(component.config)
            else
                group.put(key, JsonArray().add(component.config))
        }

        components.forEach { (_, component) ->
            when (component.group) {
                Component.ComponentGroup.Server -> add(servers, component)
                Component.ComponentGroup.Driver -> add(drivers, component)
                Component.ComponentGroup.Logger -> add(loggers, component)
                else -> TODO()
            }
        }

        return JsonObject()
            .put("Drivers", drivers)
            .put("Servers", servers)
            .put("Loggers", loggers)
    }

    fun getComponents(): List<ComponentRecord> {
        return components.map { it.value }
    }

    fun createComponent(type: Component.ComponentType, config: JsonObject) {
        val enabled = config.getBoolean("Enabled", true)
        config.put("Enabled", enabled)

        // Get component id
        val componentId = config.getString("Id", UUID.randomUUID().toString())
        config.put("Id", componentId)

        // Add component
        if (type != Component.ComponentType.None) {
            val component = if (enabled) { factory(type, config) } else null
            val record = ComponentRecord(componentId, type, config, component)
            components["$type/$componentId"] = record
            if (enabled) deployComponent(type, componentId)
        }
    }

    fun deleteComponent(type: Component.ComponentType, id: String) {
        val key = "$type/$id"
        val record = components[key]
        if (record != null) {
            undeployComponent(type, id)
            components.remove(key)
        }
    }

    fun deployComponent(type: Component.ComponentType, id: String) {
        var record = components["$type/$id"]
        if (record != null && record.component != null) {
            val component = record.component!!
            vertx.deployVerticle(component) { result ->
                if (result.succeeded()) {
                    logger.info("Component ${component.getComponentName()} started successfully")
                } else {
                    logger.severe("Failed to stop component: " + result.cause())
                }
            }
        }
    }

    fun undeployComponent(type: Component.ComponentType, id: String) {
        val record = components["$type/$id"]
        if (record != null && record.component != null) {
            vertx.undeploy(record.component!!.deploymentID()) { result ->
                if (result.succeeded()) {
                    logger.info("Component stopped successfully")
                } else {
                    logger.severe("Failed to stop component: " + result.cause())
                }
            }
        }
    }
}