package at.rocworks.data

import io.vertx.core.json.JsonObject
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import java.time.Instant

data class Value(
    val value: Any?,
    val dataTypeId: Int,
    val statusCode: Long,
    val sourceTime: Instant,
    val serverTime: Instant,
    val sourcePicoseconds: Int = 0,
    val serverPicoseconds: Int = 0
) {
    // Datatypes. org.eclipse.milo.opcua.stack.core.Identifiers

    fun serverTimeAsISO() = serverTime.toString()
    fun sourceTimeAsISO() = sourceTime.toString()

    fun encodeToJson() = encodeToJson(this)

    val dataTypeName = when (uint(dataTypeId)) {
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

    companion object {
        fun fromDataValue(v: DataValue): Value {
            return Value(
                value = if (v.value.isNotNull) v.value.value else null,
                dataTypeId = if (v.value.value==null) -1 else (v.value.dataType.get().identifier as? UInteger)?.toInt() ?: -1,
                statusCode = v.statusCode?.value ?: StatusCode.BAD.value,
                sourceTime = v.sourceTime?.javaInstant ?: Instant.MIN,
                serverTime = v.serverTime?.javaInstant ?: Instant.MIN,
                sourcePicoseconds = v.sourcePicoseconds?.toInt() ?: 0,
                serverPicoseconds = v.serverPicoseconds?.toInt() ?: 0,
            )
        }

        fun toDataValue(v: Value): DataValue {
            return DataValue(
                Variant(v.value),
                StatusCode(v.statusCode),
                DateTime(v.sourceTime),
                UShort.valueOf(v.sourcePicoseconds),
                DateTime(v.serverTime),
                UShort.valueOf(v.serverPicoseconds)
            )
        }

        private const val VALUE = "Value"
        private const val DATATYPE = "DataType"
        private const val DATATYPE_ID = "DataTypeId"
        private const val STATUS_CODE = "StatusCode"
        private const val SOURCE_TIME = "SourceTime"
        private const val SERVER_TIME = "ServerTime"
        private const val SERVER_PICOSECONDS = "ServerPicoseconds"
        private const val SOURCE_PICOSECONDS = "SourcePicoseconds"

        fun encodeToJson(v: Value) : JsonObject {
            return JsonObject()
                .put(VALUE, v.value?.toString())
                .put(DATATYPE, v.dataTypeName)
                .put(DATATYPE_ID, v.dataTypeId)
                .put(STATUS_CODE, v.statusCode)
                .put(SOURCE_TIME, v.sourceTimeAsISO())
                .put(SERVER_TIME, v.serverTimeAsISO())
                .put(SERVER_PICOSECONDS, v.serverPicoseconds)
                .put(SOURCE_PICOSECONDS, v.sourcePicoseconds)
        }

        fun decodeFromJson(json: JsonObject): Value {
            return Value(
                value = json.getValue(VALUE, null),
                dataTypeId = json.getInteger(DATATYPE_ID, 0),
                statusCode = json.getLong(STATUS_CODE, StatusCode.BAD.value),
                sourceTime = Instant.parse(json.getString(SOURCE_TIME, null)),
                serverTime = Instant.parse(json.getString(SERVER_TIME, null)),
                serverPicoseconds = json.getInteger(SERVER_PICOSECONDS, 0),
                sourcePicoseconds = json.getInteger(SOURCE_PICOSECONDS, 0),
            )
        }
    }
}
