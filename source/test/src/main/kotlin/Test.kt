import at.rocworks.gateway.core.data.*

import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import java.lang.Exception
import java.util.logging.LogManager
import kotlin.system.exitProcess

import org.slf4j.LoggerFactory

object Test {

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val stream = Test::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = LoggerFactory.getLogger(javaClass.simpleName)

        val vertx = Vertx.vertx()

        // Register Message Types
        vertx.eventBus().registerDefaultCodec(Topic::class.java,
            CodecTopic()
        )

        println("Hello Automation Gateway!")
        //TestTopics.main()

        fun flatten(o: JsonObject): HashMap<String, Any> {
            val result = HashMap<String, Any>()

            val recursion = object {
                fun flattenObject(base: String, o: JsonObject) {
                    o.forEach { kv ->
                        val key = "${base}${kv.key}"
                        when (kv.value) {
                            is JsonObject -> flattenObject("${key}_", kv.value as JsonObject)
                            is JsonArray -> flattenArray("${key}_", kv.value as JsonArray)
                            else -> result[key] = kv.value
                        }
                    }
                }

                fun flattenArray(base: String, o: JsonArray) {
                    o.forEachIndexed { i, v ->
                        val key = "${base}${i}"
                        when (v) {
                            is JsonObject -> flattenObject("${key}_", v as JsonObject)
                            is JsonArray -> flattenArray("${key}_", v as JsonArray)
                            else -> result[key] = v
                        }
                    }
                }
            }

            recursion.flattenObject("", o)
            return result
        }

        val o = JsonObject()
            .put("test1", "value 1")
            .put("test2", "value 2")
            .put("test3", JsonObject()
                .put("test3a", "value 3a")
                .put("test3b", "value 3b"))
            .put("test4", JsonArray().add("value 4a").add("value 4b"))
            .put("test5", JsonArray().add("value 5a").add(JsonObject().put("x1", "v1").put("x2", "v2")))
        val x = flatten(o)
        println(x)

    }
}