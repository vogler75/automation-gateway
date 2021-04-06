import at.rocworks.gateway.core.service.ClusterHandler

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

object App {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        ClusterHandler.init(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("App", JsonObject())
            .getJsonArray("Module", JsonArray())
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
            .forEach {
                vertx.deployVerticle(AppVerticle(it))
            }
    }
}
