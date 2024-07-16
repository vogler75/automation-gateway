package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.DataPoint
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class LoggerQueueMemory(
    private val logger: Logger,
    private val writeParameterQueueSize: Int,
    private val writeParameterBlockSize: Int,
    private val writeQueuePollTimeout: Long
) : ILoggerQueue {
    private val writeValueQueue = ArrayBlockingQueue<DataPoint>(writeParameterQueueSize)
    private var writeValueQueueFull = false

    private val outputBlock = arrayListOf<DataPoint>()

    override fun isQueueFull(): Boolean = writeValueQueueFull
    override fun getCapacity(): Int = writeParameterQueueSize
    override fun getSize(): Int = writeValueQueue.size

    override fun add(dp: DataPoint) {
        try {
            writeValueQueue.add(dp)
            if (writeValueQueueFull) {
                writeValueQueueFull = false
                logger.warning("Logger write queue not full anymore. [${getSize()}]")
            }
        } catch (e: IllegalStateException) {
            if (!writeValueQueueFull) {
                writeValueQueueFull = true
                logger.warning("Logger write queue is full! [${getSize()}]")
            }
        }
    }

    private fun pollWait(): DataPoint? = writeValueQueue.poll(writeQueuePollTimeout, TimeUnit.MILLISECONDS)

    private fun pollNoWait(): DataPoint? = writeValueQueue.poll()

    override fun pollBlock(handler: (DataPoint)->Unit): Int {
        if (outputBlock.isNotEmpty()) {
            logger.warning("Repeat last data block.")
            outputBlock.forEach(handler)
            return outputBlock.size
        } else {
            var point: DataPoint? = pollWait()
            while (point != null) {
                if (point.value.sourceTime.epochSecond > 0) {
                    outputBlock.add(point)
                    handler(point)
                }
                point = if (outputBlock.size < writeParameterBlockSize) pollNoWait() else null
            }
            return outputBlock.size
        }
    }

    override fun pollCommit() {
        outputBlock.clear()
    }
}