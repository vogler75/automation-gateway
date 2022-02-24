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

        val vertx = Vertx.vertx()

        // Register Message Types
        vertx.eventBus().registerDefaultCodec(Topic::class.java,
            CodecTopic()
        )

        println("Hello Automation Gateway!")
        TestTopics.main()
    }
}