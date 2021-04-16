import at.rocworks.gateway.core.mqtt.MqttVerticle
import at.rocworks.gateway.core.opcua.OpcUaVerticle
import at.rocworks.gateway.core.service.Cluster

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

object Cache {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.init(args, clientMode = false) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonArray("Cache", JsonArray())
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
            .forEach {
                vertx.deployVerticle(CacheVerticle(it))
            }
    }
}
