package at.rocworks.gateway.core.opcua

import at.rocworks.gateway.core.data.Value
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Topic.TopicType
import io.vertx.core.AsyncResult
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem
import org.eclipse.milo.opcua.sdk.client.model.nodes.objects.ServerTypeNode
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.*
import org.eclipse.milo.opcua.stack.core.types.structured.*
import java.lang.Exception
import java.lang.IllegalStateException
import java.lang.NumberFormatException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import kotlin.collections.HashSet
import kotlin.concurrent.thread
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue

class OpcUaHandler(config: JsonObject) : OpcUaVerticle(config) {

    class Registry {
        private val topics = HashSet<Topic>()
        private val topicClients = HashMap<String, HashSet<String>>() // TODO: Thread Safety?
        private val topicMonitoredItems = HashMap<String, ArrayList<UaMonitoredItem>>() // TODO: Thread Safety?

        fun addClient(clientId: String, topic: Topic) : Pair<Int, Boolean> {
            val clients = topicClients.getOrDefault(topic.topicName, HashSet())
            if (clients.size == 0) topicClients[topic.topicName] = clients
            val added = clients.add(clientId)
            return Pair(clients.size, added)
        }

        fun delClient(clientId: String, topic: Topic) : Pair<Int, Boolean> {
            val clients = topicClients.getOrDefault(topic.topicName, HashSet())
            val removed = clients.remove(clientId)
            if (clients.size==0) topicClients.remove(topic.topicName)
            return Pair(clients.size, removed)
        }

        fun addMonitoredItem(item: UaMonitoredItem, topic: Topic) {
            topics.add(topic)
            val items = topicMonitoredItems.getOrDefault(topic.topicName, ArrayList<UaMonitoredItem>())
            if (items.size == 0) topicMonitoredItems[topic.topicName] = items
            items.add(item)
        }

        fun delMonitoredItems(topic: Topic) {
            topicMonitoredItems.remove(topic.topicName)
        }

        fun getTopics(): List<Topic> {
            return topics.toList()
        }

        fun delTopic(topic: Topic) : List<UaMonitoredItem> {
            topics.remove(topic)
            val result = topicMonitoredItems.remove(topic.topicName)
            return result ?: ArrayList<UaMonitoredItem>()
        }
    }

    private val registry = Registry()

    private val monitoringParametersBufferSize : UInteger
    private val monitoringParametersBufferSizeDef = 100

    private val monitoringParametersSamplingInterval : Double
    private val monitoringParametersSamplingIntervalDef = 0.0

    private val monitoringParametersDiscardOldest : Boolean
    private val monitoringParametersDiscardOldestDef = false

    private val writeParameterQueueSize : Int
    private val writeParameterQueueSizeDef = 1000

    private val writeParametersBlockSize : Int
    private val writeParametersBlockSizeDef = 100

    private val writeParametersWithTime : Boolean
    private val writeParametersWithTimeDef = false

