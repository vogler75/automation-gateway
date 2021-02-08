package at.rocworks.tests

import at.rocworks.data.Topic
import at.rocworks.data.Value
import graphql.schema.DataFetchingEnvironment
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.observables.ConnectableObservable
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import java.util.concurrent.ArrayBlockingQueue

object Test {
    private val q = ArrayBlockingQueue<Int>(100)

    object Main {
        @JvmStatic
        fun main(args: Array<String>) {
            val j = JsonObject()
            val a = JsonArray()
            a.add("a")
            a.add("b")
            a.add(1)
            j.put("test", JsonArray())

            var x = a.map { if (it is String) it else null}
            x.forEach { println(it) }
        }
    }
}