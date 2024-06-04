package at.rocworks.gateway.logger.zenoh

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerPublisher
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject

import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.keyexpr.intoKeyExpr
import io.zenoh.prelude.Encoding
import io.zenoh.prelude.KnownEncoding
import io.zenoh.publication.Publisher
import io.zenoh.value.Value

class ZenohLogger(config: JsonObject) : LoggerPublisher(config) {
    private val baseKey = config.getString("Key", "gateway")

    private var session : Session? = null
    private val publisher = mutableMapOf<String, Publisher>()

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            val config = Config.default()
            Session.open(config).onSuccess { it ->
                session = it
                logger.info("Zenoh connected.")
                result.complete()
            }.onFailure {
                logger.severe("Zenoh connect failed! [${it.message}]", )
                result.fail(it)
            }
        } catch (e: Exception) {
            logger.severe("Zenoh connect failed! [${e.message}]", )
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        session?.close()
        promise.complete()
        return promise.future()
    }

    override fun publish(topic: Topic, payload: Buffer) {
        val key = (baseKey ?: topic.systemName) + "/" + topic.getBrowsePathOrNode().toString()
        publishKey(key, payload)
    }

    override fun publish(topics: List<Topic>, payload: Buffer) {
        val key = baseKey
        publishKey(key, payload)
    }

    private fun publishKey(key: String, payload: Buffer) {
        session?.let { session ->
            publisher[key]?.let { pub ->
                pub.put(Value(payload.bytes, Encoding(KnownEncoding.default()))).res()
                valueCounterOutput++
            } ?: run {
                key.intoKeyExpr().onSuccess { keyExpr ->
                    keyExpr.use {
                        session.declarePublisher(keyExpr).res().onSuccess { pub ->
                            publisher[key] = pub
                            pub.put(Value(payload.bytes, Encoding(KnownEncoding.default()))).res()
                            valueCounterOutput++
                        }
                    }
                }
            }
        }
    }
}