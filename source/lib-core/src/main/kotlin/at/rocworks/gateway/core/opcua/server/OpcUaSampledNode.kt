package at.rocworks.gateway.core.opcua.server

import at.rocworks.gateway.core.service.ComponentLogger
import io.vertx.core.Vertx
import org.eclipse.milo.opcua.sdk.server.AbstractLifecycle
import org.eclipse.milo.opcua.sdk.server.api.DataItem
import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn

class OpcUaSampledNode(
    private val vertx: Vertx,
    private val item: DataItem,
    private val node: UaNode
) : AbstractLifecycle() {
    private val logger = ComponentLogger.getLogger(this::class.java.simpleName)

    @Volatile
    var samplingEnabled: Boolean = true

    private var tick: Long = 0L

    override fun onStartup() {
        item.setValue(sampleCurrentValue())
        logger.finest { "onStartup: ${node.nodeId} ${item.samplingInterval}" }
        tick = vertx.setPeriodic(item.samplingInterval.toLong()) {
            tick()
        }
    }

    override fun onShutdown(): Unit = synchronized(this) {
        logger.finest { "onShutdown: ${node.nodeId}" }
        vertx.cancelTimer(tick)
    }

    private fun tick() {
        if (samplingEnabled) {
            try {
                item.setValue(sampleCurrentValue())
            } catch (t: Throwable) {
                logger.severe("Error sampling value for ${item.readValueId}: $t")
                item.setValue(DataValue(StatusCodes.Bad_InternalError))
            }
        }
    }

    fun modifyRate(newRate: Double) {
        logger.fine { "modifyRate: ${node.nodeId} $newRate" }
        vertx.cancelTimer(tick)
        vertx.setPeriodic(newRate.toLong()) {
            tick()
        }
    }

    private fun sampleCurrentValue(): DataValue {
        return node.readAttribute(
            AttributeContext(item.session.server),
            item.readValueId.attributeId,
            TimestampsToReturn.Both,
            item.readValueId.indexRange,
            item.readValueId.dataEncoding
        )
    }
}
