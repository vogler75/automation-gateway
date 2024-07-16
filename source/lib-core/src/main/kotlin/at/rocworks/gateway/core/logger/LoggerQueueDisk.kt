package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.DataPoint
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.math.absoluteValue


class LoggerQueueDisk(
    private val id: String,
    private val logger: Logger,
    private val writeParameterQueueSize: Int,
    private val writeParameterBlockSize: Int,
    private val writeQueuePollTimeout: Long
) : ILoggerQueue {
    private var writeValueQueueFull = false

    private val outputBlock = arrayListOf<DataPoint>()
    private val semaphore = Semaphore(0) // Start with 0 permits (blocking initially)

    private val fileSize = writeParameterQueueSize.toLong() // one datapoint is about 1408 bytes
    private val fileName = "${id}.buf"
    private val file: RandomAccessFile = RandomAccessFile(fileName, "rw")
    private val buffer: MappedByteBuffer
    private val lock = ReentrantLock()

    private val startPosition = Int.SIZE_BYTES * 2
    private var writePosition = startPosition
    private var readPosition = startPosition

    init {
        if (file.length() != writeParameterQueueSize.toLong()) {
            file.setLength(fileSize)
            buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, fileSize)
            writeReadPosition()
            writeWritePosition()
        } else {
            buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, fileSize)
            buffer.position(0)
            readPosition = buffer.int
            writePosition = buffer.int
            logger.info("Initial position read $readPosition write $writePosition")
        }
    }

    private fun enqueue(dp: DataPoint): Boolean {
        lock.lock()
        //println("> ${dp.value.valueAsString()}")
        try {
            val dataBytes = serialize(dp)
            val dataSize = dataBytes.size + Int.SIZE_BYTES
            logger.finest { "enqueue: ReadPos $readPosition WritePos $writePosition Data size: ${dataBytes.size} " }

            if (writePosition + dataSize > file.length()) {
                buffer.position(writePosition)
                buffer.putInt(0) // mark EOF
                writePosition = startPosition
            }
            if (writePosition < readPosition && writePosition + dataSize >= readPosition) {
                return false
            } else {
                buffer.position(writePosition)
                buffer.putInt(dataBytes.size)
                buffer.put(dataBytes)
                writePosition += dataSize
                writeWritePosition()
                return true
            }
        } finally {
            lock.unlock()
        }
    }

    private fun writeReadPosition() {
        buffer.position(0)
        buffer.putInt(readPosition)
    }

    private fun writeWritePosition() {
        buffer.position(Int.SIZE_BYTES)
        buffer.putInt(writePosition)
    }

    private fun dequeue(): DataPoint? {
        lock.lock()
        try {
            if (readPosition == writePosition) {
                return null // Queue is empty
            }
            logger.finest { "dequeue: ReadPos $readPosition WritePos $writePosition" }

            buffer.position(readPosition)
            var dataSize = buffer.int
            if (dataSize == 0) { // EOF
                readPosition = startPosition
                if (writePosition == startPosition) {
                    return null // Queue is empty
                } else {
                    buffer.position(startPosition)
                    dataSize = buffer.int
                }
            }
            val dataBytes = ByteArray(dataSize)
            buffer.get(dataBytes)
            val dp = deserialize(dataBytes)

            readPosition += dataSize + Int.SIZE_BYTES

            //println("< ${dp.value.valueAsString()}")

            return dp
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        buffer.force()
        file.close()
    }

    override fun isQueueFull(): Boolean {
        return writeValueQueueFull;
    }

    override fun getCapacity(): Int {
        return writeParameterQueueSize
    }

    override fun getSize(): Int {
        val size = writePosition - readPosition
        return if (size >= 0) size
        else writeParameterQueueSize + size
    }

    override fun add(dp: DataPoint) {
        if (enqueue(dp)) {
            if (writeValueQueueFull) {
                writeValueQueueFull = false
                logger.warning("Logger write queue not full anymore. [${getSize()}]")
            }
        } else {
            if (!writeValueQueueFull) {
                writeValueQueueFull = true
                logger.warning("Logger write queue is full! [${getSize()}]")
            }
        }
        semaphore.release()
    }

    override fun pollBlock(handler: (DataPoint) -> Unit): Int {
        if (outputBlock.isNotEmpty()) {
            logger.warning("Repeat last data block.")
            outputBlock.forEach(handler)
            return outputBlock.size
        } else {
            var point: DataPoint? = dequeue()
            if (point == null) {
                if (semaphore.tryAcquire(writeQueuePollTimeout, TimeUnit.MILLISECONDS)) {
                    point = dequeue()
                }
            }
            while (point != null) {
                if (point.value.sourceTime.epochSecond > 0) {
                    outputBlock.add(point)
                    handler(point)
                }
                point = if (outputBlock.size < writeParameterBlockSize) dequeue() else null
            }
            return outputBlock.size
        }
    }

    override fun pollCommit() {
        lock.lock()
        try {
            outputBlock.clear()
            writeReadPosition()
        } finally {
            lock.unlock()
        }
    }

    private fun serialize(dp: DataPoint) : ByteArray {
        ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos ->
                oos.writeObject(dp)
                return baos.toByteArray()
            }
        }
    }

    private fun deserialize(serializedData: ByteArray) : DataPoint {
        ByteArrayInputStream(serializedData).use { bais ->
            ObjectInputStream(bais).use { ois ->
                return ois.readObject() as DataPoint
            }
        }
    }
}