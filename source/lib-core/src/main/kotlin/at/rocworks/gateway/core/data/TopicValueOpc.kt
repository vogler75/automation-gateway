package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
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


    companion object {
        fun fromJsonObject(json: JsonObject): TopicValueOpc = json.mapTo(TopicValueOpc::class.java)
    }
}
