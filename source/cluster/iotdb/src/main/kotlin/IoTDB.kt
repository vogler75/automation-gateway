import at.rocworks.gateway.core.service.Cluster
import at.rocworks.gateway.logger.iotdb.IoTDBLogger

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object IoTDB {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.init(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("Database")
            ?.getJsonArray("Logger")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled") && it.getString("Type") == "IoTDB"}
            ?.forEach {
                vertx.deployVerticle(IoTDBLogger(it))
            }
    }
}
