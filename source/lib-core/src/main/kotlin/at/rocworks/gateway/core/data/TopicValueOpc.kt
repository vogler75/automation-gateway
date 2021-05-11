package at.rocworks.gateway.core.data


import io.vertx.core.json.JsonObject
import org.apache.ignite.cache.query.annotations.QuerySqlField
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import java.time.Instant

data class TopicValueOpc (
    val value: Any?,
    val statusCode: Long,
    val sourceTime: Instant,
    val serverTime: Instant,
    val sourcePicoseconds: Int = 0,
    val serverPicoseconds: Int = 0
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null,0, Instant.MIN, Instant.MIN, 0, 0)

    override fun hasValue() = value!=null

    override fun valueAsObject() = value
    override fun statusAsString() = statusCode.toString()
    override fun valueAsString() = value?.toString() ?: ""

    override fun serverTime() = serverTime
    override fun sourceTime() = sourceTime

    override fun hasStruct() = false

    override fun asFlatMap(): Map<String, Any> {
        return if (value!=null) mapOf(Pair("value", value)) else mapOf()
    }

    companion object {
        fun fromDataValue(v: DataValue): TopicValueOpc {
            return TopicValueOpc(
                value = if (v.value.isNotNull) v.value.value else null,
                statusCode = v.statusCode?.value ?: StatusCode.BAD.value,
                sourceTime = v.sourceTime?.javaInstant ?: Instant.MIN,
                serverTime = v.serverTime?.javaInstant ?: Instant.MIN,
                sourcePicoseconds = v.sourcePicoseconds?.toInt() ?: 0,
                serverPicoseconds = v.serverPicoseconds?.toInt() ?: 0,
            )
        }

        fun fromJsonObject(json: JsonObject): TopicValueOpc = json.mapTo(TopicValueOpc::class.java)
    }
}
