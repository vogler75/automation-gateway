import DDS.*
import OpenDDS.DCPS.DEFAULT_STATUS_MASK
import OpenDDS.DCPS.TheParticipantFactory
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Value
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import at.rocworks.gateway.core.opcua.OpcUaMonitoredItem
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import org.omg.CORBA.StringSeqHolder
import java.time.Instant

class DdsVerticle(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Dds

    private val configFile = config.getString("DCPSConfigFile", "dds.ini")
    private val domainId = config.getInteger("Domain", 0)

    private var domainParticipant: DDS.DomainParticipant? = null
    private var subscriber: DDS.Subscriber? = null

    class TopicType(val topicTypeName: String) {
        val typeSupportImplClass = Class.forName(topicTypeName + "TypeSupportImpl")
        val typeSupportInstance = typeSupportImplClass.getConstructor().newInstance()
        val typeSupportRegisterType =
            typeSupportImplClass.getMethod("register_type", DDS.DomainParticipant::class.java, String::class.java)
        val typeSupportGetTypeName = typeSupportImplClass.getMethod("get_type_name")

        fun registerType(domainParticipant: DomainParticipant) =
            typeSupportRegisterType.invoke(typeSupportInstance, domainParticipant, "")

        fun getTypeName() = typeSupportGetTypeName.invoke(typeSupportInstance) as String
    }

    private val topicTypes: Map<String, TopicType>

    init {
        topicTypes = config.getJsonArray("TopicTypes")
            .filterIsInstance<JsonObject>()
            .map {
                val id = it.getString("Id")
                val topicTypeName = it.getString("TopicTypeName")
                id to TopicType(topicTypeName)
            }.toMap()
    }

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            val args = listOf("-DCPSConfigFile", configFile)
            val domainParticipantFactory = TheParticipantFactory.WithArgs(StringSeqHolder(args.toTypedArray()))
            if (domainParticipantFactory == null) {
                promise.fail("Domain participant factory failed!")
            } else {
                logger.info("Domain factory created.")
                domainParticipant = domainParticipantFactory.create_participant(
                    domainId,
                    PARTICIPANT_QOS_DEFAULT.get(),
                    null,
                    DEFAULT_STATUS_MASK.value
                )

                if (domainParticipant == null) {
                    promise.fail("Domain participant creation failed!")
                } else {
                    logger.info("Domain participant created.")
                    subscriber = domainParticipant!!.create_subscriber(
                        SUBSCRIBER_QOS_DEFAULT.get(),
                        null,
                        DEFAULT_STATUS_MASK.value
                    )

                    topicTypes.forEach { (id, topicType) ->
                        if (topicType.registerType(domainParticipant!!) == RETCODE_OK.value) {
                            logger.info("Registered type ${topicType.topicTypeName}.")
                        } else {
                            logger.error("Register type ${topicType.topicTypeName} failed!")
                        }
                    }

                    logger.info("Started.")
                    promise.complete(true)
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return promise.future()
    }

    override fun disconnect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        promise.complete(true)
        return promise.future()
    }

    override fun shutdown() {
        logger.warn("Shutdown")
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        topics.filter { it.pathItems.size > 1 }.forEach(this::subscribeTopic)
        promise.complete(true)
        return promise.future()
    }

    private fun subscribeTopic(topic: Topic) {
        val topicTypeName = topic.pathItems.component1()
        val topicName = topic.pathItems.component2()
        val topicType = topicTypes[topicTypeName]
        if (topicType != null) {
            val ddsTopic = domainParticipant!!.create_topic(
                topicName,
                topicType.getTypeName(),
                TOPIC_QOS_DEFAULT.get(), null,
                DEFAULT_STATUS_MASK.value
            )

            val listener = DataReaderListenerImpl(topicType.topicTypeName) { sampleInfo, data ->
                val json = Json.encode(data)
                val sec = sampleInfo.source_timestamp.sec.toLong()
                val ms = sampleInfo.source_timestamp.nanosec.toLong() / 1_000_000
                val ts = Instant.ofEpochMilli(sec * 1_000 + ms)

                val value = Value(  // TODO: create a new value subtype with json as value
                    json,
                    0,
                    sampleInfo.sample_state.toLong(),
                    ts,
                    ts
                )
                vertx.eventBus().publish(topic.topicName, json)
            }

            val reader = subscriber!!.create_datareader(
                ddsTopic,
                DATAREADER_QOS_DEFAULT.get(),
                listener,
                DEFAULT_STATUS_MASK.value
            )

            registry.addMonitoredItem(DdsMonitoredItem(reader!!), topic)
        } else {
            logger.warn("Unhandled topic type ${topicTypeName}!")
        }
    }

    override fun unsubscribeItems(items: List<MonitoredItem>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        items.filterIsInstance<DdsMonitoredItem>().forEach { item ->
            subscriber!!.delete_datareader(item.item)
        }
        promise.complete(true)
        return promise.future()
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        TODO("Not yet implemented")
    }

    override fun readServerInfo(): JsonObject {
        TODO("Not yet implemented")
    }

    override fun readHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun writeHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun browseHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun schemaHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

}