package at.rocworks.gateway.logger.snowflake

import at.rocworks.gateway.core.logger.LoggerBase

import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject

import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

import net.snowflake.ingest.streaming.OpenChannelRequest
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClientFactory

/*
CREATE OR REPLACE SCHEMA gateway;
CREATE TABLE IF NOT EXISTS gateway.gateway (
  system character varying(1000) NOT NULL,
  address character varying(1000) NOT NULL,
  sourcetime timestamp with time zone NOT NULL,
  servertime timestamp with time zone NOT NULL,
  numericvalue numeric,
  stringvalue text,
  status character varying(30),
  CONSTRAINT gateway_pk PRIMARY KEY (system, datapoint, sourcetime)
  );
*/

class SnowflakeLogger(config: JsonObject) : LoggerBase(config) {
    private val privateKeyFile = config.getString("PrivateKeyFile", "rsa_key.p8")
    private val account = config.getString("Account", "xx00000")
    private val url = config.getString("Url", "https://xx00000.eu-central-1.snowflakecomputing.com:443")
    private val user = config.getString("User", "xxxxxx")
    private val role = config.getString("Role", "accountadmin")
    private val scheme = config.getString("Scheme", "https")
    private val port = config.getInteger("Port", 443)
    private val database = config.getString("Database", "SCADA")
    private val schema = config.getString("Schema", "SCADA")
    private val table = config.getString("Table", "GATEWAY")

    private val privateKeyPath = Paths.get(privateKeyFile)
    private val privateKeyContent = Files.readAllBytes(privateKeyPath).toString(Charsets.UTF_8)
    private val privateKey = privateKeyContent
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")

    private var channel: SnowflakeStreamingIngestChannel? = null

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            logger.info("Snowflake connect to $url")

            val props = Properties()
            props.put("account", account)
            props.put("url", url)
            props.put("user", user)
            props.put("private_key", privateKey)
            props.put("role", role)
            props.put("scheme", scheme)
            props.put("port", port.toString())

            logger.info("Create a client...")
            val client = SnowflakeStreamingIngestClientFactory.builder("client1").setProperties(props).build()

            logger.info("Open a channel...")
            val request = OpenChannelRequest.builder("channel1")
                .setDBName(database)
                .setSchemaName(schema)
                .setTableName(table)
                .setOnErrorOption(OpenChannelRequest.OnErrorOption.ABORT)
                .build();

            // Open a streaming ingest channel from the given client
            channel = client.openChannel(request);

            logger.info("Snowflake connected.")
            result.complete()

        } catch (e: Exception) {
            logger.severe("Snowflake connect failed! [${e.message}]")
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()


        promise.complete()
        return promise.future()
    }

    override fun isEnabled(): Boolean {
        return channel != null
    }

    override fun writeExecutor() {
        try {
            val records = ArrayList<Map<String, Any?>>()
            val size = pollDatapointBlock { point ->
                val record = HashMap<String, Any?>()
                record.put("SYSTEM", point.topic.systemName)
                record.put("ADDRESS", point.topic.getBrowsePathOrNode().toString())
                record.put("SOURCETIME", point.value.sourceTime.toString())
                record.put("SERVERTIME", point.value.serverTime.toString())
                record.put("STRINGVALUE", point.value.stringValue())
                record.put("NUMERICVALUE", point.value.valueAsDouble())
                records.add(record)
            }
            if (size > 0) {
                try {
                    channel?.let { channel ->
                        for ((i, record) in records.withIndex()) {
                            val response = channel.insertRow(record, i.toString())
                            if (response.hasErrors()) {
                                logger.warning("Error inserting row: " + response.insertErrors.first().message)
                            }
                        }
                    }
                    commitDatapointBlock()
                    valueCounterOutput += size
                } catch (e: Exception) {
                    logger.severe("Error writing batch [${e.message}]")
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            logger.severe("Error writing batch [${e.message}]")
            e.printStackTrace()
        }
    }
}