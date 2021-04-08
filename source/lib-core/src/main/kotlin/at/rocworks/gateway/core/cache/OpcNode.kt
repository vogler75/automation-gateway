package at.rocworks.gateway.core.cache

import org.apache.ignite.cache.query.annotations.QuerySqlField
import org.apache.ignite.cache.query.annotations.QuerySqlField.Group

class OpcNode(
    @QuerySqlField(index = true, orderedGroups = [Group(name = "opcnode_pk", order = 0, descending = true)])
    val systemName: String,

    @QuerySqlField(index = true, orderedGroups = [Group(name = "opcnode_pk", order = 1, descending = true)])
    val nodeId: String,

    @QuerySqlField()
    val nodeClass: String,

    @QuerySqlField(index = true)
    val browsePath: String,

    @QuerySqlField(index = true)
    val parentPath: String,

    @QuerySqlField()
    val browseName: String,

    @QuerySqlField()
    val displayName: String,
) {
    fun key(): String = browsePath

    @QuerySqlField()
    val topic: String = "opc/$systemName/node/$nodeId"
}