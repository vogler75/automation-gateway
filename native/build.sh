export APP_HOME=source/app$1

CLASSPATH=`cat classpath.txt | envsubst`
echo $CLASSPATH

native-image --no-fallback \
-H:ReflectionConfigurationFiles=config$1/reflect-config.json \
-H:DynamicProxyConfigurationFiles=config$1/proxy-config.json \
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
--initialize-at-run-time=io.netty.internal.tcnative.CertificateCompressionAlgo \
-cp $CLASSPATH App

