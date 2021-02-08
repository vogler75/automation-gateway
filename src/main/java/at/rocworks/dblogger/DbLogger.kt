package at.rocworks.dblogger

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

class DbLogger {
    companion object {
        fun create(vertx: Vertx, config: JsonObject) {
            val logger = LoggerFactory.getLogger(javaClass.simpleName)
            when (val type = config.getString("Type")) {
                "InfluxDB" -> vertx.deployVerticle(InfluxDBLogger(config))
                else -> logger.error("Unknown database type [{}]", type)
            }
        }
    }
}