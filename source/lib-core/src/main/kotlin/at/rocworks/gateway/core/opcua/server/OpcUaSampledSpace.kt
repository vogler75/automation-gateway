package at.rocworks.gateway.core.opcua.server

import at.rocworks.gateway.core.opcua.OpcUaServer
import at.rocworks.gateway.core.service.ComponentLogger
import com.google.common.collect.Maps
import org.eclipse.milo.opcua.sdk.server.OpcUaServer as MiloOpcUaServer
import org.eclipse.milo.opcua.sdk.server.api.*
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import java.util.concurrent.ConcurrentMap

abstract class OpcUaSampledSpace(
    private val config: OpcUaServer,
    server: MiloOpcUaServer,
    composite: AddressSpaceComposite
) : ManagedAddressSpaceFragmentWithLifecycle(server, composite) {
    private val logger = ComponentLogger.getLogger(this::class.java.simpleName)

    private val filter = SimpleAddressSpaceFilter.create {
        nodeManager.containsNode(it)
    }

    private val sampledNodes: ConcurrentMap<DataItem, OpcUaSampledNode> = Maps.newConcurrentMap()

    override fun getFilter(): AddressSpaceFilter {
        return filter
    }

    override fun onDataItemsCreated(items: List<DataItem>) {
        items.forEach { item ->
            logger.fine { "onDataItemsCreated: ${item.readValueId.nodeId}" }
            val nodeId: NodeId = item.readValueId.nodeId
            val node: UaNode? = nodeManager.get(nodeId)

            if (node != null) {
                val sampledNode = OpcUaSampledNode(config.vertx, item, node)
                sampledNode.samplingEnabled = item.isSamplingEnabled
                sampledNode.startup()

                sampledNodes[item] = sampledNode
            }
        }
    }

    override fun onDataItemsModified(items: List<DataItem>) {
        items.forEach { item ->
            logger.fine { "onDataItemsModified: ${item.readValueId.nodeId}" }
            sampledNodes[item]?.modifyRate(item.samplingInterval)
        }
    }

    override fun onDataItemsDeleted(items: List<DataItem>) {
        items.forEach { item ->
            logger.fine { "onDataItemsDeleted: ${item.readValueId.nodeId}" }
            sampledNodes.remove(item)?.shutdown()
        }
    }

    override fun onMonitoringModeChanged(items: List<MonitoredItem>) {
        items.forEach { item ->
            logger.fine { "onMonitoringModeChanged: ${item.readValueId.nodeId} ${item.isSamplingEnabled}" }
            sampledNodes[item]?.samplingEnabled = item.isSamplingEnabled
        }
    }
}
