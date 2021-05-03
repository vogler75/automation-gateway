package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject

data class Topic (
    val topicName: String,
    val systemType: SystemType,
    val topicType: TopicType,
    val systemName: String,
    val address: String,
    val format: Format = Format.Json,
    var browsePath: String = ""
) {
    enum class SystemType {
        Unknown,
        Sys,
        Opc,
        Plc,
        Dds,
        Mqtt
    }

    enum class TopicType {
        Unknown,
        NodeId,
        Symbol,
        Path,
        Rpc
    }

    enum class Format {
        Json,
        Pretty,
        Value
    }

    fun isValid() = systemType != SystemType.Unknown && topicType != TopicType.Unknown

    val pathItems : List<String>
        get() = this.address.split(Regex("""(?<!\\)/""")).map { it.replace("\\/", "/") }

    fun encodeToJson() = encodeToJson(this)

    companion object {
        fun parseTopic(topic: String): Topic {
            val dollar = "\$"

            val opcUri = SystemType.Opc.name
            val plcUri = SystemType.Plc.name
            val ddsUri = SystemType.Dds.name
            val mqttUri = SystemType.Mqtt.name

            val optFmt = "(|:Json|:Pretty|:Value)"
            fun getFmt(s: String) = when (s.toLowerCase()) {
                ":" + Format.Value.name.toLowerCase() -> Format.Value
                ":" + Format.Json.name.toLowerCase() -> Format.Json
                ":" + Format.Pretty.name.toLowerCase() -> Format.Pretty
                else -> Format.Json
            }

            // --- OPC ---
            return """${opcUri}/(\w+)/Node$optFmt/([0-9])+/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.NodeId,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    address = "ns=${it.destructured.component3()};s=${it.destructured.component4()}",
                )
            } ?: """${opcUri}/(\w+)/Node$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.NodeId,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    address = it.destructured.component3()
                )
            } ?: """${opcUri}/(\w+)/Path$optFmt/(.*)/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.Path,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    address = """${it.destructured.component3()}/${it.destructured.component4()}""",
                )
            }
            // --- PLC ---
            ?: """${plcUri}/(\w+)/Node$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Plc,
                    topicType = TopicType.NodeId,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    address = it.destructured.component3()
                )
            }
            // --- DDS ---
            ?: """${ddsUri}/(\w+)/Path$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Dds,
                    topicType = TopicType.Path,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    address = it.destructured.component3()
                )
            }
            // --- Mqtt ---
            ?: """${mqttUri}/(\w+)/Path$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Mqtt,
                    topicType = TopicType.Path,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    address = it.destructured.component3()
                )
            }
            // --- SYS ---
            ?: """(\${dollar}SYS)/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Sys,
                    topicType = TopicType.Path,
                    systemName = "",
                    address = """${it.destructured.component1()}/${it.destructured.component2()}""",
                )
            }
            // --- Other ---
            ?: run {
                Topic(
                    topic,
                    systemType = SystemType.Unknown,
                    topicType = TopicType.Unknown,
                    systemName = "",
                    address = topic,
                )
            }
        }

        private const val TOPIC_NAME = "topicName"
        private const val SYSTEM_TYPE = "systemType"
        private const val ITEM_TYPE = "itemType"
        private const val SYSTEM_NAME = "systemName"
        private const val PAYLOAD = "payload"
        private const val FORMAT = "format"
        private const val BROWSE_PATH = "browsePath"

        fun encodeToJson(topic: Topic) : JsonObject {
            return JsonObject()
                .put(TOPIC_NAME, topic.topicName)
                .put(SYSTEM_TYPE, topic.systemType.name)
                .put(ITEM_TYPE, topic.topicType.name)
                .put(SYSTEM_NAME, topic.systemName)
                .put(PAYLOAD, topic.address)
                .put(FORMAT, topic.format)
                .put(BROWSE_PATH, topic.browsePath)
        }

        fun decodeFromJson(json: JsonObject): Topic {
            return Topic(
                topicName = json.getString(TOPIC_NAME, ""),
                systemType = SystemType.valueOf(json.getString(SYSTEM_TYPE, "Invalid")),
                topicType = TopicType.valueOf(json.getString(ITEM_TYPE, "Invalid")),
                systemName = json.getString(SYSTEM_NAME, ""),
                address = json.getString(PAYLOAD, ""),
                format = Format.valueOf(json.getString(FORMAT, Format.Value.name)),
                browsePath = json.getString(BROWSE_PATH, "")
            )
        }
    }
}


