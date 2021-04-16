import org.apache.ignite.cache.query.annotations.QuerySqlField
import java.sql.Timestamp

class OpcValueHistory(value: OpcValue) {
    fun key(): String = "$systemName/$nodeId/$sourceTime"
    @QuerySqlField(
        index = true, orderedGroups = [QuerySqlField.Group(
            name = "opcnodehistory_unique_idx",
            order = 0,
            descending = true
        )]
    )
    val systemName: String = value.systemName

    @QuerySqlField(
        index = true, orderedGroups = [QuerySqlField.Group(
            name = "opcnodehistory_unique_idx",
            order = 1,
            descending = true
        )]
    )
    val nodeId: String = value.nodeId

    @QuerySqlField(
        index = true, orderedGroups = [QuerySqlField.Group(
            name = "opcnodehistory_unique_idx",
            order = 2,
            descending = true
        )]
    )
    val sourceTime: Timestamp = value.sourceTime

    @QuerySqlField
    val stringValue: String = value.stringValue

    @QuerySqlField
    val doubleValue: Double? = value.doubleValue
}