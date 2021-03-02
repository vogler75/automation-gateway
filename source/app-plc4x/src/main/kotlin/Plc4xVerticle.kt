import at.rocworks.gateway.core.data.Globals
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import org.apache.plc4x.java.api.PlcConnection
import org.apache.plc4x.java.PlcDriverManager
import org.apache.plc4x.java.api.exceptions.PlcConnectionException
import org.apache.plc4x.java.api.messages.PlcReadRequest
import org.apache.plc4x.java.api.messages.PlcWriteRequest

class Plc4xVerticle(config: JsonObject): DriverBase(config) {
    override fun getRootUri() = Globals.BUS_ROOT_URI_PLC

    private val url: String = config.getString("Url", "")
    private var plc: PlcConnection? = null

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            plc = PlcDriverManager().getConnection(url)
            plc!!.connect()
            promise.complete(true)
        } catch (e: PlcConnectionException) {
            logger.error(e.message)
            promise.complete(false)
        }

        if (plc != null) {
            val info = (if (plc?.metadata?.canRead() == true) "Read " else " ") +
                    (if (plc?.metadata?.canWrite() == true) "Write " else " ") +
                    if (plc?.metadata?.canSubscribe() == true) "Subscribe " else " "
            logger.error("This connection supports: {}", info)
        }
        return promise.future()
    }

    override fun disconnect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        if (plc != null && plc?.isConnected == true) {
            plc?.close()
            promise.complete(true)
        } else {
            promise.complete(false)
        }
        return promise.future()
    }

    override fun shutdown() {
        disconnect()
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        TODO("Not yet implemented")
    }

    override fun unsubscribeItems(items: List<MonitoredItem>): Future<Boolean> {
        TODO("Not yet implemented")
    }

    override fun writeTopicValue(topic: Topic, value: Buffer): Future<Boolean> {
        TODO("Not yet implemented")
    }

    override fun readServerInfo(): JsonObject {
        TODO("Not yet implemented")
    }

    override fun readHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        when {
            plc?.metadata?.canRead() == false -> {
                message.reply(JsonObject().put("Ok", false).put("Error", "Read not supported!"))
            }
            node != null && node is String -> {
                try {
                    val builder: PlcReadRequest.Builder = plc!!.readRequestBuilder()
                    builder.addItem("value-$node", node)
                    val request = builder.build()
                    val response = request.execute()
                    response.whenComplete { readResponse, throwable ->
                        if (readResponse != null) {
                            val value = readResponse.getAllBytes("value-$node")
                            val result = JsonObject().put("Value", value)
                            message.reply(JsonObject().put("Ok", true).put("Result", result))
                        } else {
                            logger.error("An error occurred: " + throwable.message, throwable)
                            val result = JsonObject().put("Value", throwable.message)
                            message.reply(JsonObject().put("Ok", false).put("Result", result))
                        }
                    }
                } catch (e: Exception)  {
                    val result = JsonObject().put("Value", e.message)
                    message.reply(JsonObject().put("Ok", false).put("Result", result))
                }
            }
            node != null && node is JsonArray -> {
                message.reply(JsonObject().put("Ok", false))
            }
            else -> {
                val err = String.format("Invalid format in read request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        logger.info("writeHandler [{}]", node)
        when {
            node != null && node is String -> {
                try {
                    val value = message.body().getString("Value", "")
                    val builder: PlcWriteRequest.Builder = plc!!.writeRequestBuilder()
                    builder.addItem("value", node, value)
                    val request = builder.build()
                    val response = request.execute()
                    response.whenComplete { writeResponse, throwable ->
                        if (writeResponse != null) {
                            logger.info("Write response [{}]", writeResponse.getResponseCode("value"))
                            message.reply(JsonObject().put("Ok", true))
                        } else {
                            logger.error("An error occurred: " + throwable.message, throwable)
                            message.reply(JsonObject().put("Ok", false))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            node != null && node is JsonArray -> {

            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    override fun browseHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }
}