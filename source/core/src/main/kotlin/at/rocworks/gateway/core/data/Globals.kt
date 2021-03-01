package at.rocworks.gateway.core.data

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

object Globals {
    const val BUS_ROOT_URI_OPC = "opc"
    const val BUS_ROOT_URI_PLC = "plc"
    const val BUS_ROOT_URI_LOG = "log"

    fun RetrieveConfig(vertx: Vertx, configFilePath: String)
    = ConfigRetriever.create(
        vertx,
        ConfigRetrieverOptions().addStore(
            ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(JsonObject().put("path", configFilePath))
        )
    )
}