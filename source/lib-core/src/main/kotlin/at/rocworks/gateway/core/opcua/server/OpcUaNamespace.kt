package at.rocworks.gateway.core.opcua.server

import org.eclipse.milo.opcua.sdk.core.AccessLevel
import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.Lifecycle
import org.eclipse.milo.opcua.sdk.server.LifecycleManager
import org.eclipse.milo.opcua.sdk.server.OpcUaServer as MiloOpcUaServer
import org.eclipse.milo.opcua.sdk.server.api.AddressSpaceComposite
import org.eclipse.milo.opcua.sdk.server.api.Namespace
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode
import org.eclipse.milo.opcua.stack.core.BuiltinDataType
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import java.util.*

class OpcUaNamespace(server: MiloOpcUaServer) : AddressSpaceComposite(server), Namespace, Lifecycle {

    companion object {
        const val NAMESPACE_URI: String = "urn:rocworks:automation-gateway:opcua:server"
    }

    private val lifecycleManager = LifecycleManager()

    private val namespaceIndex: UShort = server.namespaceTable.addUri(NAMESPACE_URI)

    init {
        lifecycleManager.addLifecycle(object : Lifecycle {
            override fun startup() {
                server.addressSpaceManager.register(this@OpcUaNamespace)
            }

            override fun shutdown() {
                server.addressSpaceManager.unregister(this@OpcUaNamespace)
            }
        })
    }

    override fun startup() {
        lifecycleManager.startup()
    }

    override fun shutdown() {
        lifecycleManager.shutdown()
    }

    override fun getNamespaceUri(): String {
        return NAMESPACE_URI
    }

    override fun getNamespaceIndex(): UShort {
        return namespaceIndex
    }
}