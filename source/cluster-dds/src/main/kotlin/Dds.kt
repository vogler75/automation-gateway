import at.rocworks.gateway.core.service.ClusterHandler
import at.rocworks.gateway.core.opcua.OpcUaVerticle

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

object Dds {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        ClusterHandler.setup(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("DDS", JsonObject())
            .getJsonArray("Domains", JsonArray())
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
            .forEach {
                vertx.deployVerticle(DdsVerticle(it))
            }
    }
}
