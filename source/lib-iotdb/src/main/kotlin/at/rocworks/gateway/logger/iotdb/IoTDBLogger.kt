package at.rocworks.gateway.logger.iotdb

import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit

import org.apache.iotdb.session.Session
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType

class IoTDBLogger(config: JsonObject) : LoggerBase(config) {
    private val host = config.getString("Host", "localhost")
    private val port = config.getInteger("Port", 6667)
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "root.scada")
    private val writeDetails = config.getBoolean("WriteDetails", false)

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

        fun getDataTypeAndValue(topicValue: TopicValue): Pair<TSDataType?, Any?> {
            return when (val value = topicValue.valueAsObject()) {
                is String -> Pair(TSDataType.TEXT, value)
                is Double -> if (!value.isNaN()) Pair(TSDataType.DOUBLE, value) else Pair(null, null)
                is Float -> if (!value.isNaN()) Pair(TSDataType.FLOAT, value) else Pair(null, null)
                is Int -> Pair(TSDataType.INT32, value)
                is Long -> Pair(TSDataType.INT64, value)
                is Boolean -> Pair(TSDataType.BOOLEAN, value)
                else -> {
                    logger.warning("Unhandled datatype [${value?.javaClass?.name}]!")
                    Pair(null, null)
                }
            }
        }

        fun addDetails(point: DataPoint) {
            try {
                val time = point.value.sourceTime().toEpochMilli()
                val path = point.topic.systemBrowsePath().replace("/", ".")
                val status = point.value.statusAsString()
                val (dataType, value) = getDataTypeAndValue(point.value)
                if (dataType != null && value != null) {
                    deviceIds.add("${database}.${path}")
                    times.add(time)
                    measurementList.add(listOf("value", "status"))
                    typesList.add(listOf(dataType, TSDataType.TEXT))
                    valuesList.add(listOf(value, status))
                }
            } catch (e: Exception) {
                logger.severe(e.message)
            }
        }

        fun addValue(point: DataPoint) {
            try {
                val time = point.value.sourceTime().toEpochMilli()
                val path = "${point.topic.systemName}.${point.topic.browsePath}"
                    .replace("/", ".")
                    .substringBeforeLast(delimiter = '.')

                val name = point.topic.browsePath
                    .replace("/", ".")
                    .substringAfterLast(delimiter = '.')

                val (dataType, value) = getDataTypeAndValue(point.value)
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
            if (writeDetails) addDetails(point) else addValue(point)
            point = writeValueQueue.poll()
        }
        if (deviceIds.size > 0) {
            try {
                session.insertRecords(deviceIds, times, measurementList, typesList, valuesList)
                valueCounterOutput += deviceIds.size
            } catch (e: Exception) {
                logger.severe("Error writing records [${e.message}]", )
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