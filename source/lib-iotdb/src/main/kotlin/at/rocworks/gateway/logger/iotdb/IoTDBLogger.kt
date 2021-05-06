package at.rocworks.gateway.logger.iotdb

import at.rocworks.gateway.core.logger.LoggerBase

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

    override fun open(): Boolean {
        return try {
            session.open()
            logger.info("IoTDB connected.")
            true
        } catch (e: Exception) {
            logger.error("IoTDB connect failed! [{}]", e.message)
            false
        }
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


        fun addDetails(point: DataPoint) {
            try {
                val path = point.topic.systemName+"."+point.topic.browsePath.replace("/", ".")
                val time = point.value.sourceTime().toEpochMilli()
                val value = point.value.valueAsDouble() ?: point.value.valueAsString()
                val status = point.value.statusAsString()
                deviceIds.add("${database}.${path}")
                times.add(time)
                measurementList.add(listOf("value", "status"))
                typesList.add(listOf(TSDataType.DOUBLE, TSDataType.TEXT))
                valuesList.add(listOf(value, status))
            } catch (e: Exception) {
                logger.error(e.message)
            }
        }

        fun addValue(point: DataPoint) {
            try {
                val path = point.topic.systemName+"."+point.topic.browsePath
                    .replace("/", ".")
                    .substringBeforeLast(delimiter = '.')
                val time = point.value.sourceTime().toEpochMilli()
                val value = point.value.valueAsDouble() ?: point.value.valueAsString()
                val valueName = point.topic.browsePath
                    .replace("/", ".")
                    .substringAfterLast(delimiter = '.')

                deviceIds.add("${database}.${path}")
                times.add(time)
                measurementList.add(listOf(valueName))
                typesList.add(listOf(TSDataType.DOUBLE))  // TODO: data types ...
                valuesList.add(listOf(value))
            } catch (e: Exception) {
                logger.error(e.message)
            }
        }

        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && deviceIds.size <= writeParameterBlockSize) {
            if (writeDetails) addDetails(point) else addValue(point)
            point = writeValueQueue.poll()
        }
        if (deviceIds.size > 0) {
            session.insertRecords(deviceIds, times, measurementList, typesList, valuesList)
            valueCounterOutput+=deviceIds.size
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeNano: Long,
        toTimeNano: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}