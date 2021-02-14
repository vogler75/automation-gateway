import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.slf4j.LoggerFactory
import java.util.logging.LogManager
import kotlin.system.exitProcess


object TestCluster {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val stream = TestCluster::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val logger = (LoggerFactory.getLogger(javaClass.simpleName));


        val clusterManager = IgniteClusterManager()

        val vertxOptions = VertxOptions().setClusterManager(clusterManager)

        val vertxClusterResult = Vertx.clusteredVertx(vertxOptions)

        vertxClusterResult.onComplete {
            val vertx = it.result()

            // Clustered Map
            val map: Map<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map

        }
    }
}