import at.rocworks.gateway.core.service.ClusterHandler
import at.rocworks.gateway.logger.influx.InfluxDBLogger

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object InfluxDB {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        ClusterHandler.init(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("Database")
            ?.getJsonArray("Logger")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled") }
            ?.forEach {
                vertx.deployVerticle(InfluxDBLogger(it))
            }
    }
}
