package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.DataPoint

interface ILoggerQueue {
    fun isQueueFull() : Boolean
    fun getSize(): Int
    fun getCapacity(): Int

    fun add(dp: DataPoint)

    fun pollBlock(handler: (DataPoint)->Unit): Int

    fun pollCommit()
}