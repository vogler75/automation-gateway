package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.graphql.GraphQLServer
import at.rocworks.gateway.core.mqtt.MqttDriver
import at.rocworks.gateway.core.mqtt.MqttLogger
import at.rocworks.gateway.core.mqtt.MqttServer
import at.rocworks.gateway.core.opcua.OpcUaDriver
import at.rocworks.gateway.core.opcua.OpcUaServer
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject

abstract class Component(val config: JsonObject) : AbstractVerticle() {
    enum class ComponentStatus {
        None, Enabled, Disabled
    }

    enum class ComponentGroup {
        None, Server, Driver, Logger
    }

    enum class ComponentType {
        None,
        GraphQLServer, MqttServer, OpcUaServer,
        OpcUaDriver, MqttDriver, Plc4xDriver,
        InfluxDBLogger,
        IoTDBLogger,
        JdbcLogger,
        KafkaLogger,
        MqttLogger,
        Neo4jLogger,
        OpenSearchLogger,
        ImplyLogger
    }

    fun getComponentType() : ComponentType = ComponentType.valueOf(this.javaClass.simpleName)
    abstract fun getComponentGroup(): ComponentGroup
    abstract fun getComponentId(): String
    abstract fun getComponentConfig(): JsonObject

    fun getComponentName() = this.javaClass.simpleName+(if (getComponentId().isNotEmpty()) "/${getComponentId()}" else "")

    open fun getComponentStatus(): ComponentStatus = ComponentStatus.None

    override fun start() {
        super.start()
        ComponentHandler.componentStarted(this) // TODO: do this via message bus?
    }

    override fun stop() {
        super.stop()
        ComponentHandler.componentStopped(this) // TODO: do this via message bus?
    }

    companion object {
        fun defaultFactory(type: Component.ComponentType, config: JsonObject): Component? {
            return when (type) {
                ComponentType.GraphQLServer  -> GraphQLServer(config)
                ComponentType.OpcUaServer    -> OpcUaServer(config)
                ComponentType.OpcUaDriver    -> OpcUaDriver(config)
                ComponentType.MqttServer     -> MqttServer(config)
                ComponentType.MqttDriver     -> MqttDriver(config)
                ComponentType.MqttLogger     -> MqttLogger(config)
                else -> null
            }
        }
    }
}