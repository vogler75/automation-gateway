package at.rocworks.tests


import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.UaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import java.lang.Exception
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayList
import java.util.function.BiConsumer
import kotlin.concurrent.thread

object SubscribeTest {

    val endpointUrl = "opc.tcp://ubuntu1:62541/discovery"
    val identityProvider = UsernameProvider("opcuauser", "password")
    var client: UaClient = createClient()

    @JvmStatic
    fun main(args: Array<String>) {
        var done = false
        client.connect().thenAccept {
            test(client.subscriptionManager.createSubscription(0.0).get())
            println("disconnect...")
            client.disconnect()
            println("done")
            done = true
        }
        while (!done) Thread.sleep(1000)
        println("exit")
    }

    private fun createClient(): OpcUaClient {
        val securityTempDir = Paths.get(System.getProperty("java.io.tmpdir"), "security")
        Files.createDirectories(securityTempDir)
        if (!Files.exists(securityTempDir)) {
            throw Exception("unable to create security dir: $securityTempDir")
        }
        return OpcUaClient.create(
            SubscribeTest.endpointUrl,
            { endpoints: List<EndpointDescription?> ->
                endpoints.stream().findFirst()
            }
        ) { configBuilder: OpcUaClientConfigBuilder ->
            configBuilder
                .setApplicationName(LocalizedText.english("Example"))
                .setApplicationUri("urn:localhost:ROCWORKS.SCADA")
                .setIdentityProvider(SubscribeTest.identityProvider)
                .setRequestTimeout(Unsigned.uint(5000))
                .build()
        }
    }

    private fun test(subscription: UaSubscription) {
        //val nodes = listOf(
        //    NodeId.parse("ns=2;s=ExampleDP_Float.ExampleDP_Arg1"),
        //    NodeId.parse("ns=2;s=ExampleDP_Float.ExampleDP_Arg2"))

        val nodes1 = listOf(
            NodeId.parse("ns=2;s=[default]/Pump_1/flow"),
            NodeId.parse("ns=2;s=[default]/Pump_2/flow"))

        val nodes2 = listOf(
            NodeId.parse("ns=2;s=[default]/Pump_1/flow"))

        // Simulate Values
        val sim = thread {
            try {
                var value = 0.0f
                while (true) {
                    println("")
                    nodes1.forEach {
                        client.writeValue(it, DataValue(Variant(value), null, null))
                    }
                    nodes2.forEach {
                        client.writeValue(it, DataValue(Variant(value), null, null))
                    }
                    value += 1.0f
                    Thread.sleep(2000)
                }
            } catch (e: InterruptedException) {

            }
        }

        // Subscribe to a list of nodes
        fun testSubscribe(nodeIds: List<NodeId>, client: String, base: Int) {
            println("Subscribe nodes [${nodeIds.joinToString(",") { it.toParseableString() }}]")
            val requests = ArrayList<MonitoredItemCreateRequest>()
            for (i in nodeIds.indices) {
                val clientHandle = subscription.nextClientHandle()
                requests.add(
                    MonitoredItemCreateRequest(
                        ReadValueId(nodeIds[i], AttributeId.Value.uid(),null, QualifiedName.NULL_VALUE),
                        MonitoringMode.Reporting,
                        MonitoringParameters(
                            clientHandle,
                            0.0,
                            null,
                            Unsigned.uint(10),
                            true
                        )
                    )
                )
            }

            val onItemCreated =
                BiConsumer { item: UaMonitoredItem, nr: Int ->
                    val handleId = item.clientHandle.toInt()
                    item.setValueConsumer { data: DataValue ->
                        println(DateTime().javaDate.toString()+" consumer: client=$client nr=$nr cId=$handleId mId=${item.monitoredItemId} : ${item.readValueId.nodeId.toParseableString()} => data: ${data.value}")
                    }
                }

            subscription
                .createMonitoredItems(TimestampsToReturn.Both, requests, onItemCreated)
                .thenAccept { monitoredItems: List<UaMonitoredItem> ->
                    try {
                        for (item in monitoredItems) {
                            if (item.statusCode.isGood) {
                                println("Monitored item created for nodeId [${item.readValueId.nodeId}]")
                            } else {
                                println("Failed to create item for nodeId ${item.readValueId.nodeId} (status=${item.statusCode})")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        }

        // Subscribe Client A with two nodes
        testSubscribe(nodes1, "A", 0)

        Thread.sleep(5000)
        println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

        // Subscribe Client A to first node in the list
        testSubscribe(nodes2, "B", 10)

        Thread.sleep(5000)
        sim.interrupt()
    }
}