    init {
        val monitoringParameters = config.getJsonObject("MonitoringParameters")
        monitoringParametersBufferSize = Unsigned.uint(monitoringParameters?.getInteger("BufferSize", monitoringParametersBufferSizeDef) ?: monitoringParametersBufferSizeDef)
        monitoringParametersSamplingInterval = monitoringParameters?.getDouble("SamplingInterval", monitoringParametersSamplingIntervalDef) ?: monitoringParametersSamplingIntervalDef
        monitoringParametersDiscardOldest = monitoringParameters?.getBoolean("DiscardOldest", monitoringParametersDiscardOldestDef) ?: monitoringParametersDiscardOldestDef
        logger.info("MonitoringParameters: "+
                "BufferSize=$monitoringParametersBufferSize " +
                "SamplingInterval=$monitoringParametersSamplingInterval " +
                "DiscardOldest=$monitoringParametersDiscardOldest ")

        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDef) ?: writeParameterQueueSizeDef
        writeParametersBlockSize = writeParameters?.getInteger("BlockSize", writeParametersBlockSizeDef) ?: writeParametersBlockSizeDef
        writeParametersWithTime = writeParameters?.getBoolean("WithTime", writeParametersWithTimeDef) ?: writeParametersWithTimeDef
        logger.info("WriteParameters: "+
                "QueueSize=$writeParameterQueueSize "+
                "BlockSize=$writeParametersBlockSize "+
                "WithTime=$writeParametersWithTime ")
    }

    val writeGetTime = if (writeParametersWithTime) { -> DateTime.nowNanos() } else { -> null }

    override fun subscribeHandler(message: Message<JsonObject>) {
        val request = message.body()
        val clientId = request.getString("ClientId")
        val tagTopic = Topic.decodeFromJson(request.getJsonObject("Topic"))
        subscribeTopic(clientId, tagTopic).onComplete { result: AsyncResult<Boolean> ->
            if (result.cause() != null) result.cause().printStackTrace()
            message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
        }
    }

    override fun unsubscribeHandler(message: Message<JsonObject>) {
        val request = message.body()
        val clientId = request.getString("ClientId")
        val tagTopics = request.getJsonArray("Topics").map { Topic.decodeFromJson(it as JsonObject) }
        unsubscribeTopics(clientId, tagTopics).onComplete { result: AsyncResult<Boolean> ->
            if (result.cause() != null) result.cause().printStackTrace()
            message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
        }
    }

    override fun publishHandler(message: Message<JsonObject>) {
        val topic = Topic.decodeFromJson(message.body().getJsonObject("Topic"))
        val data = message.body().getBuffer("Data")
        logger.debug("Publish [{}] [{}]", topic.toString(), data.toString())
        try {
            when (topic.topicType) {
                TopicType.NodeId -> {
                    writeTopicValue(topic, data).onComplete { result: AsyncResult<Boolean> ->
                        if (result.cause() != null) result.cause().printStackTrace()
                        message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
                    }
                }
                TopicType.Path -> {
                    // TODO: implement write value with path
                    logger.warn("Item type [{}] not yet implemented!", topic.topicType)
                }
                TopicType.Rpc -> {
                    executeRpc(topic, data).onComplete { result: AsyncResult<Boolean> ->
                        if (result.cause() != null) result.cause().printStackTrace()
                        message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
                    }
                }
                else -> {
                    logger.warn("Item type [{}] not yet implemented!", topic.topicType)
                }
            }
        } catch (e: ExecutionException) {
            e.printStackTrace()
            message.reply(JsonObject().put("Ok", false))
        } catch (e: InterruptedException) {
            e.printStackTrace()
            message.reply(JsonObject().put("Ok", false))
        }
    }

    override fun browseHandler(message: Message<JsonObject>) {
        try {
            val rootNodeId = NodeId.parseOrNull(message.body().getString("NodeId", ""))
            if (rootNodeId != null) {
                val result = browseNode(rootNodeId)
                message.reply(JsonObject().put("Ok", true).put("Result", result))
            } else {
                message.reply(JsonObject().put("Ok", false).put("Result", null))
            }
        } catch (e: Exception) {
            message.fail(-1, e.message)
            e.printStackTrace()
        }
    }

    private fun subscribeTopic(clientId: String, topic: Topic): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            val (count, added) = registry.addClient(clientId, topic)
            logger.debug("Subscribe [{}] [{}]", count, topic)
            if (!added) {
                logger.warn("Client [{}] already subscribed to [{}]", clientId, topic)
                ret.complete(false)
            } else if (count == 1) {
                when {
                    topic.topicType === TopicType.NodeId -> subscribeNodes(listOf(topic)).onComplete(ret)
                    topic.topicType === TopicType.Path -> subscribePath(listOf(topic)).onComplete(ret)
                    topic.topicType === TopicType.Rpc -> ret.complete(true)
                    else -> {
                        logger.warn("Unhandled item type [{}]", topic.topicType)
                        ret.complete(false)
                    }
                }
            } else {
                ret.complete(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ret.fail(e)
        }
        return ret.future()
    }

    private fun unsubscribeTopics(clientId: String, topics: List<Topic>): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        val items = ArrayList<UaMonitoredItem>()

        topics.forEach { topic ->
            val (count, removed) = registry.delClient(clientId, topic)
            logger.debug("Unsubscribe [{}] [{}]", count, topic)
            if (!removed) {
                logger.warn("Client [{}] was not subscribed to [{}]", clientId, topic)
            } else if (count == 0) {
                items.addAll(registry.delTopic(topic))
            }
        }
        if (items.size > 0) {
            unsubscribeItems(items).onComplete(ret)
        } else {
            ret.complete(true)
        }
        return ret.future()
    }

    private fun getVariantOfValue(value: Buffer, nodeId: NodeId): Variant {
        return if (value.length() == 0)
            Variant.NULL_VALUE
        else
            getVariantOfValue(value.toString(), nodeId)
    }

    private fun getVariantOfValue(value: String, nodeId: NodeId): Variant {
        // TODO: Exception handling
        try {
            val type = client.addressSpace.getVariableNode(nodeId).dataType.identifier
            return when (type) {
                Identifiers.String.identifier -> Variant(value)
                Identifiers.Float.identifier -> Variant(value.toFloat())
                Identifiers.Double.identifier -> Variant(value.toDouble())
                Identifiers.UInteger.identifier,
                Identifiers.Integer.identifier,
                Identifiers.UInt16.identifier,
                Identifiers.Int16.identifier,
                Identifiers.UInt32.identifier,
                Identifiers.Int32.identifier,
                Identifiers.UInt64.identifier,
                Identifiers.Int64.identifier -> Variant(value.toString().toInt())
                Identifiers.SByte.identifier,
                Identifiers.Byte.identifier -> Variant(
                    Unsigned.ubyte(value.toByteArray(StandardCharsets.UTF_8)[0])
                )
                Identifiers.Boolean.identifier -> Variant(
                    !(value == "0" || value.equals("false", ignoreCase = true))
                )
                else -> {
                    logger.warn("Unhandled data type $type")
                    Variant.NULL_VALUE
                }
            }
        } catch (e: Exception) {
            logger.warn("Converting value to variant exception []", e.message)
            return Variant.NULL_VALUE
        }
    }

    private fun writeTopicValue(topic: Topic, value: Buffer): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            val nodeId = NodeId.parse(topic.topicInfo)
            val dataValue = when (topic.format) {
                Topic.Format.Value ->
                    DataValue(getVariantOfValue(value, nodeId), null, writeGetTime())
                Topic.Format.Json,
                Topic.Format.Pretty -> {
                    logger.warn("Value format not yet implemented!") // TODO
                    DataValue(Variant.NULL_VALUE, null, null)
                }
            }
            writeValueQueued(nodeId, dataValue).onComplete(ret)
        } catch (e: NumberFormatException) {
            logger.warn("Not a valid number [{}] for numeric tag [{}] value!", value.toString(), topic)
            ret.complete(false)
        } catch (e: Exception) {
            //e.printStackTrace()
            ret.fail(e)
        }
        return ret.future()
    }

    private val writeValueQueue = ArrayBlockingQueue<Triple<NodeId, DataValue, Promise<Boolean>>>(writeParameterQueueSize)

    private val writeValueThread =
        thread {
            while (true) {
                val nodeIds = ArrayList<NodeId>(writeParametersBlockSize)
                val dataValues = ArrayList<DataValue>(writeParametersBlockSize)
                val promises = ArrayList<Promise<Boolean>>(writeParametersBlockSize)
                fun addIt(it : Triple<NodeId, DataValue, Promise<Boolean>>) : Boolean {
                    nodeIds.add(it.first)
                    dataValues.add(it.second)
                    promises.add(it.third)
                    return true
                }
                var got = writeValueQueue.poll(1, TimeUnit.SECONDS)?.let(::addIt) ?: false
                while (got && nodeIds.size < writeParametersBlockSize) {
                    got = writeValueQueue.poll()?.let(::addIt) ?: false
                }
                if (nodeIds.size > 0) {
                    val results = client.writeValues(nodeIds, dataValues).get()
                    results.zip(promises).forEach {
                        if (!it.first.isGood) logger.warn("Writing value was not good [{}]", it.first.toString())
                        it.second.complete(it.first.isGood)
                    }
                }
            }
        }


    private fun writeValueAsync(nodeId: NodeId, dataValue: DataValue): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        client.writeValue(nodeId, dataValue).thenAccept { status: StatusCode ->
            if (status.isGood) {
                logger.debug("Wrote [{}] to nodeId=[{}]", dataValue.value.toString(), nodeId)
                promise.complete(true)
            } else {
                logger.warn(
                    "Wrote [{}] to nodeId=[{}] with status {}",
                    dataValue.value.toString(),
                    nodeId,
                    status
                )
                promise.complete(false)
            }
        }
        return promise.future()
    }

    private var lastWriteFailures = 0
    private fun writeValueQueued(nodeId: NodeId, dataValue: DataValue): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            writeValueQueue.add(Triple(nodeId, dataValue, promise))
            if (lastWriteFailures > 0) {
                logger.error("Add to write queue: Ok [{} missed writes]", lastWriteFailures)
                lastWriteFailures = 0
            }
        } catch (e: IllegalStateException) {
            if (lastWriteFailures == 0) {
                logger.error("Add to write queue: ${e.message}")
            }
            lastWriteFailures++
            promise.complete(false)
        }
        return promise.future()
    }

    private fun executeRpc(topic: Topic, data: Buffer): Future<Boolean> {
        return vertx.executeBlocking { ret: Promise<Boolean> -> // TODO: Implement functions async!
            val response = JsonObject()
            try {
                val request = Json.decodeValue(data)
                if (request is JsonObject) {
                    response.put("Id", request.getString("Id", ""))
                    when (val command = request.getString("Command", "")) {
                        "ServerInfo" -> response.put("Response", readServerInfo())
                        "BrowseNode" -> response.put("Response", rpcBrowseNode(request))
                        else -> {
                            val err = String.format("Unknown command [%s]", command)
                            response.put("Error", err)
                            logger.error(err)
                        }
                    }
                } else {
                    val err = "Request is not a json object!"
                    response.put("Error", err)
                    logger.error(err)
                }
            } catch (e: Exception) {
                response.put("Error", e.message)
                e.printStackTrace()
            }
            vertx.eventBus().publish(topic.topicName, Buffer.buffer(response.encodePrettily()))
            ret.complete(true)
        }
    }

    override fun serverInfoHandler(message: Message<JsonObject>) { // TODO: make it async
        val result = readServerInfo()
        message.reply(JsonObject().put("Ok", true).put("Result", result))
    }

    private fun readServerInfo(): JsonObject? {
        val result = JsonObject()
        val serverNode = client.addressSpace.getObjectNode(
            Identifiers.Server,
            Identifiers.ServerType
        ) as ServerTypeNode

        // Read properties of the Server object...
        val server = JsonArray()
        val namespace = JsonArray()
        result.put("Server", server)
        result.put("Namespace", namespace)
        serverNode.serverArray.forEach { server.add(it) }
        Arrays.stream(serverNode.namespaceArray).forEach { namespace.add(it) }
        val serverStatusNode = serverNode.serverStatusNode
        result.put("BuildInfo", serverStatusNode.buildInfo.toString())
        result.put("StartTime", serverStatusNode.startTime.javaInstant.toString())
        result.put("CurrentTime", serverStatusNode.currentTime.javaInstant.toString())
        result.put("ServerStatus", serverStatusNode.state.toString())
        return result
    }    

    private fun rpcBrowseNode(request: JsonObject) : JsonObject {
        val response = JsonObject()
        val node = request.getString("NodeId", "")
        val levels = request.getInteger("Levels", -1)
        val nodeId = NodeId.parseSafe(node)
        val flat = request.getBoolean("Flat", false)
        if (nodeId.isPresent) {
            response.put("Result", browseNode(nodeId.get(), levels, flat))
        } else {
            val err = String.format("Invalid node [%s]", node)
            response.put("Error", err)
            logger.error(err)
        }
        return response
    }

    override fun readHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        when {
            node != null && node is String -> {
                val nodeId = NodeId.parse(node)
                client.readValue(0.0, TimestampsToReturn.Both, nodeId).thenAccept { value ->
                    val result = Value.fromDataValue(value).encodeToJson()
                    message.reply(JsonObject().put("Ok", true).put("Result", result))
                }
            }
            node != null && node is JsonArray -> {
                val nodeIds = node.mapNotNull { if (it is String) NodeId.parse(it) else null }
                client.readValues(0.0, TimestampsToReturn.Both, nodeIds).thenAccept { list ->
                    val result = JsonArray()
                    list.forEach {
                        result.add(Value.fromDataValue(it).encodeToJson())
                    }
                    message.reply(JsonObject().put("Ok", true).put("Result", result))
                }
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
        when {
            node != null && node is String -> {
                val nodeId = NodeId.parse(node)
                val value = message.body().getString("Value", "")
                val dataValue = DataValue(getVariantOfValue(value, nodeId), null, writeGetTime())
                writeValueQueued(nodeId, dataValue).onComplete {
                    message.reply(JsonObject().put("Ok", it.succeeded() && it.result()))
                }
            }
            node != null && node is JsonArray -> {
                val values = message.body().getJsonArray("Value", JsonArray())
                CompositeFuture.all(node.zip(values).mapNotNull {
                    if (it.first is String && it.second is String) {
                        val nodeId = NodeId.parseSafe(it.first as String)
                        if (nodeId.isPresent) {
                            val variant = getVariantOfValue(it.second as String, nodeId.get())
                            val dataValue = DataValue(variant, null, writeGetTime())
                            writeValueQueued(nodeId.get(), dataValue) // TODO: optimize and replace with client.writeValues(nodeIds, dataValues)
                        } else null
                    } else null
                }).onComplete { result ->
                    val results = result.result().list<Boolean>()
                    message.reply(JsonObject().put("Ok", JsonArray(results)))
                }
            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    private fun subscribeNodes(topics: List<Topic>) : Future<Boolean> {
        //logger.info("Subscribe nodes [{}] [{}]", topics.joinToString(",") { it.topicName }, monitoringParametersSamplingInterval)
        logger.info("Subscribe nodes [{}] sampling interval [{}]", topics.size, monitoringParametersSamplingInterval)
        val ret = Promise.promise<Boolean>()
        val nodeIds = topics.map { NodeId.parseOrNull(it.topicInfo) }.toList()
        val requests = ArrayList<MonitoredItemCreateRequest>()
        nodeIds.forEach { nodeId ->
            val clientHandle = subscription.nextClientHandle()
            requests.add(
                MonitoredItemCreateRequest(
                    ReadValueId(nodeId, AttributeId.Value.uid(),null, QualifiedName.NULL_VALUE),
                    MonitoringMode.Reporting,
                    MonitoringParameters(
                        clientHandle,
                        monitoringParametersSamplingInterval,
                        null,
                        monitoringParametersBufferSize,
                        monitoringParametersDiscardOldest
                    )
                )
            )
        }

        // when creating items in MonitoringMode.Reporting this callback is where each item needs to have its
        // value/event consumer hooked up. The alternative is to create the item in sampling mode, hook up the
        // consumer after the creation call completes, and then change the mode for all items to reporting.
        val onItemCreated =
            BiConsumer { item: UaMonitoredItem, nr: Int ->
                val topic = topics[nr]
                if (item.statusCode.isGood)
                    registry.addMonitoredItem(item, topic)
                item.setValueConsumer { data: DataValue ->
                    //println("callback: id="+ item.monitoredItemId+ " : size=" +topics.size + " : "+ item.clientHandle.toInt() + " : " + item.readValueId.nodeId.toParseableString() + " : " + data.value.toString())
                    valueConsumer(topic, data)
                }
            }

        subscription
            .createMonitoredItems(TimestampsToReturn.Both, requests, onItemCreated)
            .thenAccept { monitoredItems: List<UaMonitoredItem> ->
                try {
                    for (item in monitoredItems) {
                        if (item.statusCode.isGood) {
                            logger.debug("Monitored item created for nodeId {}", item.readValueId.nodeId)
                        } else {
                            logger.warn(
                                "Failed to create item for nodeId {} (status={})",
                                item.readValueId.nodeId,
                                item.statusCode
                            )
                        }
                    }
                    ret.complete(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ret.fail(e)
                }
            }
        return ret.future()
    }

    private fun subscribePath(topics: List<Topic>) : Future<Boolean> {
        return vertx.executeBlocking { ret: Promise<Boolean> ->
            try {
                val resolvedTopics = mutableListOf<Topic>()
                topics.forEach { topic ->
                    logger.debug("Subscribe path [{}]", topic)
                    val items = topic.pathItems.map {
                        when (it.toLowerCase()) {
                            "\$objects" -> "i=85"
                            else -> it
                        }
                    }
                    if (items.size < 2) {
                        logger.warn("Subscribe path with too less items! [{}]", topic.topicInfo)
                    } else {
                        val resolvedNodeIds = mutableListOf<NodeId>()
                        fun find(node: String, itemIdx: Int) {
                            val item = items[itemIdx]
                            val nodeId = NodeId.parseOrNull(node)
                            if (nodeId != null) {
                                val result = browseNode(nodeId)
                                    .filterIsInstance<JsonObject>()
                                    .filter { item == "#" || item == "+" || item == it.getString("BrowseName", "") }
                                val nextIdx = if (item != "#" && itemIdx + 1 < items.size) itemIdx + 1 else itemIdx
                                result.forEach {
                                    val childNodeId = NodeId.parseOrNull(it.getString("NodeId"))
                                    if (childNodeId != null) when (it.getString("NodeClass")) {
                                        "Variable" -> resolvedNodeIds.add(childNodeId)
                                        "Object" -> find(it.getString("NodeId", ""), nextIdx)
                                    }
                                }
                            }
                        }
                        find(items.first(), 1)
                        resolvedTopics.addAll(resolvedNodeIds.map {
                            Topic(
                                topicName = topic.topicName,
                                systemType = topic.systemType,
                                topicType = topic.topicType,
                                systemName = topic.systemName,
                                topicInfo = it.toParseableString(),
                                format = topic.format
                            )
                        })
                    }
                }
                logger.info("Path result size [{}]", resolvedTopics.size)
                if (resolvedTopics.size>0) {
                    subscribeNodes(resolvedTopics).onComplete(ret)
                } else {
                    ret.complete(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ret.fail(e)
            }
        }
    }

    override fun resubscribe() {
        val topics = registry.getTopics()
        if (topics.isNotEmpty()) {
            logger.info("Resubscribe [{}] topics", topics.size)
            topics.forEach(registry::delTopic)
            subscribeNodes(topics)
        }
    }

    private fun unsubscribeItems(items: List<UaMonitoredItem>) : Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            logger.debug("Unsubscribe items [{}]", items.joinToString(",") { it.readValueId.nodeId.toString() })
            if (items.isNotEmpty()) {
                subscription.deleteMonitoredItems(items).thenAccept {
                    ret.complete(true)
                }
            } else {
                ret.complete(false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ret.fail(e)
        }
        return ret.future()
    }


    private fun valueConsumer(topic: Topic, data: DataValue) {
        logger.debug("Got value [{}] [{}]", topic.topicName, data.value.toString())
        try {
            fun json() = JsonObject()
                .put("Topic", topic.encodeToJson())
                .put("Value", Value.fromDataValue(data).encodeToJson())

            val buffer : Buffer? = when (topic.format) {
                Topic.Format.Value -> {
                    data.value?.value?.let {
                        Buffer.buffer(it.toString())
                    }
                }
                Topic.Format.Json -> Buffer.buffer(json().encode())
                Topic.Format.Pretty -> Buffer.buffer(json().encodePrettily())
            }
            if (buffer!=null) vertx.eventBus().publish(topic.topicName, buffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun browseNode(browseRoot: NodeId, maxLevel: Int=1, flat: Boolean=false): JsonArray {
        return browseNode(browseRoot, maxLevel, 1, flat)
    }

    private fun browseNode(browseRoot: NodeId, maxLevel: Int, level: Int, flat: Boolean): JsonArray {
        val result = JsonArray()

        fun addResult(references: List<ReferenceDescription>) {
            references.forEach { rd ->
                val item = JsonObject()
                item.put("BrowseName", rd.browseName.name)
                item.put("DisplayName", rd.displayName.text)
                item.put("NodeId", rd.nodeId.toParseableString())
                item.put("NodeClass", rd.nodeClass.toString())
                //logger.debug("$flat : $level : $item")

                if (rd.nodeClass === NodeClass.Variable || !flat) result.add(item)

                // recursively browse to children if it is an object node
                if ((maxLevel == -1 || level < maxLevel) && rd.nodeClass === NodeClass.Object) {
                    val nodeId = rd.nodeId.toNodeId(client.namespaceTable)
                    if (nodeId.isPresent) {
                        val next = browseNode(nodeId.get(), maxLevel, level + 1, flat)
                        if (flat) {
                            result.addAll(next)
                        } else {
                            item.put("Nodes", next)
                        }
                    }
                }
            }
        }

        val browse = BrowseDescription(
            browseRoot,
            BrowseDirection.Forward,
            Identifiers.References,
            true,
            Unsigned.uint(NodeClass.Object.value or NodeClass.Variable.value),
            Unsigned.uint(BrowseResultMask.All.value)
        )

        try {
            val browseResult = client.browse(browse).get()
            if (browseResult.statusCode.isGood &&  browseResult.references != null) {
                addResult(browseResult.references.asList())
                var continuationPoint = browseResult.continuationPoint
                while (continuationPoint != null && continuationPoint.isNotNull) {
                    val nextResult = client.browseNext(false, continuationPoint).get()
                    addResult(nextResult.references.asList())
                    continuationPoint = nextResult.continuationPoint
                }
            } else {
                logger.error("Browsing nodeId [{}] failed [{}]", browseRoot, browseResult.statusCode.toString())
            }
        } catch (e: InterruptedException) {
            logger.error(String.format("Browsing nodeId=%s failed: %s", browseRoot, e.message, e))
        } catch (e: ExecutionException) {
            logger.error(String.format("Browsing nodeId=%s failed: %s", browseRoot, e.message, e))
        }

        return result
    }
}