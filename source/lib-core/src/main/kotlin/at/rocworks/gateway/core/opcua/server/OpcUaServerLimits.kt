package at.rocworks.gateway.core.opcua.server

import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfigLimits
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint


object OpcUaServerLimits : OpcUaServerConfigLimits {

    override fun getMaxSessionCount(): UInteger {
        return uint(1000)
    }

    override fun getMinPublishingInterval(): Double {
        return 100.0
    }

    override fun getDefaultPublishingInterval(): Double {
        return 100.0
    }

}
