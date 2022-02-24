export APP_HOME=source/app

CLASSPATH=`cat classpath.txt | envsubst`
echo $CLASSPATH

native-image --no-fallback \
-H:ReflectionConfigurationFiles=config/reflect-config.json \
-H:DynamicProxyConfigurationFiles=config/proxy-config.json \
--allow-incomplete-classpath \
--initialize-at-build-time=org.slf4j \
--initialize-at-run-time=io.netty.util.internal.logging.Log4JLogger \
--initialize-at-run-time=io.netty.handler.ssl.JettyNpnSslEngine \
--initialize-at-run-time=io.netty.handler.ssl.ConscryptAlpnSslEngine \
--initialize-at-run-time=io.netty.handler.ssl.JdkNpnApplicationProtocolNegotiator \
--initialize-at-run-time=io.netty.handler.ssl.OpenSslAsyncPrivateKeyMethod \
--initialize-at-run-time=io.netty.handler.ssl.OpenSslPrivateKeyMethod \
--initialize-at-run-time=io.netty.handler.ssl.ReferenceCountedOpenSslEngine \
--initialize-at-run-time=io.netty.handler.ssl.BouncyCastleAlpnSslUtils \
--initialize-at-run-time=io.netty.handler.codec.compression.BrotliOptions \
--initialize-at-run-time=io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod \
--initialize-at-run-time=io.netty.internal.tcnative.CertificateVerifier \
--initialize-at-run-time=io.netty.internal.tcnative.SSL \
--initialize-at-run-time=io.netty.internal.tcnative.SSLPrivateKeyMethod \
-cp $CLASSPATH App

