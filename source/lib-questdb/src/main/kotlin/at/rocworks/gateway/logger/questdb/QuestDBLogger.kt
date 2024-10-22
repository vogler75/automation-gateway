package at.rocworks.gateway.logger.questdb

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject

import io.questdb.client.Sender

/*
CREATE TABLE gateway (
    time timestamp,
    system symbol,
    address symbol,
    value double,
    text varchar
) TIMESTAMP(time) PARTITION BY DAY;

ALTER TABLE gateway DEDUP ENABLE UPSERT KEYS(time, system, address)
*/

class QuestDBLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Config", "http::addr=localhost:9000;")
    private val table = config.getString("Table", "gateway")
    private val autoFlush = config.getBoolean("AutoFlush", false)

    @Volatile
    private var sender : Sender? = null

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            logger.info("QuestDB connect to $url")
            sender = Sender.fromConfig(url)
            logger.info("QuestDB connected.")
            result.complete()
        } catch (e: Exception) {
            logger.severe("QuestDB connect failed! [${e.message}]")
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        sender?.close()
        sender = null
        promise.complete()
        return promise.future()
    }

    override fun isEnabled(): Boolean {
        return sender != null
    }

    override fun writeExecutor() {
        try {
            val size = pollDatapointBlock { point ->
                val address = point.topic.getBrowsePathOrNode().toString()
                val value = point.value.valueAsDouble() ?: Double.NaN
                val text = point.value.stringValue()
                sender?.table(table)
                    ?.symbol("system", point.topic.systemName)
                    ?.symbol("address", address)
                    ?.symbol("status", point.value.statusCode)
                    ?.doubleColumn("value", value)
                    ?.stringColumn("text", text)
                    ?.at(point.value.sourceTime)
            }
            if (size > 0) {
                try {
                    if (!autoFlush) sender?.flush()
                    commitDatapointBlock() // with autoFlush==true it is possible to lose values if the connection gets broken!!
                    valueCounterOutput += size
                } catch (e: Exception) {
                    logger.severe("Error writing batch [${e.message}]")
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            logger.severe("Error writing batch [${e.message}]")
            e.printStackTrace()
        }
    }
}