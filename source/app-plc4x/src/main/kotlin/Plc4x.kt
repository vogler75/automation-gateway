import at.rocworks.gateway.cluster.Cluster
import at.rocworks.gateway.core.data.Globals

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlin.concurrent.thread

object Plc4x {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Cluster.setup(args) { vertx, config -> services(vertx, config) }

        /*
        val vertx = Vertx.vertx()
        val configFilePath = if (args.isNotEmpty()) args[0] else "config.yaml"
        println("Using config file: $configFilePath")
        val config = Globals.RetrieveConfig(vertx, configFilePath)
        config.getConfig { cfg ->
            if (cfg==null || cfg.failed()) {
                println("Missing or invalid $configFilePath file!")
                config.close()
                vertx.close()
            } else {
                thread { // because it will block
                    services(vertx, cfg.result())
                }
            }
        }
        */
    }

    private fun services(vertx: Vertx, config: JsonObject) {
        config.getJsonObject("Plc4x")
            ?.getJsonArray("Drivers")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                Plc4xDriver(it)
            }
    }
}
