import at.rocworks.gateway.cluster.Cluster
import at.rocworks.gateway.core.opcua.OpcUaVerticle

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object Plc4x {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.setup(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("App")
            ?.getJsonArray("Module")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled") }
            ?.forEach {
                vertx.deployVerticle(AppVerticle(it))
            }
    }
}
