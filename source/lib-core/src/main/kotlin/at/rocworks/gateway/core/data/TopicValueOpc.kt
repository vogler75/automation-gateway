package at.rocworks.gateway.core.data


import io.vertx.core.json.JsonObject
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import java.time.Instant

class TopicValueOpc (
    val value: Any?,
    val dataTypeId: Int,
    val statusCode: Long,
    val sourceTime: Instant,
    val serverTime: Instant,
    val sourcePicoseconds: Int = 0,
    val serverPicoseconds: Int = 0
) : TopicValue() {
    // default constructor needed for json to object mapping
    constructor() : this(null, 0, 0, Instant.MIN, Instant.MIN, 0, 0)

    override fun hasValue() = value!=null
    override fun statusAsString() = statusCode.toString()
    override fun valueAsString() = value?.toString() ?: ""

    override fun serverTime() = serverTime
    override fun sourceTime() = sourceTime

    override fun dataTypeName() = when (uint(dataTypeId)) { // Datatypes: org.eclipse.milo.opcua.stack.core.Identifiers
        Identifiers.Argument.identifier -> "Argument"
        Identifiers.BaseDataType.identifier -> "BaseDataType"
        Identifiers.Boolean.identifier -> "Boolean"
        Identifiers.Byte.identifier -> "Byte"
        Identifiers.ByteString.identifier -> "ByteString"
        Identifiers.DateString.identifier -> "DateString"
        Identifiers.DateTime.identifier -> "DateTime"
        Identifiers.DecimalString.identifier -> "DecimalString"
        Identifiers.Double.identifier -> "Double"
        Identifiers.Duration.identifier -> "Duration"
        Identifiers.DurationString.identifier -> "DurationString"
        Identifiers.Enumeration.identifier -> "Enumeration"
        Identifiers.EnumValueType.identifier -> "EnumValueType"
        Identifiers.Float.identifier -> "Float"
        Identifiers.Guid.identifier -> "Guid"
        Identifiers.IdType.identifier -> "IdType"
        Identifiers.Image.identifier -> "Image"
        Identifiers.ImageBMP.identifier -> "ImageBMP"
        Identifiers.ImageGIF.identifier -> "ImageGIF"
        Identifiers.ImageJPG.identifier -> "ImageJPG"
        Identifiers.ImagePNG.identifier -> "ImagePNG"
        Identifiers.Int16.identifier -> "Int16"
        Identifiers.Int32.identifier -> "Int32"
        Identifiers.Int64.identifier -> "Int64"
        Identifiers.Integer.identifier -> "Integer"
        Identifiers.LocaleId.identifier -> "LocaleId"
        Identifiers.LocalizedText.identifier -> "LocalizedText"
        Identifiers.NamingRuleType.identifier -> "NamingRuleType"
        Identifiers.NodeClass.identifier -> "NodeClass"
        Identifiers.NodeId.identifier -> "NodeId"
        Identifiers.NormalizedString.identifier -> "NormalizedString"
        Identifiers.Number.identifier -> "Number"
        Identifiers.OptionSet.identifier -> "OptionSet"
        Identifiers.QualifiedName.identifier -> "QualifiedName"
        Identifiers.SByte.identifier -> "SByte"
        Identifiers.String.identifier -> "String"
        Identifiers.Structure.identifier -> "Structure"
        Identifiers.TimeString.identifier -> "TimeString"
        Identifiers.TimeZoneDataType.identifier -> "TimeZoneDataType"
        Identifiers.UInt16.identifier -> "UInt16"
        Identifiers.UInt32.identifier -> "UInt32"
        Identifiers.UInt64.identifier -> "UInt64"
        Identifiers.UInteger.identifier -> "UInteger"
        Identifiers.Union.identifier -> "Union"
        Identifiers.UtcTime.identifier -> "UtcTime"
        Identifiers.XmlElement.identifier -> "XmlElement"
        else -> "Unknown"
    }

    override fun hasStruct() = false

    override fun asFlatMap(): Map<String, Any> {
        return if (value!=null) mapOf(Pair("value", value)) else mapOf()
    }

    companion object {
        fun fromDataValue(v: DataValue): TopicValueOpc {
            return TopicValueOpc(
                value = if (v.value.isNotNull) v.value.value else null,
                dataTypeId = if (v.value.value == null) -1 else (v.value.dataType.get().identifier as? UInteger)?.toInt()
                    ?: -1,
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
