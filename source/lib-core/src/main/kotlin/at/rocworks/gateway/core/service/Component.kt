package at.rocworks.gateway.core.service

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
        GraphQLServer, MqttServer,
        OpcUaDriver, MqttDriver, Plc4xDriver,
        InfluxDBLogger, IoTDBLogger, JdbcLogger, KafkaLogger, MqttLogger
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
}