import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.Component

import at.rocworks.gateway.logger.influx.InfluxDBLogger
import at.rocworks.gateway.logger.jdbc.JdbcLogger
import at.rocworks.gateway.logger.kafka.KafkaLogger
import at.rocworks.gateway.logger.iotdb.IoTDBLogger
import at.rocworks.gateway.logger.neo4j.Neo4jLogger
//import at.rocworks.gateway.logger.duckdb.DuckDBLogger // it's huge, include it only when needed

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import java.util.logging.Logger

object App {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val vertx = Vertx.vertx()
        val logger = Logger.getLogger(javaClass.simpleName)

        fun factory(type: Component.ComponentType, config: JsonObject): Component? {
            return Component.defaultFactory(type, config) ?: when (type) {
                Component.ComponentType.InfluxDBLogger -> InfluxDBLogger(config)
                Component.ComponentType.IoTDBLogger    -> IoTDBLogger(config)
                Component.ComponentType.KafkaLogger    -> KafkaLogger(config)
                Component.ComponentType.JdbcLogger     -> JdbcLogger(config)
                Component.ComponentType.Neo4jLogger    -> Neo4jLogger(config)
                //Component.ComponentType.DuckDBLogger   -> DuckDBLogger(config) // it's huge, include it only when needed
                else -> {
                    logger.severe("Unknown component type [${type}]")
                    null
                }
            }
        }

        KeyStoreLoader.init()
        Common.initLogging()
        Common.initGateway(args, vertx, ::factory)
    }
}