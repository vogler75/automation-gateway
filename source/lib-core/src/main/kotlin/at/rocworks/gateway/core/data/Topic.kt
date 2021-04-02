package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject

data class Topic (
    val topicName: String,
    val systemType: SystemType,
    val topicType: TopicType,
    val systemName: String,
    val payload: String,
    val format: Format = Format.Json
) {

    enum class SystemType {
        Unknown,
        Sys,
        Opc,
        Plc,
        Dds,
        Mqtt,
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
        get() = this.payload.split(Regex("""(?<!\\)/""")).map { it.replace("\\/", "/") }

    fun encodeToJson() = encodeToJson(this)

    companion object {
        fun parseTopic(topic: String): Topic {
            val dollar = "\$"

            val opcUri = SystemType.Opc.name
            val plcUri = SystemType.Plc.name
            val ddsUri = SystemType.Dds.name

            val optFmt = "(|:Json|:Pretty|:Value)"
            fun getFmt(s: String) = when (s.toLowerCase()) {
                ":" + Format.Value.name.toLowerCase() -> Format.Value
                ":" + Format.Json.name.toLowerCase() -> Format.Json
                ":" + Format.Pretty.name.toLowerCase() -> Format.Pretty
                else -> Format.Value
            }

            // --- OPC ---
            return """${opcUri}/(\w+)/Node$optFmt/([0-9])+/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.NodeId,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    payload = "ns=${it.destructured.component3()};s=${it.destructured.component4()}",
                )
            } ?: """${opcUri}/(\w+)/Node$optFmt/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.NodeId,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    payload = it.destructured.component3()
                )
            } ?: """${opcUri}/(\w+)/Path$optFmt/(.*)/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.Path,
                    systemName = it.destructured.component1(),
                    format = getFmt(it.destructured.component2()),
                    payload = """${it.destructured.component3()}/${it.destructured.component4()}""",
                )
            } ?: """${opcUri}/(\w+)/Rpc/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Opc,
                    topicType = TopicType.Rpc,
                    systemName = it.destructured.component1(),
                    format = Format.Json,
                    payload = it.destructured.component2(),
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
                    payload = it.destructured.component3()
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
                    payload = it.destructured.component3()
                )
            }
            // --- SYS ---
            ?: """(\${dollar}SYS)/(.*)$""".toRegex(RegexOption.IGNORE_CASE).find(topic)?.let {
                Topic(
                    topic,
                    systemType = SystemType.Sys,
                    topicType = TopicType.Path,
                    systemName = "",
                    payload = """${it.destructured.component1()}/${it.destructured.component2()}""",
                )
            }
            // --- MQTT ---
            ?: run {
                Topic(
                    topic,
                    systemType = SystemType.Mqtt,
                    topicType = TopicType.Path,
                    systemName = "",
                    payload = topic,
                )
            }
        }

        private const val TOPICNAME = "TopicName"
        private const val SYSTEMTYPE = "SystemType"
        private const val ITEMTYPE = "ItemType"
        private const val SYSTEMNAME = "SystemName"
        private const val PAYLOAD = "Payload"
        private const val FORMAT = "Format"


        fun encodeToJson(topic: Topic) : JsonObject {
            return JsonObject()
                .put(TOPICNAME, topic.topicName)
                .put(SYSTEMTYPE, topic.systemType.name)
                .put(ITEMTYPE, topic.topicType.name)
                .put(SYSTEMNAME, topic.systemName)
                .put(PAYLOAD, topic.payload)
                .put(FORMAT, topic.format)
        }

        fun decodeFromJson(json: JsonObject): Topic {
            return Topic(
                topicName = json.getString(TOPICNAME, ""),
                systemType = SystemType.valueOf(json.getString(SYSTEMTYPE, "Invalid")),
                topicType = TopicType.valueOf(json.getString(ITEMTYPE, "Invalid")),
                systemName = json.getString(SYSTEMNAME, null),
                payload = json.getString(PAYLOAD, null),
                format = Format.valueOf(json.getString(FORMAT, Format.Value.name))
            )
        }
    }
}


