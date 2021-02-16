package at.rocworks.opcua;

import at.rocworks.data.Globals;
import at.rocworks.data.Topic;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.ServiceDiscovery;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.ServiceFaultListener;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscriptionManager.SubscriptionListener;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ServiceFault;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

public abstract class OpcUaVerticle extends AbstractVerticle implements ServiceFaultListener {
    private String id = OpcUaVerticle.class.getSimpleName(); // Default Id

    protected final Logger logger;
    private String logLevel = "INFO";

    public static String APPLICATION_NAME = "Reactive Gateway@"+HostnameUtil.getHostname();
    public static String APPLICATION_URI = String.format("urn:%s:ROCWORKS.Gateway", HostnameUtil.getHostname());

    private String endpointUrl;
    private String updateEndpointUrl;
    private SecurityPolicy securityPolicy;
    private IdentityProvider identityProvider = new AnonymousProvider();

    private Integer requestTimeout;
    private Integer connectTimeout;
    private Integer keepAliveFailuresAllowed;
    private Double  subscriptionSamplingInterval;

    protected static KeyStoreLoader keyStoreLoader;

    protected OpcUaClient client;
    protected UaSubscription subscription;

    private List<MessageConsumer> messageHandlers = new ArrayList<>();

    private Integer defaultRetryWaitTime = 5000;

    static {
        // Required for SecurityPolicy.Aes256_Sha256_RsaPss
        Security.addProvider(new BouncyCastleProvider());
    }

    Predicate<EndpointDescription> endpointFilter() {
        return e -> {
            if (this.updateEndpointUrl !=null) {
                logger.info("Update endpoint to "+this.updateEndpointUrl);
                EndpointUtil.updateUrl(e, this.updateEndpointUrl);
            }
            return (securityPolicy == null) || (e.getSecurityPolicyUri().equals(securityPolicy.getUri()));
        };
    }

    EndpointDescription endpointUpdater(EndpointDescription endpoint) {
        if (this.updateEndpointUrl!=null) {
            return EndpointUtil.updateUrl(endpoint, this.updateEndpointUrl);
        } else {
            return endpoint;
        }
    }

