package at.rocworks.gateway.core.service

import java.util.LinkedList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class ComponentLogger(private val className: String, private val id: String) : Handler() {
    private val max = 1000
    private val log = LinkedList<LogRecord>()

    companion object {
        private val loggers = mutableMapOf<String, ComponentLogger>()

        private fun getId(className: String, id: String="") = "$className"+(if (id.isNotEmpty()) "/$id" else "")

        fun getLogger(className: String, id: String=""): Logger {
            val logger = Logger.getLogger(getId(className, id))
            val memoryHandler = ComponentLogger(className, id)
            logger.addHandler(memoryHandler)
            return logger
        }

        private fun addLogger(className: String, id: String, logger: ComponentLogger) = loggers.put(getId(className, id), logger)
        private fun delLogger(className: String, id: String) = loggers.remove(getId(className, id))

        fun getMessages(className: String, id: String, last: Int): List<LogRecord> {
            return loggers[getId(className, id)]?.getMessages(last) ?: listOf()
        }
    }

    init {
        addLogger(className, id, this)
    }

    override fun publish(record: LogRecord) {
        log.add(record)
        if (log.size > max)
            log.removeFirst()
    }

    override fun flush() {
        log.clear()
    }

    override fun close() {
        log.clear()
        delLogger(className, id)
    }

    fun getMessages(last: Int): List<LogRecord> {
        return if (last<=0) {
            log
        }
        else {
            log.takeLast(last)
        }
    }
}