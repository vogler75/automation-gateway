package at.rocworks.gateway.logger.opensearch

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import java.time.YearMonth
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.opensearch.client.RestClient
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.BulkRequest
import org.opensearch.client.opensearch.core.bulk.BulkOperation
import org.opensearch.client.opensearch.core.bulk.IndexOperation
import org.opensearch.client.transport.OpenSearchTransport
import org.opensearch.client.transport.rest_client.RestClientTransport
import java.time.format.DateTimeFormatter

/* Create an index template "<index name>" with an index pattern "<index name>-*" with the following JSON index mapping
{
  "properties": {
    "topicName": { "type": "text" },
    "systemType": { "type": "text" },
    "systemName": { "type": "text" },
    "topicType": { "type": "text" },
    "topicPath": { "type": "text" },
    "topicNode": { "type": "text" },
    "browsePath": { "type": "text" },
    "valueAsString": { "type": "text" },
    "valueAsNumber": { "type": "double" },
    "statusCode": { "type": "text" },
    "sourceTime": { "type": "date" },
    "serverTime": { "type": "date" }
  }
}
 */

class OpenSearchLogger(config: JsonObject) : LoggerBase(config) {
    data class IndexData(
        val topicName: String,
        val systemType: Topic.SystemType,
        val systemName: String,
        val topicType: Topic.TopicType,
        val topicPath: String,
        val topicNode: String,
        val browsePath: String,
        val valueAsString: String,
        val valueAsNumber: Double?,
        val statusCode: String,
        val sourceTime: Long,
        val serverTime: Long
    )

    private val host = config.getString("Host", "http://localhost")
    private val port = config.getInteger("Port", 9200)
    private val index = config.getString("Index", "gateway")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")

    private val httpHost = HttpHost(host, port)
    private val credentialsProvider = BasicCredentialsProvider()

    //Initialize the client
    private val restClient = RestClient.builder(httpHost).setHttpClientConfigCallback { httpClientBuilder ->
        httpClientBuilder.setDefaultCredentialsProvider(
            credentialsProvider
        )
    }.build()

    private val transport: OpenSearchTransport = RestClientTransport(restClient, JacksonJsonpMapper())
    private val client = OpenSearchClient(transport)

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            //Only for demo purposes. Don't specify your credentials in code.
            credentialsProvider.setCredentials(
                AuthScope(httpHost),
                UsernamePasswordCredentials(username, password)
            )
            logger.info("OpenSearch connected.")
            result.complete()
        } catch (e: Exception) {
            logger.severe("OpenSearch connect failed! [${e.message}]")
            e.printStackTrace()
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        promise.complete()
        return promise.future()
    }

    override fun writeExecutor() {
        val indexCurrent = index+"-"+YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val bulkOperations : MutableList<BulkOperation> = mutableListOf()

        pollDatapointBlock { point ->
            val value = point.value.valueAsDouble()
            val data = IndexData(
                point.topic.topicName,
                point.topic.systemType,
                point.topic.systemName,
                point.topic.topicType,
                point.topic.topicPath,
                point.topic.topicNode,
                point.topic.getBrowsePathOrNode().toString(),
                point.value.valueAsString(),
                if (value == null || value.isNaN()) null else value,
                point.value.statusCode,
                point.value.sourceTime.toEpochMilli(),
                point.value.serverTime.toEpochMilli()
            )
            val indexOperation = IndexOperation.Builder<IndexData>().index(indexCurrent).document(data).build()
            bulkOperations.add(BulkOperation.Builder().index(indexOperation).build())
        }
        if (bulkOperations.size > 0) {
            try {
                val result = client.bulk(BulkRequest.Builder().operations(bulkOperations).build())
                if (result.errors()) {
                    logger.severe("Bulk had some errors")
                    for (item in result.items()) {
                        if (item.error() != null) {
                            logger.severe(item.error()!!.reason())
                        }
                    }
                }
                commitDatapointBlock()
            } catch (e: Exception) {
                logger.severe("Error writing batch [${e.message}]")
            }
        }
    }
}