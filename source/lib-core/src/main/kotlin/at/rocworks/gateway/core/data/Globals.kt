package at.rocworks.gateway.core.data

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object Globals {
    const val BUS_ROOT_URI_LOG = "Log"

    fun retrieveConfig(vertx: Vertx, configFilePath: String): ConfigRetriever = ConfigRetriever.create(
        vertx,
        ConfigRetrieverOptions().addStore(
            ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(JsonObject().put("path", configFilePath))
        )
    )
}