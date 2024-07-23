package at.rocworks.gateway.core.service

import io.vertx.core.AbstractVerticle
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

class WebConfig(private val port: Int, configFileName: String) : AbstractVerticle() {
    private val logger = Logger.getLogger(javaClass.simpleName)
    private val configFileName = configFileName.substringBefore(".")+".yaml" // only YAML

    override fun start() {
        println("ConfigFileName $configFileName")
        createHttpServer(vertx, port)
    }

    override fun stop() {
        super.stop()
    }

    private fun ensureYamlExtension(configFileName: String): String {
        // Regex to check if the file has an extension
        val regex = Regex(".*\\.[a-zA-Z0-9]+$")

        return if (regex.matches(configFileName)) {
            // If the file has an extension, return it as is
            configFileName
        } else {
            // If the file does not have an extension, add .yaml to it
            "$configFileName.yaml"
        }
    }

    private fun createHttpServer(vertx: Vertx, port: Int) : HttpServer {
        val index = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>File Upload</title>
        </head>
        <body>
            <h1>Upload Config File</h1>
            <form action="/upload" method="post" enctype="multipart/form-data">
                <input type="file" name="file" accept=".yaml">
                <button type="submit">Upload</button>
            </form>
        </body>
        </html>            
        """.trimIndent()
        val router = Router.router(vertx)

        // Serve the static HTML page
        router.get("/").handler { ctx ->
            ctx.response()
                .putHeader("Content-Type", "text/html")
                .end(index)
        }

        // Handle file uploads
        router.route().handler(BodyHandler.create().setUploadsDirectory("config"))

        router.post("/upload").handler { ctx ->
            val upload = ctx.fileUploads().iterator().next()
            val uploadedFileName = upload.uploadedFileName()
            val fileName = upload.fileName()

            if (fileName.isEmpty()) {
                ctx.response()
                    .putHeader("Content-Type", "text/html")
                    .end("""
                    <html>
                    <body>
                        <h1>Error: No file selected</h1>
                        <button onclick="window.location.href='/'">Return to Upload Page</button>
                    </body>
                    </html>
                """.trimIndent())
            } else {
                // Move the file to a new location
                val targetPath = Paths.get(ensureYamlExtension(configFileName))
                Files.move(Paths.get(uploadedFileName), targetPath, StandardCopyOption.REPLACE_EXISTING)
                ctx.response()
                    .putHeader("Content-Type", "text/html")
                    .end("""
                    <html>
                    <body>
                        <h1>File uploaded to ${targetPath.toAbsolutePath()}</h1>
                        <button onclick="window.location.href='/'">Return to Upload Page</button>
                    </body>
                    </html>
                """.trimIndent())
            }
        }

        val server = vertx.createHttpServer().requestHandler(router).listen(port) {
            if (it.succeeded()) {
                logger.info("HTTP Server started on port $port")
            } else {
                logger.warning("Failed to start HTTP Server: ${it.cause()}")
            }
        }

        return server
    }
}