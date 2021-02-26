import at.rocworks.gateway.cluster.Cluster
import at.rocworks.gateway.core.mqtt.MqttVerticle
import at.rocworks.gateway.core.opcua.OpcUaHandler

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object Gateway {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.setup(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        // OPC UA Server
        val enabled: List<JsonObject> = config.getJsonArray("OpcUaClient")
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
        enabled.map {
            vertx.deployVerticle(OpcUaHandler(it))
        }

        // Mqtt Server
        config.getJsonObject("MqttServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                MqttVerticle.create(vertx, it)
            }
    }
}