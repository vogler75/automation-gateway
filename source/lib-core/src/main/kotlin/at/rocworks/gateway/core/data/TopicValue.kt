package at.rocworks.gateway.core.data

import io.vertx.core.json.JsonObject
import java.lang.Exception
import java.time.Instant

abstract class TopicValue {
    val className: String = this.javaClass.simpleName // must be public to be added in json

    fun encodeToJson(): JsonObject = JsonObject.mapFrom(this)

    abstract fun hasValue(): Boolean

    abstract fun valueAsObject(): Any?
    abstract fun statusAsString(): String
    abstract fun valueAsString(): String
    open fun valueAsDouble(): Double? = when (val value = valueAsObject()) {
        is Boolean -> if (value) 1.0 else 0.0
        else -> valueAsString().toDoubleOrNull()
    }
    abstract fun sourceTime(): Instant
    abstract fun serverTime(): Instant

    fun sourceTimeMs(): Long = sourceTime().toEpochMilli()
    fun serverTimeMs(): Long = serverTime().toEpochMilli()

    fun serverTimeAsISO(): String = serverTime().toString()
    fun sourceTimeAsISO(): String = sourceTime().toString()

    open fun dataTypeName(): String = valueAsObject()?.javaClass?.simpleName ?: ""

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValue {
            return when (val objectClassName = json.getString("className", "")) {
                TopicValueOpc::class.java.simpleName -> TopicValueOpc.fromJsonObject(json)
                TopicValuePlc::class.java.simpleName -> TopicValuePlc.fromJsonObject(json)
                else -> throw Exception("Unhandled class [$objectClassName] in JsonObject!")
            }
        }
    }
}