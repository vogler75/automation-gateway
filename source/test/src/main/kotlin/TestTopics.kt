import at.rocworks.gateway.core.data.Topic

object TestTopics {
    val dollar = "\$"

    fun main() {
        listOf(
            "opc/unified/node/1/16.687.1.0.0.0",
            "opc/unified/node:Value/1/16.687.1.0.0.0",
            "opc/unified/node:Pretty/1/16.687.1.0.0.0",
            "opc/unified/symbol/HMI_Tag_2",
            "opc/unified/path/Tags/HMI_Tag_3",
            "opc/oa/node:Value/2/ExampleDP_Float.ExampleDP_Arg1",
            "opc/oa/node:value/2/ExampleDP_Float.ExampleDP_Arg1",
            "opc/oa/node:Json/2/ExampleDP_Float.ExampleDP_Arg1",
            "opc/oa/node:json/2/ExampleDP_Float.ExampleDP_Arg1",
            "opc/oa/rpc/d490352d-4142-4729-aab2-2f0101f4701e",
            "\$SYS/broker/uptime"
            ).forEach {
            val t = Topic.parseTopic(it)
            println("--- " + t.topicName + " ---")
            if (t.isValid()) {
                println("parsedTopic   : " + t.toString())
                val j = Topic.encodeToJson(t)
                println("encodeToJson  : " + j.toString())
                val x = Topic.decodeFromJson(j)
                println("decodeFromJson: " + x.toString())
            } else {
                println("Invalid! " + t.topicName)
            }
        }
    }
}