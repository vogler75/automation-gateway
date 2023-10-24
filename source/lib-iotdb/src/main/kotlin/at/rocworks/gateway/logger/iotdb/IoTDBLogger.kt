package at.rocworks.gateway.logger.iotdb

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit

import org.apache.iotdb.session.Session
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType
import java.util.*
import kotlin.collections.LinkedHashMap

class IoTDBLogger(config: JsonObject) : LoggerBase(config) {
    private val host = config.getString("Host", "localhost")
    private val port = config.getInteger("Port", 6667)
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "root.test")

    private val unhandledTypes = mutableListOf<String>()

    private val session: Session = if (username == null || username == "")
        Session(host, port)
    else
        Session(host, port, username, password)

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        try {
            session.open()
            logger.info("IoTDB connected.")
            promise.complete()
        } catch (e: Exception) {
            logger.severe("IoTDB connect failed! [${e.message}]")
            promise.fail(e)
        }
        return promise.future()
    }

    override fun close() {
        session.close()
    }

    override fun writeExecutor() {
        val deviceIds = mutableListOf<String>()
        val times = mutableListOf<Long>()
        val measurementList = mutableListOf<List<String>>()
        val typesList = mutableListOf<List<TSDataType>>()
        val valuesList = mutableListOf<List<Any>>()

        fun getDataTypeAndValue(point: DataPoint): Pair<TSDataType?, Any?> {
            if (point.value.hasValue()) {
                return when (val value = point.value.valueAsObject()!!) {
                    is String -> TSDataType.TEXT to value
                    is Double -> if (!value.isNaN()) TSDataType.DOUBLE to value.toDouble() else null to null
                    is Float -> if (!value.isNaN()) TSDataType.FLOAT to value.toFloat() else null to null
                    is Int -> TSDataType.INT32 to value.toInt()
                    is Byte -> TSDataType.INT32 to value.toInt()
                    is Short -> TSDataType.INT32 to value.toInt()
                    is Long -> TSDataType.INT64 to value.toLong()
                    is Boolean -> TSDataType.BOOLEAN to value
                    is Date -> TSDataType.INT64 to value.time
                    is UUID -> TSDataType.TEXT to value.toString()
                    is LinkedHashMap<*, *> -> {
                        val map = value.entries.associate { item -> item.key.toString() to item.value }
                        TSDataType.TEXT to JsonObject(map).toString()
                    }
                    else -> {
                        val type=value.javaClass.canonicalName
                        val hash=type+"::"+point.topic.hashCode()
                        if (!unhandledTypes.contains(hash)) {
                            logger.warning("Unhandled value datatype [${type}] for ${point.topic}!")
                            unhandledTypes.add(hash)
                        }
                        null to null
                    }
                }
            } else {
                return null to null
            }
        }

        fun addValue(point: DataPoint) {
            try {
                val time = point.value.sourceTime().toEpochMilli()
                val (path, name) = if (point.topic.hasBrowsePath) {
                    val input = point.topic.systemNameAndPath.replace("/", ".")
                    input.substringBeforeLast(delimiter = '.') to input.substringAfterLast(delimiter = '.')
                } else {
                    point.topic.node to "value"
                }

                val (dataType, value) = getDataTypeAndValue(point)
                if (dataType != null && value != null) {
                    deviceIds.add("${database}.${path}")
                    times.add(time)
                    measurementList.add(listOf(name))
                    typesList.add(listOf(dataType))
                    valuesList.add(listOf(value))
                }
            } catch (e: Exception) {
                logger.severe(e.message)
            }
        }

        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && deviceIds.size <= writeParameterBlockSize) {
            addValue(point)
            point = writeValueQueue.poll()
        }
        if (deviceIds.size > 0) {
            try {
                session.insertRecords(deviceIds, times, measurementList, typesList, valuesList)
                valueCounterOutput += deviceIds.size
            } catch (e: Exception) {
                logger.severe("Error writing records [${e.message}]")
            }
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}