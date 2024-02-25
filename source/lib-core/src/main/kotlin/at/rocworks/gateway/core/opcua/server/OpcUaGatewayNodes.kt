package at.rocworks.gateway.core.opcua.server

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicStatus
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.opcua.OpcUaServer
import at.rocworks.gateway.core.service.ComponentLogger
import io.vertx.core.buffer.impl.BufferImpl
import org.eclipse.milo.opcua.sdk.core.AccessLevel
import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.OpcUaServer as MiloOpcUaServer
import org.eclipse.milo.opcua.sdk.server.api.AddressSpaceComposite
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode
import org.eclipse.milo.opcua.stack.core.BuiltinDataType
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import java.time.Instant


class OpcUaGatewayNodes(
    private val config: OpcUaServer,
    private val server: MiloOpcUaServer,
    composite: AddressSpaceComposite,
    private val namespaceIndex: UShort
) : OpcUaSampledSpace(config, server, composite) {
    private val logger = ComponentLogger.getLogger(this::class.java.simpleName)

    init {
        lifecycleManager.addStartupTask {            
        }
    }

    private fun inverseReferenceTo(node: UaNode, targetNodeId: NodeId, typeId: NodeId) {
        node.addReference(
            Reference(
                node.nodeId,
                typeId,
                targetNodeId.expanded(),
                Reference.Direction.INVERSE
            )
        )
    }

    private val folderNodes = mutableMapOf<String, NodeId>()
    private val variableTopics = mutableMapOf<String, Topic>()
    private val variableNodesID = mutableMapOf<String, UaVariableNode>()
    private val variableNodesTS = mutableMapOf<String, DateTime?>()

    fun setDataPoint(data: DataPoint)  {
        val id = Integer.toHexString(data.topic.encodeToJson().toString().hashCode())
        val value = getDataValue(data.value)
        val node = variableNodesID[id]
        if (node != null) {
            setVariableValue(id, node, value)
        } else {
            newVariableValue(id, data.topic, value)
        }
    }

    private fun newVariableValue(id: String, topic: Topic, dataValue: DataValue) {
        addVariableFolder(topic)?.let { (name, folder) ->
            val dataTypeId = if (dataValue.value.dataType.isPresent)
                dataValue.value.dataType.get().toNodeId(server.namespaceTable).get()
            else BuiltinDataType.Variant.nodeId
            val variableNode = addVariableNode(
                folder,
                name,
                dataTypeId = dataTypeId,
                dataValue = dataValue,
                nodeId = NodeId(namespaceIndex, id)
            )

            variableTopics[id] = topic
            variableNodesID[id] = variableNode
            variableNodesTS[id] = dataValue.serverTime

            variableNode.addAttributeObserver { node, _, data ->
                if (node is UaVariableNode && data is DataValue) {
                    variableHasChanged(id, node, data)
                }
            }
        }
    }

    private fun addVariableFolder(topic: Topic): Pair<String, NodeId>? {
        val parts = when (topic.topicType) {
            Topic.TopicType.Node -> listOf(
                topic.systemType.toString(),
                topic.systemName,
                "Nodes",
                topic.topicNode
            )

            Topic.TopicType.Path -> listOf(
                topic.systemType.toString(),
                topic.systemName
            ) + topic.getBrowsePathOrNode().toList()

            Topic.TopicType.Unknown -> TODO()
        }
        return if (parts.size < 3)
            null
        else {
            val name = parts.last()
            val node = parts.dropLast(1).fold(Identifiers.ObjectsFolder) { parent, item ->
                val path = "${parent.identifier}/${item}"
                if (folderNodes[path] == null) {
                    val folder = addFolderNode(parent, item)
                    folderNodes[path] = folder.nodeId
                    folder.nodeId
                } else {
                    folderNodes[path]
                }
            }
            name to node
        }
    }

    private fun addVariableNode(
        parent: NodeId,
        name: String,
        dataTypeId: NodeId,
        dataValue: DataValue,
        nodeId: NodeId = NodeId(namespaceIndex, parent.identifier.toString()+"/"+name),
        referenceTypeId: NodeId = Identifiers.HasComponent
    ): UaVariableNode {
        val variableNode = UaVariableNode.UaVariableNodeBuilder(nodeContext).run {
            setNodeId(nodeId)
            setAccessLevel(AccessLevel.toValue(AccessLevel.READ_WRITE))
            setUserAccessLevel(AccessLevel.toValue(AccessLevel.READ_WRITE))
            setBrowseName(QualifiedName(parent.namespaceIndex, name))
            setDisplayName(LocalizedText.english(name))
            setDataType(dataTypeId)
            setTypeDefinition(Identifiers.BaseDataVariableType)
            setMinimumSamplingInterval(100.0)
            setValue(dataValue)
            build()
        }

        nodeManager.addNode(variableNode)

        inverseReferenceTo(
            variableNode,
            parent,
            referenceTypeId
        )

        return variableNode
    }

    private fun setVariableValue(
        id: String,
        variableNode: UaVariableNode,
        dataValue: DataValue
    ) {
        variableNodesTS[id] = dataValue.serverTime // so that we know in the observer that it was set by us
        variableNode.value = dataValue
    }

    private fun variableHasChanged(id: String, variableNode: UaVariableNode, dataValue: DataValue)
    {
        val tsOld = variableNodesTS[id]?.javaTime
        val tsNew = dataValue.serverTime?.javaTime
        if (tsOld != tsNew) { // check if it was changed by a client (not by our server itself)
            logger.finest {"variableHasChanged: ${variableNode.nodeId}" }
            publishVariableValue(id, dataValue)
        }
    }

    private fun publishVariableValue(id: String, dataValue: DataValue) {
        variableTopics[id]?.let { topic ->
            val data = TopicValue(
                value = dataValue.value.value,
                statusCode = if (dataValue.statusCode?.isGood == true) TopicStatus.GOOD else TopicStatus.BAD,
                sourceTime = dataValue.sourceTime?.javaInstant ?: Instant.now(),
                serverTime = dataValue.serverTime?.javaInstant ?: Instant.now()
            )
            config.writeValue(DataPoint(topic, data))
        }
    }

    private fun getDataValue(data: TopicValue): DataValue {
        val value = when (data.value) {
            is BufferImpl -> Variant(data.value.toString()) // MQTT Driver with RAW Format sends a BufferImpl
            else -> Variant(data.value)
        }
        return DataValue(
            value,
            getStatusCode(data.statusCode),
            DateTime(data.sourceTime),
            DateTime(data.serverTime)
        )
    }

    private fun getStatusCode(status: String): StatusCode {
        return if (status.isNullOrEmpty() || status == TopicStatus.GOOD) StatusCode.GOOD else StatusCode.BAD
    }


    private fun addFolderNode(parentNodeId: NodeId, name: String): UaFolderNode {
        val folderNode = UaFolderNode(
            nodeContext,
            NodeId(namespaceIndex, parentNodeId.identifier.toString()+"/"+name),
            QualifiedName(parentNodeId.namespaceIndex, name),
            LocalizedText(name)
        )

        nodeManager.addNode(folderNode)

        inverseReferenceTo(
            folderNode,
            parentNodeId,
            Identifiers.HasComponent
        )

        return folderNode
    }
}