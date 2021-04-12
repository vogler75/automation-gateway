package at.rocworks.gateway.core.service

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import org.slf4j.Logger

class ServiceHandler(val vertx: Vertx, val logger: Logger) {
    private val discovery = ServiceDiscovery.create(vertx)!!
    private val observers : MutableMap<String, (Record)->Unit> = mutableMapOf()

    init {
        onServiceChanged {
            observers[idOf(it)]?.invoke(it)
        }
    }

    fun observeService(type: String, name: String, result: (record: Record)->Unit) {
        observers[idOf(type, name)] = result
        getServiceRecord(type, name).onComplete {
            if (it.result() != null) result(it.result())
        }
    }

    fun unobserveService(type: String, name: String) {
        observers.remove(idOf(type, name))
    }

    fun registerService(type: String, name: String, endpoint: String): Future<Record> {
        val promise = Promise.promise<Record>()
        val record = Record()
            .setType(type)
            .setName(name)
            .setLocation(JsonObject()
                .put("node", ClusterHandler.getNodeId())
                .put("endpoint", endpoint))
        discovery.publish(record, promise)
        return promise.future()
    }

    fun removeClusterNode(nodeId: String) {
        discovery.getRecords({ r -> r.location.getString("node", "") == nodeId}) {
            it.result().forEach { record ->
                logger.info("Remove service [${record.location.getString("endpoint")}] of node [$nodeId]")
                try { discovery.unpublish(record.registration) } catch (e: java.lang.Exception) { e.printStackTrace() }
            }
        }
    }

    companion object {
        fun idOf(record: Record) = "${record.type}/${record.name}"
        fun idOf(type: String, name: String) = "$type/$name"
        fun endpointOf(record: Record) = record.location.getString("endpoint", "")!!
    }

    private fun onServiceChanged(result: (record: Record)->Unit) {
        vertx.eventBus().consumer<JsonObject>(discovery.options().announceAddress) { message ->
            result(Record(message.body()))
        }
    }

    private fun getServiceRecord(type: String, name: String): Future<Record> {
        val promise = Promise.promise<Record>()
        discovery.getRecord({ r -> r.name == name && r.type == type }) { record ->
            if (record.succeeded() && record.result() != null) {
                promise.complete(record.result())
            } else {
                promise.complete(null)
            }
        }
        return promise.future()
    }
}