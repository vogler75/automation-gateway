import at.rocworks.gateway.core.graphql.ConfigServer
import at.rocworks.gateway.core.graphql.GraphQLServer
import at.rocworks.gateway.core.mqtt.MqttDriver
import at.rocworks.gateway.core.mqtt.MqttLogger
import at.rocworks.gateway.core.mqtt.MqttServer
import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaDriver
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.Component
import at.rocworks.gateway.core.service.ComponentHandler

import at.rocworks.gateway.logger.influx.InfluxDBLogger
import at.rocworks.gateway.logger.iotdb.IoTDBLogger
import at.rocworks.gateway.logger.jdbc.JdbcLogger
import at.rocworks.gateway.logger.kafka.KafkaLogger

import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.lang.Exception
import java.util.logging.Logger

object App {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val vertx = Vertx.vertx()
        val logger = Logger.getLogger(javaClass.simpleName)

        fun factory(type: Component.ComponentType, config: JsonObject): Component? {
            return when (type) {
                Component.ComponentType.MqttServer -> MqttServer(config)
                Component.ComponentType.GraphQLServer -> GraphQLServer(config)
                Component.ComponentType.OpcUaDriver -> OpcUaDriver(config)
                Component.ComponentType.MqttDriver -> MqttDriver(config)
                Component.ComponentType.Plc4xDriver -> Plc4xDriver(config)
                Component.ComponentType.MqttLogger -> MqttLogger(config)
                Component.ComponentType.KafkaLogger -> KafkaLogger(config)
                Component.ComponentType.JdbcLogger -> JdbcLogger(config)
                Component.ComponentType.InfluxDBLogger -> InfluxDBLogger(config)
                Component.ComponentType.IoTDBLogger -> IoTDBLogger(config)
                else -> {
                    logger.severe("Unknown component type [${type}]")
                    null
                }
            }
        }

        KeyStoreLoader.init()
        Common.initLogging()
        Common.initGateway(args, vertx) { config ->
            val componentHandler = ComponentHandler(vertx, config, ::factory)
            vertx.deployVerticle(ConfigServer(componentHandler))
        }
    }
}