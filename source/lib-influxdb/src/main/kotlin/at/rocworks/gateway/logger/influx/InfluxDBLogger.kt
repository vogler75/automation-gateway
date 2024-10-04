package at.rocworks.gateway.logger.influx

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.json.JsonObject

class InfluxDBLogger {
    companion object {
        fun create(config: JsonObject): LoggerBase {
            val version = config.getInteger("Version")
            return when (version) {
                1 -> InfluxDBLoggerV1(config)
                2 -> InfluxDBLoggerV2(config)
                else -> throw IllegalArgumentException("Unknown InfluxDBLogger version: $version")
            }
        }
    }
}