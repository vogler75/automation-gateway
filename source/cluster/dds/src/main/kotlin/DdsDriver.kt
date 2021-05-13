import DDS.*
import OpenDDS.DCPS.DEFAULT_STATUS_MASK
import OpenDDS.DCPS.TheParticipantFactory
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import org.omg.CORBA.StringSeqHolder
import java.time.Instant
import DDS.PUBLISHER_QOS_DEFAULT
import DDS.DATAWRITER_QOS_DEFAULT
import at.rocworks.gateway.core.data.TopicValueJson
import java.lang.reflect.Method


class DdsDriver(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Dds

    private val configFile = config.getString("DCPSConfigFile", "dds.ini")
    private val domainId = config.getInteger("Domain", 0)

    private var domainParticipant: DDS.DomainParticipant? = null
    private var subscriber: DDS.Subscriber? = null

    class TopicType(val topicTypeName: String) {
        private val typeSupportImplClass: Class<*> = Class.forName(topicTypeName + "TypeSupportImpl")

        private val typeSupportInstance: Any = typeSupportImplClass.getConstructor().newInstance()

        private val typeSupportRegisterType: Method =
            typeSupportImplClass.getMethod("register_type", DDS.DomainParticipant::class.java, String::class.java)

        private val typeSupportGetTypeName: Method = typeSupportImplClass.getMethod("get_type_name")

        fun registerType(domainParticipant: DomainParticipant): Any =
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

                    topicTypes.forEach { (_, topicType) ->
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
        topics.filter { it.addressItems.size > 1 }.forEach(this::subscribeTopic)
        promise.complete(true)
        return promise.future()
    }

    private fun subscribeTopic(topic: Topic) {
        val topicTypeName = topic.addressItems.component1()
        val topicName = topic.addressItems.component2()
        val topicType = topicTypes[topicTypeName]
        if (topicType != null) {
            val ddsTopic = domainParticipant!!.create_topic(
                topicName,
                topicType.getTypeName(),
                TOPIC_QOS_DEFAULT.get(), null,
                DEFAULT_STATUS_MASK.value
            )

            val listener = DataReaderListenerImpl(topicType.topicTypeName) { sampleInfo, data ->
                val json = JsonObject.mapFrom(data)
                val sec = sampleInfo.source_timestamp.sec.toLong()
                val ms = sampleInfo.source_timestamp.nanosec.toLong() / 1_000_000
                val ts = Instant.ofEpochMilli(sec * 1_000 + ms)
                val value = TopicValueJson(json, ts, sampleInfo.sample_state)

                fun json() = JsonObject()
                    .put("Topic", topic.encodeToJson())
                    .put("Value", value.encodeToJson())

                val buffer : Buffer? = when (topic.format) {
                    Topic.Format.Value -> {
                        value.encodeToJson().let {
                            Buffer.buffer(it.encode())
                        }
                    }
                    Topic.Format.Json -> Buffer.buffer(json().encode())
                    Topic.Format.Pretty -> Buffer.buffer(json().encodePrettily())
                }

                if (buffer!=null) vertx.eventBus().publish(topic.topicName, buffer)
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
        items.filterIsInstance<DdsMonitoredItem>().forEach { reader ->
            subscriber!!.delete_datareader(reader.item)
        }
        promise.complete(true)
        return promise.future()
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            logger.info("Publish...$topic")
            val topicTypeName = topic.addressItems.component1()
            val topicName = topic.addressItems.component2()
            val topicType = topicTypes[topicTypeName]
            if (topicType != null) {
                logger.debug("Create Topic...")
                val ddsTopic = domainParticipant!!.create_topic(
                    topicName,
                    topicType.getTypeName(),
                    TOPIC_QOS_DEFAULT.get(), null,
                    DEFAULT_STATUS_MASK.value
                )

                logger.debug("Create Publisher...")
                val ddsPublisher: Publisher = domainParticipant!!.create_publisher(
                    PUBLISHER_QOS_DEFAULT.get(),
                    null,
                    DEFAULT_STATUS_MASK.value
                )

                logger.debug("Create DataWriter...")
                val ddsDataWriter = ddsPublisher.create_datawriter(
                    ddsTopic, DATAWRITER_QOS_DEFAULT.get(), null, DEFAULT_STATUS_MASK.value
                )

                val dataWriterClass = Class.forName(topicType.topicTypeName + "DataWriter")
                val dataWriterHelperClass = Class.forName(topicType.topicTypeName + "DataWriterHelper")
                val dataWriterHelperNarrow = dataWriterHelperClass.getMethod("narrow", org.omg.CORBA.Object::class.java)

                logger.debug("Parse Value...")
                val topicClass = Class.forName(topicType.topicTypeName )
                val topicValue = Json.decodeValue(value, topicClass)

                val dataWriter = dataWriterHelperNarrow(null, ddsDataWriter)

                logger.debug("Write Topic...")
                val handle: Int = dataWriterClass.getMethod("register_instance", topicClass).invoke(dataWriter, topicValue) as Int
                val ret: Int = dataWriterClass.getMethod("write", topicClass, Int::class.java).invoke(dataWriter, topicValue, handle) as Int
                logger.debug("Write Done.")

                ddsDataWriter._release()
                ddsPublisher._release()
                ddsTopic._release()

                promise.complete(ret == 0)
            } else {
                logger.warn("Unhandled topic type ${topicTypeName}!")
                promise.complete(false)
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return promise.future()
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