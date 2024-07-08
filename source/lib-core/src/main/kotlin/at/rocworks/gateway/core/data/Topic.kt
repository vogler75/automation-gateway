package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

class BrowsePath (
    private val items: List<String>
) {
    constructor(browsePath: String) : this(Topic.splitAddress(browsePath))

    constructor(browsePath: String, additionalItem: String) : this(Topic.splitAddress(browsePath) + additionalItem)
    override fun toString() = items.joinToString("/")
    fun toString(separator: CharSequence) = items.joinToString(separator)
    fun isEmpty() = items.isEmpty()

    fun toList() = items

    fun getLast(): String = if (items.isNotEmpty()) items.last() else ""
}

/*
 Example Topics:

 Topic(topicName=opc/s7/node/ns=3;s="DB1"."A01"."State", systemType=Opc, systemName=s7, topicType=Node, topicPath=, topicNode=ns=3;s="DB1"."A01"."State", dataFormat=Json, browsePath=)

 Topic(topicName=opc/demo1/path/Objects/Dynamic/#, systemType=Opc, systemName=demo1, topicType=Path, topicPath=Objects/Dynamic/#, topicNode=ns=2;s=Dynamic/RandomInt32, dataFormat=Json, browsePath=Objects/Dynamic/RandomInt32)

 Topic(topicName=mqtt/home/path/Original/PV/Hoymiles1/Inverter/GridFrequency, systemType=Mqtt, systemName=home, topicType=Path, topicPath=Original/PV/Hoymiles1/Inverter/GridFrequency, topicNode=, dataFormat=Json, browsePath=Original/PV/Hoymiles1/Inverter/GridFrequency)

 Topic(topicName=mqtt/home/path/Original/Govee/#, systemType=Mqtt, systemName=home, topicType=Path, topicPath=Original/Govee/#, topicNode=, dataFormat=Json, browsePath=Original/Govee/GVH5075_12DB/Temperature)
 */

