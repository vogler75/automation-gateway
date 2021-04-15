import at.rocworks.gateway.core.service.Cluster

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object Cache {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.init(args, clientMode = false) { vertx, config -> services(vertx, config) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun services(vertx: Vertx, config: JsonObject) {
    }
}
