import at.rocworks.gateway.core.service.ClusterHandler
import at.rocworks.gateway.core.graphql.GraphQLServer
import at.rocworks.gateway.core.mqtt.MqttVerticle
import at.rocworks.gateway.core.opcua.OpcUaVerticle
import at.rocworks.gateway.core.opcua.KeyStoreLoader

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

object Gateway {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        KeyStoreLoader.init()
        ClusterHandler.init(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        // Mqtt Server
        config.getJsonObject("MqttServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                MqttVerticle.create(vertx, it)
            }

        // GraphQL Server
        config.getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                GraphQLServer.create(vertx, it, "default")
            }

        // OPC UA Server
        config.getJsonArray("OpcUaClient", JsonArray())
            .filterIsInstance<JsonObject>()
            .filter { it.getBoolean("Enabled") }
            .forEach {
                vertx.deployVerticle(OpcUaVerticle(it))
            }
    }
}