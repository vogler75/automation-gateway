package at.rocworks.gateway.logger.iotdb

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.impl.BufferImpl
import io.vertx.core.json.JsonObject
import org.apache.iotdb.session.Session
import org.apache.tsfile.enums.TSDataType
import java.sql.SQLException
import java.time.Instant
import java.util.*


class IoTDBLogger(config: JsonObject) : LoggerBase(config) {
    private val host = config.getString("Host", "localhost")
    private val port = config.getInteger("Port", 6667)
    private val username = config.getString("Username", "root")
    private val password = config.getString("Password", "root")
    private val database = config.getString("Database", "root.gateway")

    private val unhandledTypes = mutableListOf<String>()

    private val writeSession = getSession()
    private val readSession = getSession()

    private var enabled = false

    private fun getSession() = if (username == null || username == "")
            Session(host, port)
        else
            Session(host, port, username, password)

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        try {
            readSession.open()
            writeSession.open()
            enabled = true
            logger.info("IoTDB connected.")
            promise.complete()
        } catch (e: Exception) {
            logger.severe("IoTDB connect failed! [${e.message}]")
            enabled = false
            promise.fail(e)
        }
        return promise.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        readSession.close()
        writeSession.close()
        enabled = false
        promise.complete()
        return promise.future()
    }

    override fun isEnabled(): Boolean {
        return enabled
    }

    private fun nameToPath(path: String) =  path.replace(Regex("[/;:\\.\\-= ]"), "_")
    private fun namesToPath(path: List<String>) = path.joinToString(".") { nameToPath(it) }

    private fun getPath(point: DataPoint) =
        point.topic.systemName + "." +
        when (point.topic.systemType) {
            Topic.SystemType.Opc -> namesToPath(point.topic.getBrowsePathOrNode().toList())
            Topic.SystemType.Mqtt -> namesToPath(point.topic.getBrowsePathOrNode().toList())
            Topic.SystemType.Plc -> "node."+nameToPath(point.topic.topicNode)
            Topic.SystemType.Sys -> TODO()
            Topic.SystemType.Unknown -> TODO()
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
                    is BufferImpl -> TSDataType.TEXT to value.toString()
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
                val path = getPath(point)

                val (dataType, value) = getDataTypeAndValue(point)
                if (dataType != null && value != null) {
                    deviceIds.add("${database}.${path}")
                    times.add(time)
                    measurementList.add(listOf("value", "status", "servertime"))
                    typesList.add(listOf(dataType, TSDataType.TEXT, TSDataType.TEXT))
                    valuesList.add(listOf(value, point.value.statusAsString(), point.value.serverTimeAsISO()))
                }
            } catch (e: Exception) {
                logger.severe(e.message)
            }
        }

        pollDatapointBlock {
            addValue(it)
        }

        if (deviceIds.size > 0) {
            try {
                writeSession.insertRecords(deviceIds, times, measurementList, typesList, valuesList)
                commitDatapointBlock()
                valueCounterOutput += deviceIds.size
            } catch (e: Exception) {
                logger.severe("Error writing batch [${e.message}]")
            }
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any?>>?) -> Unit // [[source time, server time, value, status code]]
    ) {
        val path = "$database.$system.**.${nameToPath(nodeId)}"
        val paths = listOf("$path.servertime", "$path.value", "$path.status")
        try {
            readSession.executeRawDataQuery(paths, fromTimeMS, toTimeMS).use { dataSet ->
                val data = mutableListOf<List<Any>>()
                dataSet.fetchSize = 1024
                while (dataSet.hasNext()) {
                    val row = dataSet.next()
                    data.add(
                        listOf(
                            Instant.ofEpochMilli(row.timestamp), // source time
                            Instant.parse(row.fields[0].toString()), // server time
                            row.fields[1].toString(), // value
                            row.fields[2].toString() // status
                        )
                    )
                }
                result(true, data)
            }
        } catch (e: SQLException) {
            logger.severe("Error executing query [${e.message}]")
            result(false, null)
        }
    }
}