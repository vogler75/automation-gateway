import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject

import java.util.logging.Level
import java.util.logging.Logger
import org.slf4j.LoggerFactory

class AppVerticle(config: JsonObject): AbstractVerticle() {
    private val id = config.getString("Id", "InfluxDB")
    private val logger = LoggerFactory.getLogger(id)

    init {
        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
    }

    override fun start(startPromise: Promise<Void>) {
        logger.info("Starting...")
    }

    override fun stop(stopPromise: Promise<Void>) {
        logger.info("Stopping...")
    }
}