data class Topic (
    val topicName: String, // <SystemType> / <SystemName> / <Node|Path> ...
    val systemType: SystemType,
    val systemName: String,
    val topicType: TopicType,
    val topicPath: String, // path with potentially wildcards
    val topicNode: String, // given node or a resolved address
    val dataFormat: Format = Format.Json,
    private val browsePath: BrowsePath = BrowsePath(emptyList())
) {
    enum class SystemType {
        Unknown,
        Sys,
        Opc,
        Plc,
        Mqtt
    }

    enum class TopicType {
        Unknown,
        Node,
        Path
    }

    enum class Format {
        Json,
        Value
    }

    fun isValid() = systemType != SystemType.Unknown && topicType != TopicType.Unknown

    private val topicItems : List<String>
        get() = splitAddress(this.topicName)

    val hasBrowsePath: Boolean
        get() = !browsePath.isEmpty()

    fun getFQN() = "${systemType}/${systemName}/${topicType}/${getBrowsePathOrNode()}"
    //fun getFQN() = topicItems.slice(0..2).joinToString("/") + "/" + getBrowsePathOrNode()

    fun getMetricName() = when (topicType) {
        TopicType.Path -> browsePath.getLast()
        TopicType.Node -> topicNode
        TopicType.Unknown -> ""
    }

    fun getBrowsePathOrNode() = when (topicType) {
        TopicType.Path -> browsePath
        TopicType.Node -> BrowsePath(listOf(topicNode))
        TopicType.Unknown -> BrowsePath(emptyList())
    }

    fun encodeToJson() = encodeToJson(this)

    companion object {
        fun splitAddress(address: String)
            = address.split(Regex("""(?<!\\)/""")).map { it.replace("\\/", "/") }

        fun parseTopic(topic: String): Topic {
            val dollar = "\$"

            val opcUri = SystemType.Opc.name
            val plcUri = SystemType.Plc.name
            val mqttUri = SystemType.Mqtt.name

            val optFmt = "(|:Json|:Pretty|:Value)"
            fun getFmt(s: String) = when (s.lowercase()) {
                ":" + Format.Value.name.lowercase() -> Format.Value
                ":" + Format.Json.name.lowercase() -> Format.Json
                else -> Format.Json
            }

            // --- OPC ---
            return """${opcUri}/(\w+)/Node$optFmt/([0-9])+/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.Node,
                    systemName = it.destructured.component1(),
                    dataFormat = getFmt(it.destructured.component2()),
                    topicPath = "",
                    topicNode = "ns=${it.destructured.component3()};s=${it.destructured.component4()}",
                )
            } ?: """${opcUri}/(\w+)/Node$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.Node,
                    systemName = it.destructured.component1(),
                    dataFormat = getFmt(it.destructured.component2()),
                    topicPath = "",
                    topicNode = it.destructured.component3()
                )
            } ?: """${opcUri}/(\w+)/Path$optFmt/(.*)/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.Path,
                    systemName = it.destructured.component1(),
                    dataFormat = getFmt(it.destructured.component2()),
                    topicPath = """${it.destructured.component3()}/${it.destructured.component4()}""",
                    topicNode = ""
                )
            }
            // --- PLC ---
            ?: """${plcUri}/(\w+)/Node$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Plc,
                    topicType = TopicType.Node,
                    systemName = it.destructured.component1(),
                    dataFormat = getFmt(it.destructured.component2()),
                    topicPath = "",
                    topicNode = it.destructured.component3()
                )
            }
            // --- Mqtt ---
            ?: """${mqttUri}/(\w+)/Path$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Mqtt,
                    topicType = TopicType.Path,
                    systemName = it.destructured.component1(),
                    dataFormat = getFmt(it.destructured.component2()),
                    topicPath = it.destructured.component3(),
                    topicNode = ""
                )
            }
            // --- SYS ---
            ?: """(\${dollar}SYS)/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Sys,
                    topicType = TopicType.Path,
                    systemName = "",
                    topicPath = "${it.destructured.component1()}/${it.destructured.component2()}",
                    topicNode = "",
                )
            }
            // --- Other ---
            ?: run {
                Topic(
                    topic,
                    systemType = SystemType.Unknown,
                    topicType = TopicType.Unknown,
                    systemName = "",
                    topicPath = topic,
                    topicNode = "",
                )
            }
        }

        private const val TOPIC_NAME = "topicName"
        private const val TOPIC_TYPE = "topicType"
        private const val SYSTEM_TYPE = "systemType"
        private const val SYSTEM_NAME = "systemName"
        private const val TOPIC_NODE = "topicNode"
        private const val TOPIC_PATH = "topicPath"
        private const val DATA_FORMAT = "dataFormat"
        private const val BROWSE_PATH = "browsePath"

        fun encodeToJson(topic: Topic) : JsonObject {
            return JsonObject()
                .put(TOPIC_NAME, topic.topicName)
                .put(TOPIC_TYPE, topic.topicType.name)
                .put(SYSTEM_TYPE, topic.systemType.name)
                .put(SYSTEM_NAME, topic.systemName)
                .put(TOPIC_PATH, topic.topicPath)
                .put(TOPIC_NODE, topic.topicNode)
                .put(DATA_FORMAT, topic.dataFormat)
                .put(BROWSE_PATH, topic.browsePath.toList())
        }

        fun decodeFromJson(json: JsonObject): Topic {
            return Topic(
                topicName = json.getString(TOPIC_NAME, ""),
                systemType = SystemType.valueOf(json.getString(SYSTEM_TYPE, "Invalid")),
                topicType = TopicType.valueOf(json.getString(TOPIC_TYPE, "Invalid")),
                systemName = json.getString(SYSTEM_NAME, ""),
                topicPath = json.getString(TOPIC_PATH, ""),
                topicNode = json.getString(TOPIC_NODE, ""),
                dataFormat = Format.valueOf(json.getString(DATA_FORMAT, Format.Value.name)),
                browsePath = BrowsePath(json.getJsonArray(BROWSE_PATH, JsonArray()).map { it.toString() }.toList())
            )
        }
    }
}