    public OpcUaVerticle(JsonObject config) {
        try {
            this.id = config.getValue("Id").toString();
            this.endpointUrl = config.getString("EndpointUrl");
            this.updateEndpointUrl = config.getString("UpdateEndpointUrl", null);
            var securityPolicyConf = config.getString("SecurityPolicyUri", null);
            this.securityPolicy = securityPolicyConf != null ? SecurityPolicy.fromUri(securityPolicyConf) : null;
            if (config.containsKey("UsernameProvider")) {
                var value = (JsonObject) config.getJsonObject("UsernameProvider");
                this.identityProvider = new UsernameProvider(value.getString("Username"), value.getString("Password"));
            }

            this.requestTimeout = config.getInteger("RequestTimeout", 5000);
            this.connectTimeout = config.getInteger("ConnectTimeout", 5000);
            this.keepAliveFailuresAllowed = config.getInteger("KeepAliveFailuresAllowed", 0);
            this.subscriptionSamplingInterval = config.getDouble("SubscriptionSamplingInterval", 0.0);

            this.logLevel = config.getString("LogLevel", "INFO");
            java.util.logging.Logger.getLogger(id).setLevel(java.util.logging.Level.parse(this.logLevel));
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.logger = LoggerFactory.getLogger(id);
        this.logger.info(APPLICATION_URI);

        this.logger.info("RequestTimeout: [{}] " +
                        "ConnectTimeout: [{}] " +
                        "KeepAliveFailuresAllowed: [{}] " +
                        "SubscriptionSamplingInterval [{}]",
                requestTimeout,
                connectTimeout,
                keepAliveFailuresAllowed,
                subscriptionSamplingInterval);

        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            logger.warn("Shutdown [{}]", this.id);
            try {
                if (client!=null) client.disconnect();
                logger.warn("Shutdown finished [{}]", this.id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("OpcUaClient start [{}] [{}] [{}]", this.id, this.endpointUrl, this.securityPolicy.toString());
        new Thread(()->startClient(startPromise)).start();
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("OpcUaClient stop [{}] [{}] [{}]", this.id, this.endpointUrl, this.securityPolicy.toString());
        new Thread(()->{
            try {
                disconnectHandlers();
                client.disconnect().thenAccept(client -> {
                    stopPromise.complete();
                });
            } catch (Exception e) {
                stopPromise.fail(e);
            }
        }).start();
    }

    SubscriptionListener subscriptionListener = new SubscriptionListener() {
        @Override
        public void onKeepAlive(UaSubscription subscription, DateTime publishTime) {
        }

        @Override
        public void onStatusChanged(UaSubscription subscription, StatusCode status) {
            logger.info("onStatusChanged: " + status.toString());
        }

        @Override
        public void onPublishFailure(UaException exception) {
            logger.warn("onPublishFailure: " + exception.getMessage());
        }

        @Override
        public void onNotificationDataLost(UaSubscription subscription) {
            logger.warn("onNotificationDataLost");
        }

        @Override
        public void onSubscriptionTransferFailed(UaSubscription subscription, StatusCode statusCode) {
            logger.warn("onSubscriptionTransferFailed: " + statusCode.toString());
            subscription.deleteMonitoredItems(subscription.getMonitoredItems()); // TODO: is this needed?
            createSubscription();
        }
    };

    protected abstract void resubscribe();

    private void startClient(Promise<Void> promise) {
        try {
            createClientAsync().onComplete((ret1)->{
                connectClientAsync().onComplete((ret2) -> {
                    client.addFaultListener(this);
                    client.getSubscriptionManager().addSubscriptionListener(subscriptionListener);
                    createSubscription();
                    connectHandlers();
                    registerService();
                    promise.complete();
                });
            });
        } catch (Exception e) {
            logger.error(e.toString());
            promise.fail(e);
        }
    }

    private void registerService() {
        ServiceDiscovery discovery = ServiceDiscovery.create(vertx);

        Record record = new Record()
                .setName(id)
                .setType(Topic.SystemType.Opc.toString())
                .setLocation(new JsonObject().put("endpoint", Globals.BUS_ROOT_URI_OPC+"/"+this.id));

        discovery.publish(record, ar -> {
            if (ar.succeeded()) {
                Record publishedRecord = ar.result();
                logger.info("Service registered.");
            } else {
                logger.warn("Service registration failed!");
            }
        });
    }

    private void createSubscription() {
        client.getSubscriptionManager()
                .createSubscription(this.subscriptionSamplingInterval)
                .whenCompleteAsync(this::createSubscriptionComplete);
    }

    private void createSubscriptionComplete(UaSubscription s, Throwable e) {
        if (e == null) {
            subscription = s;
            resubscribe();
        }
        else {
            logger.error("Unable to create subscription, reason: "+ e.getMessage());
        }
    }

    private Future<Void> createClientAsync() {
        Promise<Void> ret = Promise.promise();
        createClientThread(ret);
        return ret.future();
    }

    private void createClientThread(Promise<Void> ret) {
        new Thread(()-> {
            try {
                client = OpcUaClient.create(
                        this.endpointUrl,
                        endpoints ->
                                endpoints.stream()
                                        .filter(endpointFilter())
                                        .map(this::endpointUpdater)
                                        .findFirst(),
                        configBuilder ->
                                configBuilder
                                        .setApplicationName(LocalizedText.english(APPLICATION_NAME))
                                        .setApplicationUri(APPLICATION_URI)
                                        .setCertificate(keyStoreLoader.getClientCertificate())
                                        .setKeyPair(keyStoreLoader.getClientKeyPair())
                                        .setIdentityProvider(this.identityProvider)
                                        .setRequestTimeout(uint(this.requestTimeout))
                                        .setConnectTimeout(uint(this.connectTimeout))
                                        .setKeepAliveFailuresAllowed(uint(this.keepAliveFailuresAllowed))
                                        .build());
                logger.info("OpcUaClient created.");
                ret.complete();
            } catch (UaException e) {
                logger.info("OpcUaClient create failed! Wait and retry... " + e.getMessage());
                vertx.setTimer(defaultRetryWaitTime, id -> createClientThread(ret));
            } catch (Exception e) {
                ret.fail(e);
            }
        }).start();
    }

    private Future<Void> connectClientAsync() {
        Promise<Void> ret = Promise.promise();
        client.connect().whenCompleteAsync((c, e)->{
            if (e==null) {
                logger.info("OpcUaClient connected [{}] [{}]", this.id, this.endpointUrl);
                ret.complete();
            } else {
                logger.info("OpcUaClient connect failed! Wait and retry... "+e.getMessage());
                vertx.setTimer(defaultRetryWaitTime, id -> connectClientAsync().onComplete(ret));
            }
        });
        return ret.future();
    }

    public static void initKeyStoreLoader() throws Exception {
        Path securityTempDir = Paths.get(System.getProperty("java.io.tmpdir"), "security");
        Files.createDirectories(securityTempDir);
        if (!Files.exists(securityTempDir)) {
            throw new Exception("unable to create security dir: " + securityTempDir);
        }
        keyStoreLoader = new KeyStoreLoader().load(securityTempDir);
    }

    @Override
    public void onServiceFault(ServiceFault serviceFault) {
        //logger.warn("Service Fault: "+serviceFault.toString());
    }

    private void connectHandlers() {
        messageHandlers = List.of(
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/ServerInfo", this::serverInfoHandler),
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/Subscribe", this::subscribeHandler),
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/Unsubscribe", this::unsubscribeHandler),
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/Publish", this::publishHandler),
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/Read", this::readHandler),
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/Write", this::writeHandler),
                vertx.eventBus().consumer(Globals.BUS_ROOT_URI_OPC + "/" + this.id + "/Browse", this::browseHandler)
        );
    }

    private void disconnectHandlers() {
        messageHandlers.forEach(h -> h.unregister());
    }

    protected abstract void serverInfoHandler(Message<JsonObject> message);
    protected abstract void subscribeHandler(Message<JsonObject> message);
    protected abstract void unsubscribeHandler(Message<JsonObject> message);
    protected abstract void publishHandler(Message<JsonObject> message);
    protected abstract void readHandler(Message<JsonObject> message);
    protected abstract void writeHandler(Message<JsonObject> message);
    protected abstract void browseHandler(Message<JsonObject> message);


    protected NodeId rdToNodeId(ReferenceDescription rd) {
        return rd.getNodeId().toNodeId(client.getNamespaceTable()).get();
    }
}
