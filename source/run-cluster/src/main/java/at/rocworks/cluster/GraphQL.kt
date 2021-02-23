package at.rocworks.cluster

import at.rocworks.graphql.GraphQLServer
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
//import io.vertx.spi.cluster.ignite.IgniteClusterManager

object GraphQL {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.setup(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        // Start at.rocworks.cluster.GraphQL Server
        config.getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                GraphQLServer.create(vertx, it, "default")
            }
    }
}