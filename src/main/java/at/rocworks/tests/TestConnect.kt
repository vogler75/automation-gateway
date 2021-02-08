package at.rocworks.tests

import com.google.common.collect.Sets
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.UaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.*
import java.util.function.Consumer
import java.util.regex.Pattern

object TestConnect {

    val APPLICATION_NAME = "Test"
    val APPLICATION_URI = String.format("urn:%s:test", at.rocworks.opcua.HostnameUtil.getHostname())

    val endpointUrl = "opc.tcp://desktop-9o6hthf:4890"
    val identityProvider = UsernameProvider("opcuauser", "password1")
    val securityPolicy = SecurityPolicy.fromUri("http://opcfoundation.org/UA/SecurityPolicy#Basic128Rsa15")
    var client: UaClient = createClient()

    @JvmStatic
    fun main(args: Array<String>) {
        var done = false
        client.connect().thenAccept {
            //println("disconnect...")
            //client.disconnect()
            println("done")
            done = true
        }.get()
        Thread.sleep(30000)
        println("exit")
    }

    private fun createClient(): OpcUaClient {
        val securityTempDir = Paths.get(System.getProperty("java.io.tmpdir"), "security")
        Files.createDirectories(securityTempDir)
        if (!Files.exists(securityTempDir)) {
            throw Exception("unable to create security dir: $securityTempDir")
        }

        val loader = KeyStoreLoader().load(securityTempDir)

        return OpcUaClient.create(
            endpointUrl,
            { endpoints: List<EndpointDescription?> ->
                endpoints.stream().findFirst()
            }
        ) { configBuilder: OpcUaClientConfigBuilder ->
            configBuilder
                .setApplicationName(LocalizedText.english(APPLICATION_NAME))
                .setApplicationUri(APPLICATION_URI)
                .setIdentityProvider(identityProvider)
                .setRequestTimeout(Unsigned.uint(5000))
                .setCertificate(loader.clientCertificate)
                .setKeyPair(loader.clientKeyPair)
                .build()
        }
    }


}

internal class KeyStoreLoader {

    var clientCertificate: X509Certificate? = null
        private set
    var clientKeyPair: KeyPair? = null
        private set




    @Throws(Exception::class)
    fun load(baseDir: Path): KeyStoreLoader {
        val keyStore = KeyStore.getInstance("PKCS12")
        val serverKeyStore = baseDir.resolve("test.pfx")
        println("Loading KeyStore at $serverKeyStore")
        if (!Files.exists(serverKeyStore)) {
            keyStore.load(null, KeyStoreLoader.Companion.PASSWORD)
            val keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048)
            val builder = SelfSignedCertificateBuilder(keyPair)
                .setCommonName(TestConnect.APPLICATION_NAME)
                .setApplicationUri(TestConnect.APPLICATION_URI)
                .setOrganization("ROCWORKS")
                .setOrganizationalUnit("R&D")
                .setLocalityName("Mattersburg")
                .setCountryCode("AT")
                .addDnsName("localhost")
                .addIpAddress("127.0.0.1")

            // Get as many hostnames and IP addresses as we can listed in the certificate.
            for (hostname in HostnameUtil.getHostnames("0.0.0.0")) {
                if (KeyStoreLoader.Companion.IP_ADDR_PATTERN.matcher(hostname).matches()) {
                    builder.addIpAddress(hostname)
                } else {
                    builder.addDnsName(hostname)
                }
            }
            val certificate = builder.build()
            keyStore.setKeyEntry(
                KeyStoreLoader.Companion.CLIENT_ALIAS,
                keyPair.private,
                KeyStoreLoader.Companion.PASSWORD,
                arrayOf(certificate)
            )
            Files.newOutputStream(serverKeyStore).use { out ->
                keyStore.store(
                    out,
                    KeyStoreLoader.Companion.PASSWORD
                )
            }
        } else {
            Files.newInputStream(serverKeyStore).use { `in` ->
                keyStore.load(
                    `in`,
                    KeyStoreLoader.Companion.PASSWORD
                )
            }
        }
        val serverPrivateKey = keyStore.getKey(KeyStoreLoader.Companion.CLIENT_ALIAS, KeyStoreLoader.Companion.PASSWORD)
        if (serverPrivateKey is PrivateKey) {
            clientCertificate = keyStore.getCertificate(KeyStoreLoader.Companion.CLIENT_ALIAS) as X509Certificate
            val serverPublicKey = clientCertificate!!.publicKey
            clientKeyPair = KeyPair(serverPublicKey, serverPrivateKey)
        }
        return this
    }

    companion object {
        private val IP_ADDR_PATTERN = Pattern.compile(
            "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$"
        )
        private const val CLIENT_ALIAS = "client-ai"
        private val PASSWORD = "password".toCharArray()
    }
}

object HostnameUtil {
    /**
     * @return the local hostname, if possible. Failure results in "localhost".
     */
    val hostname: String
        get() = try {
            InetAddress.getLocalHost().hostName
        } catch (e: UnknownHostException) {
            "localhost"
        }

    /**
     * Given an address resolve it to as many unique addresses or hostnames as can be found.
     *
     * @param address the address to resolve.
     * @return the addresses and hostnames that were resolved from `address`.
     */
    fun getHostnames(address: String?): Set<String> {
        return getHostnames(address, true)
    }

    /**
     * Given an address resolve it to as many unique addresses or hostnames as can be found.
     *
     * @param address         the address to resolve.
     * @param includeLoopback if `true` loopback addresses will be included in the returned set.
     * @return the addresses and hostnames that were resolved from `address`.
     */
    fun getHostnames(address: String?, includeLoopback: Boolean): Set<String> {
        val hostnames: MutableSet<String> = Sets.newHashSet()
        try {
            val inetAddress = InetAddress.getByName(address)
            if (inetAddress.isAnyLocalAddress) {
                try {
                    val nis = NetworkInterface.getNetworkInterfaces()
                    for (ni in Collections.list(nis)) {
                        Collections.list(ni.inetAddresses).forEach(Consumer { ia: InetAddress ->
                            if (ia is Inet4Address) {
                                if (includeLoopback || !ia.isLoopbackAddress()) {
                                    hostnames.add(ia.getHostName())
                                    hostnames.add(ia.getHostAddress())
                                    hostnames.add(ia.getCanonicalHostName())
                                }
                            }
                        })
                    }
                } catch (e: SocketException) {
                    LoggerFactory.getLogger(HostnameUtil::class.java)
                        .warn("Failed to NetworkInterfaces for bind address: {}", address, e)
                }
            } else {
                if (includeLoopback || !inetAddress.isLoopbackAddress) {
                    hostnames.add(inetAddress.hostName)
                    hostnames.add(inetAddress.hostAddress)
                    hostnames.add(inetAddress.canonicalHostName)
                }
            }
        } catch (e: UnknownHostException) {
            LoggerFactory.getLogger(HostnameUtil::class.java)
                .warn("Failed to get InetAddress for bind address: {}", address, e)
        }
        return hostnames
    }
}
