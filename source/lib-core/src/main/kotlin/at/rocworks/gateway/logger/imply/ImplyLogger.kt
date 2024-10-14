package at.rocworks.gateway.logger.imply

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import java.util.concurrent.TimeUnit

class ImplyLogger(config: JsonObject) : LoggerBase(config) {
    private val projectId = config.getString("ProjectId", "Frankenstein")
    private val host = config.getString("Host", "ORGANIZATION_NAME.REGION.CLOUD_PROVIDER.api.imply.io")
    private val connectionName = config.getString("ConnectionName", "frankenstein_connection")
    private val tableName = config.getString("DataSource", "frankenstein")

    private val apiKey = config.getString("ApiKey", "")

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        createConnection().onSuccess {
            logger.info("Connection to Druid created successfully.")
            createIngestionJob().onSuccess {
                logger.info("Ingestion job created successfully.")
                promise.complete()
            }.onFailure {
                promise.fail(it)
            }
        }.onFailure {
            promise.fail(it)
        }
        return promise.future()
    }

    private fun createConnection(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        val url = "https://$host/v1/projects/$projectId/connections"
        logger.info("URL: $url")
        val payload = JsonObject().put("type", "push_streaming").put("name", connectionName)
        val clientOptions = WebClientOptions().setSsl(true).setDefaultPort(443)
        val client = WebClient.create(vertx, clientOptions)
        client.postAbs(url)
            .putHeader("Authorization", "Basic $apiKey")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload) { ar ->
                if (ar.succeeded()) {
                    val response = ar.result()
                    logger.info("Response: ${response.statusCode()}: ${response.bodyAsString()}")
                    if (response.statusCode() == 409 || response.statusCode() in 200..299) {
                        promise.complete()
                    } else {
                        val message =
                            "Failed to create connection to Druid: ${response.statusCode()}: ${response.statusMessage()}"
                        logger.warning(message)
                        promise.fail(message)
                    }
                } else {
                    val message = "Failed to create connection to Druid: : ${ar.cause().message}"
                    logger.warning(message)
                    promise.fail(message)
                }
            }
        return promise.future()
    }

    fun createIngestionJob(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        val url = "https://$host/v1/projects/$projectId/jobs"
        val payload = JsonObject()
            .put("type", "streaming")
            .put("target", JsonObject()
                .put("type", "table")
                .put("tableName", tableName)
            )
            .put("createTableIfNotExists", true)
            .put("source", JsonObject()
                .put("type", "connection")
                .put("connectionName", connectionName)
                .put("inputSchema", JsonArray()
                    .add(JsonObject().put("name", "sourceTimeMs").put("dataType", "long"))
                    .add(JsonObject().put("name", "serverTimeMs").put("dataType", "long"))
                    .add(JsonObject().put("name", "systemName").put("dataType", "string"))
                    .add(JsonObject().put("name", "topicName").put("dataType", "string"))
                    .add(JsonObject().put("name", "nodeId").put("dataType", "string"))
                    .add(JsonObject().put("name", "browsePath").put("dataType", "string"))
                    .add(JsonObject().put("name", "statusCode").put("dataType", "string"))
                    .add(JsonObject().put("name", "valueAsString").put("dataType", "string"))
                    .add(JsonObject().put("name", "valueAsDouble").put("dataType", "double"))
                )
                .put("formatSettings", JsonObject()
                    .put("format", "nd-json")
                )
            )
            .put("mappings", JsonArray()
                .add(JsonObject()
                    .put("columnName", "__time")
                    .put("expression", "MILLIS_TO_TIMESTAMP(\"sourceTimeMs\")")
                )
                .add(JsonObject()
                    .put("columnName", "serverTimeMs")
                    .put("expression", "MILLIS_TO_TIMESTAMP(\"serverTimeMs\")")
                )
                .add(JsonObject()
                    .put("columnName", "systemName")
                    .put("expression", "\"systemName\"")
                )
                .add(JsonObject()
                    .put("columnName", "topicName")
                    .put("expression", "\"topicName\"")
                )
                .add(JsonObject()
                    .put("columnName", "nodeId")
                    .put("expression", "\"nodeId\"")
                )
                .add(JsonObject()
                    .put("columnName", "browsePath")
                    .put("expression", "\"browsePath\"")
                )
                .add(JsonObject()
                    .put("columnName", "statusCode")
                    .put("expression", "\"statusCode\"")
                )
                .add(JsonObject()
                    .put("columnName", "valueAsString")
                    .put("expression", "\"valueAsString\"")
                )
                .add(JsonObject()
                    .put("columnName", "valueAsDouble")
                    .put("expression", "\"valueAsDouble\"")
                )
            )
        val clientOptions = WebClientOptions().setSsl(true).setDefaultPort(443)
        val client = WebClient.create(vertx, clientOptions)
        client.postAbs(url)
            .putHeader("Authorization", "Basic $apiKey")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload) { ar ->
                if (ar.succeeded()) {
                    val response = ar.result()
                    logger.info("Response: ${response.statusCode()}: ${response.bodyAsString()}")
                    if (response.statusCode() == 400 /* already exists*/ || response.statusCode() in 200..299) {
                        promise.complete()
                    } else {
                        val message =
                            "Failed to create ingestion job in Druid: ${response.statusCode()}: ${response.statusMessage()}"
                        logger.warning(message)
                        promise.fail(message)
                    }
                } else {
                    val message = "Failed to create ingestion job in Druid: : ${ar.cause().message}"
                    logger.warning(message)
                    promise.fail(message)
                }
            }
        return promise.future()
    }

    override fun close(): Future<Unit> {
        // Close connection to Druid
        return Future.succeededFuture()
    }

    override fun isEnabled(): Boolean {
        return true
    }

    fun createRecord(dp: DataPoint): JsonObject {
        return JsonObject()
            .put("systemName", dp.topic.systemName)
            .put("topicName", dp.topic.topicName)
            .put("nodeId", dp.topic.topicNode)
            .put("browsePath", dp.topic.getBrowsePathOrNode().toString())
            .put("statusCode", dp.value.statusAsString())
            .put("serverTimeMs", dp.value.serverTimeMs())
            .put("sourceTimeMs", dp.value.sourceTimeMs())
            .put("valueAsDouble", dp.value.valueAsDouble())
            .put("valueAsString", dp.value.valueAsString())
    }

    override fun writeExecutor() {
        val future = writeExecutorAsync()
        try {
            val result = future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            if (result) commitDatapointBlock()
        } catch (e: Exception) {
            logger.warning("Failed to write data to Druid. [${e.message}]")
        }
    }

    private fun writeExecutorAsync(): Future<Boolean> {
        val batch = mutableListOf<JsonObject>()
        pollDatapointBlock { datapoint ->
            val record = createRecord(datapoint)
            batch.add(record)
        }
        return if (batch.isNotEmpty()) {
            writeToDruid(batch)
        } else {
            Future.succeededFuture(false)
        }
    }

    fun writeToDruid(data: List<JsonObject>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val url = "https://$host/v1/projects/$projectId/events/$connectionName"
        val payload = data.joinToString(separator = "\n") { it.encode() }
        try {
            val client = WebClient.create(vertx)
            client.postAbs(url)
                .putHeader("Authorization", "Basic $apiKey")
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer(payload)) { ar ->
                    if (ar.succeeded()) {
                        val statusCode = ar.result().statusCode()
                        if (statusCode == 200 || statusCode == 202) {
                            promise.complete(true)
                        } else {
                            promise.fail("Failed to send data to Apache Druid. HTTP error code: $statusCode body: ${ar.result().bodyAsString()}")
                        }
                    } else {
                        promise.fail("Failed to send data to Apache Druid.")
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            promise.fail(e)
        }
        return promise.future()
    }
}

