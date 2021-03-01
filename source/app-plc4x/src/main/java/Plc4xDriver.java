import io.vertx.core.json.JsonObject;
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Plc4xDriver {
    private final String id;
    private final Logger logger;
    private final String logLevel;
    private final String url;

    private PlcConnection plcConnection;

    public Plc4xDriver(JsonObject config) {
        this.id = config.getValue("Id").toString();
        this.logLevel = config.getString("LogLevel", "INFO");
        this.url = config.getString("Url", "");

        java.util.logging.Logger.getLogger(id).setLevel(java.util.logging.Level.parse(this.logLevel));
        this.logger = LoggerFactory.getLogger(id);

        try {
            this.plcConnection = new PlcDriverManager().getConnection(url);
            var info = (plcConnection.getMetadata().canRead() ? "Read " : " ") +
                    (plcConnection.getMetadata().canWrite() ? "Write " : " ") +
                    (plcConnection.getMetadata().canSubscribe() ? "Subscribe ": " ");

            logger.error("This connection supports: {}", info);
        } catch (PlcConnectionException e) {
            e.printStackTrace();
        }
    }
}
