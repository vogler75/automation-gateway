import at.rocworks.gateway.cluster.Cluster
import at.rocworks.gateway.graphql.GraphQLServer

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object GraphQL {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.setup(args) { vertx, config -> services(vertx, config) }
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("GraphQLServer")
            ?.getJsonArray("Listeners")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                GraphQLServer.create(vertx, it, "default")
            }
    }
}