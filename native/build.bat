set APP_HOME=source\app

echo "Copy CLASSPATH from app\bin\app.bat!"
set CLASSPATH=%APP_HOME%\lib\app.jar;%APP_HOME%\lib\lib-core.jar;%APP_HOME%\lib\influxdb-java-2.22.jar;%APP_HOME%\lib\logging-interceptor-4.9.1.jar;%APP_HOME%\lib\dictionary-reader-0.6.3.jar;%APP_HOME%\lib\sdk-client-0.6.3.jar;%APP_HOME%\lib\stack-client-0.6.3.jar;%APP_HOME%\lib\netty-channel-fsm-0.5.jar;%APP_HOME%\lib\strict-machine-0.5.jar;%APP_HOME%\lib\kotlin-stdlib-jdk8-1.4.30.jar;%APP_HOME%\lib\vertx-config-yaml-4.2.4.jar;%APP_HOME%\lib\vertx-service-discovery-4.2.4.jar;%APP_HOME%\lib\vertx-mqtt-4.2.4.jar;%APP_HOME%\lib\vertx-web-graphql-4.2.4.jar;%APP_HOME%\lib\vertx-config-4.2.4.jar;%APP_HOME%\lib\vertx-web-4.2.4.jar;%APP_HOME%\lib\vertx-web-common-4.2.4.jar;%APP_HOME%\lib\vertx-auth-common-4.2.4.jar;%APP_HOME%\lib\vertx-bridge-common-4.2.4.jar;%APP_HOME%\lib\vertx-core-4.2.4.jar;%APP_HOME%\lib\converter-moshi-2.9.0.jar;%APP_HOME%\lib\retrofit-2.9.0.jar;%APP_HOME%\lib\okhttp-4.9.1.jar;%APP_HOME%\lib\okio-jvm-2.8.0.jar;%APP_HOME%\lib\kotlin-stdlib-jdk7-1.4.30.jar;%APP_HOME%\lib\kotlin-stdlib-1.4.30.jar;%APP_HOME%\lib\kotlin-stdlib-common-1.4.30.jar;%APP_HOME%\lib\rxjava-2.2.21.jar;%APP_HOME%\lib\netty-handler-proxy-4.1.73.Final.jar;%APP_HOME%\lib\netty-codec-http2-4.1.73.Final.jar;%APP_HOME%\lib\netty-codec-http-4.1.73.Final.jar;%APP_HOME%\lib\netty-resolver-dns-4.1.73.Final.jar;%APP_HOME%\lib\sdk-core-0.6.3.jar;%APP_HOME%\lib\bsd-parser-0.6.3.jar;%APP_HOME%\lib\bsd-core-0.6.3.jar;%APP_HOME%\lib\stack-core-0.6.3.jar;%APP_HOME%\lib\netty-handler-4.1.73.Final.jar;%APP_HOME%\lib\netty-codec-mqtt-4.1.73.Final.jar;%APP_HOME%\lib\netty-codec-socks-4.1.73.Final.jar;%APP_HOME%\lib\netty-codec-dns-4.1.73.Final.jar;%APP_HOME%\lib\netty-codec-4.1.73.Final.jar;%APP_HOME%\lib\netty-transport-4.1.73.Final.jar;%APP_HOME%\lib\netty-buffer-4.1.73.Final.jar;%APP_HOME%\lib\netty-resolver-4.1.73.Final.jar;%APP_HOME%\lib\netty-common-4.1.73.Final.jar;%APP_HOME%\lib\jackson-annotations-2.13.1.jar;%APP_HOME%\lib\jackson-dataformat-yaml-2.13.1.jar;%APP_HOME%\lib\jackson-databind-2.13.1.jar;%APP_HOME%\lib\jackson-core-2.13.1.jar;%APP_HOME%\lib\annotations-13.0.jar;%APP_HOME%\lib\graphql-java-17.3.jar;%APP_HOME%\lib\reactive-streams-1.0.3.jar;%APP_HOME%\lib\msgpack-core-0.9.0.jar;%APP_HOME%\lib\netty-tcnative-classes-2.0.46.Final.jar;%APP_HOME%\lib\snakeyaml-1.28.jar;%APP_HOME%\lib\jaxb-runtime-2.3.3.jar;%APP_HOME%\lib\jakarta.activation-1.2.2.jar;%APP_HOME%\lib\antlr4-4.9.2.jar;%APP_HOME%\lib\antlr4-runtime-4.9.2.jar;%APP_HOME%\lib\java-dataloader-3.1.0.jar;%APP_HOME%\lib\slf4j-api-1.7.30.jar;%APP_HOME%\lib\guava-30.0-jre.jar;%APP_HOME%\lib\moshi-1.8.0.jar;%APP_HOME%\lib\bcpkix-jdk15on-1.61.jar;%APP_HOME%\lib\bcprov-jdk15on-1.61.jar;%APP_HOME%\lib\jakarta.xml.bind-api-2.3.3.jar;%APP_HOME%\lib\txw2-2.3.3.jar;%APP_HOME%\lib\istack-commons-runtime-3.0.11.jar;%APP_HOME%\lib\failureaccess-1.0.1.jar;%APP_HOME%\lib\listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar;%APP_HOME%\lib\jsr305-3.0.2.jar;%APP_HOME%\lib\checker-qual-3.5.0.jar;%APP_HOME%\lib\error_prone_annotations-2.3.4.jar;%APP_HOME%\lib\j2objc-annotations-1.3.jar;%APP_HOME%\lib\ST4-4.3.jar;%APP_HOME%\lib\antlr-runtime-3.5.2.jar;%APP_HOME%\lib\org.abego.treelayout.core-1.0.3.jar;%APP_HOME%\lib\javax.json-1.0.4.jar;%APP_HOME%\lib\icu4j-61.1.jar

native-image --no-fallback ^
-H:ReflectionConfigurationFiles=config/reflect-config.json ^
--allow-incomplete-classpath ^
--initialize-at-build-time=org.slf4j ^
--initialize-at-run-time=io.netty.util.internal.logging.Log4JLogger ^
--initialize-at-run-time=io.netty.handler.ssl.JettyNpnSslEngine ^
--initialize-at-run-time=io.netty.handler.ssl.ConscryptAlpnSslEngine ^
--initialize-at-run-time=io.netty.handler.ssl.JdkNpnApplicationProtocolNegotiator ^
--initialize-at-run-time=io.netty.handler.ssl.OpenSslAsyncPrivateKeyMethod ^
--initialize-at-run-time=io.netty.handler.ssl.OpenSslPrivateKeyMethod ^
--initialize-at-run-time=io.netty.handler.ssl.ReferenceCountedOpenSslEngine ^
--initialize-at-run-time=io.netty.handler.ssl.BouncyCastleAlpnSslUtils ^
--initialize-at-run-time=io.netty.handler.codec.compression.BrotliOptions ^
--initialize-at-run-time=io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod ^
--initialize-at-run-time=io.netty.internal.tcnative.CertificateVerifier ^
--initialize-at-run-time=io.netty.internal.tcnative.SSL ^
--initialize-at-run-time=io.netty.internal.tcnative.SSLPrivateKeyMethod ^
-cp %CLASSPATH% App

