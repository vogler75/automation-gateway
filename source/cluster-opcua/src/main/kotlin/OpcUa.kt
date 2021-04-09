import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaVerticle
import at.rocworks.gateway.core.service.ClusterHandler

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

object OpcUa {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        KeyStoreLoader.init()
        ClusterHandler.init(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonArray("OpcUaClient", JsonArray())
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
            .forEach {
                vertx.deployVerticle(OpcUaVerticle(it))
            }
    }
}
