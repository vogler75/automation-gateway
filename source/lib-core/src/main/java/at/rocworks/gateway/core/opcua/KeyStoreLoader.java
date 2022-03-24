package at.rocworks.gateway.core.opcua;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;


public class KeyStoreLoader {
    public static KeyStoreLoader keyStoreLoader;

    public static final String APPLICATION_NAME = "Automation Gateway@" + HostnameUtil.getHostname();
    public static final String APPLICATION_URI = String.format("urn:ROCWORKS.Gateway");

    private static final Pattern IP_ADDR_PATTERN = Pattern.compile(
            "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");

    private static final String CLIENT_ALIAS = "client-ai";
    private static final char[] PASSWORD = "password".toCharArray();

    private final Logger logger = Logger.getLogger(getClass().getSimpleName());

    private X509Certificate clientCertificate;
    private KeyPair clientKeyPair;

    public static void init() throws Exception {
        String dirName = System.getenv("GATEWAY_SECURITY_DIRECTORY");
        if (dirName == null) dirName = "."; // System.getProperty("java.io.tmpdir");
        Path securityTempDir = Paths.get(dirName, "security");
        Files.createDirectories(securityTempDir);
        if (!Files.exists(securityTempDir)) {
            throw new Exception("Unable to create security dir: " + securityTempDir);
        }
        keyStoreLoader = new KeyStoreLoader().load(securityTempDir);
    }

    KeyStoreLoader load(Path baseDir) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");

        Path serverKeyStore = baseDir.resolve("rocworks-gateway.pfx");

        logger.info("Loading KeyStore at "+serverKeyStore);

        if (!Files.exists(serverKeyStore)) {
            logger.info("Create new certificate...");
            keyStore.load(null, PASSWORD);

            KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);

            SelfSignedCertificateBuilder builder = new SelfSignedCertificateBuilder(keyPair)
                    .setCommonName(APPLICATION_NAME)
                    .setApplicationUri(APPLICATION_URI)
                    .setOrganization("ROCWORKS")
                    .setOrganizationalUnit("R&D")
                    .setLocalityName("Mattersburg")
                    .setCountryCode("AT")
                    .addDnsName("localhost")
                    .addIpAddress("127.0.0.1");

            // Get as many hostnames and IP addresses as we can listed in the certificate.
            for (String hostname : HostnameUtil.getHostnames("0.0.0.0")) {
                if (IP_ADDR_PATTERN.matcher(hostname).matches()) {
                    logger.info("Ip: "+hostname);
                    builder.addIpAddress(hostname);
                } else {
                    logger.info("DNS: "+hostname);
                    builder.addDnsName(hostname);
                }
            }

            X509Certificate certificate = builder.build();

            keyStore.setKeyEntry(CLIENT_ALIAS, keyPair.getPrivate(), PASSWORD, new X509Certificate[]{certificate});
            try (OutputStream out = Files.newOutputStream(serverKeyStore)) {
                keyStore.store(out, PASSWORD);
            }
        } else {
            logger.info("Load existing certificate...");
            try (InputStream in = Files.newInputStream(serverKeyStore)) {
                keyStore.load(in, PASSWORD);
            }
        }

        Key serverPrivateKey = keyStore.getKey(CLIENT_ALIAS, PASSWORD);
        if (serverPrivateKey instanceof PrivateKey) {
            clientCertificate = (X509Certificate) keyStore.getCertificate(CLIENT_ALIAS);
            PublicKey serverPublicKey = clientCertificate.getPublicKey();
            clientKeyPair = new KeyPair(serverPublicKey, (PrivateKey) serverPrivateKey);
        }

        logger.info("Loaded certificate.");
        return this;
    }

    X509Certificate getClientCertificate() {
        return clientCertificate;
    }

    KeyPair getClientKeyPair() {
        return clientKeyPair;
    }
}
