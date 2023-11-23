package at.rocworks.gateway.core.opcua.server

import at.rocworks.gateway.core.opcua.KeyStoreLoader
import at.rocworks.gateway.core.opcua.OpcUaServer
import at.rocworks.gateway.core.service.ComponentLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider

import org.eclipse.milo.opcua.sdk.server.AbstractLifecycle
import org.eclipse.milo.opcua.sdk.server.OpcUaServer as MiloOpcUaServer
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator
import org.eclipse.milo.opcua.stack.core.Stack
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager
import org.eclipse.milo.opcua.stack.core.security.DefaultTrustListManager
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.*
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration
import org.eclipse.milo.opcua.stack.server.security.DefaultServerCertificateValidator
import java.security.Security

import java.text.SimpleDateFormat
import java.util.*

class OpcUaServerInstance(val config: OpcUaServer) : AbstractLifecycle() {

    private val logger = ComponentLogger.getLogger(this::class.java.simpleName, config.getComponentId())

    private val identityValidator = UsernameIdentityValidator(true) { authChallenge ->
        val username = authChallenge.username
        val password = authChallenge.password

        val adminValid = "admin" == username && "password" == password
        val user1Valid = "user1" == username && "password" == password
        val user2Valid = "user2" == username && "password" == password

        adminValid || user1Valid || user2Valid
    }

    private val gatewayServer: MiloOpcUaServer
    private val gatewayNamespace: OpcUaNamespace
    val gatewayNodes: OpcUaGatewayNodes

    companion object {
        init {
            // Required for SecurityPolicy.Aes256_Sha256_RsaPss
            Security.addProvider(BouncyCastleProvider())
        }
    }

    init {
        val keyStore = KeyStoreLoader.keyStoreLoader
        val trustListManager = DefaultTrustListManager(KeyStoreLoader.getPkiDir().toFile())

        val certificateManager = DefaultCertificateManager(
            keyStore.clientKeyPair,
            keyStore.clientCertificateChain
        )

        val certificateValidator = DefaultServerCertificateValidator(
            trustListManager,
            ValidationCheck.ALL_OPTIONAL_CHECKS
        )

        val endpoints = createEndpointConfigurations(certificateManager)

        val serverConfig = OpcUaServerConfig.builder()
            .setProductUri(config.productUri)
            .setApplicationUri(config.productUri)
            .setApplicationName(LocalizedText.english(config.productName))
            .setBuildInfo(buildInfo())
            .setTrustListManager(trustListManager)
            .setCertificateManager(certificateManager)
            .setCertificateValidator(certificateValidator)
            .setIdentityValidator(identityValidator)
            .setEndpoints(endpoints)
            .setLimits(OpcUaServerLimits)
            .build()

        gatewayServer = MiloOpcUaServer(serverConfig)
        gatewayNamespace = OpcUaNamespace(gatewayServer)
        gatewayNamespace.startup()

        gatewayNodes = OpcUaGatewayNodes(
            config,
            gatewayServer,
            gatewayNamespace,
            gatewayNamespace.namespaceIndex
        )
        gatewayNodes.startup()
    }

    override fun onStartup() {
        Stack.ConnectionLimits.RATE_LIMIT_ENABLED = true
        gatewayServer.startup()?.get()
    }

    override fun onShutdown() {
        gatewayNamespace.shutdown()
        gatewayServer.shutdown()?.get()
    }

    private fun buildInfo(): BuildInfo {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val buildDate = config.buildDate.let { date ->
            try {
                DateTime(dateFormat.parse(date))
            } catch (t: Throwable) {
                DateTime.NULL_VALUE
            }
        }

        return BuildInfo(
            config.productUri,
            config.manufacturerName,
            config.productName,
            config.softwareVersion,
            config.buildNumber,
            buildDate
        )
    }

    private fun createEndpointConfigurations(certificateManager: DefaultCertificateManager): Set<EndpointConfiguration> {
        val endpointConfigurations = LinkedHashSet<EndpointConfiguration>()

        val userTokenPolicies = mutableListOf<UserTokenPolicy>().apply {
            add(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS)
            add(OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME)
        }

        val bindAddresses: List<String> = config.bindAddresses

        val endpointAddresses: List<String> = config.endpointAddresses

        for (bindAddress in bindAddresses) {
            for (hostname in endpointAddresses) {
                val builder = EndpointConfiguration.newBuilder()
                    .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                    .setBindAddress(bindAddress)
                    .setBindPort(config.bindPort)
                    .setHostname(hostname)
                    .setPath(config.bindPath)
                    .setCertificate {
                        certificateManager.certificates.first()
                    }
                    .addTokenPolicies(*userTokenPolicies.toTypedArray())

                val securityPolicies = config.securityPolicies.mapNotNull {
                    try {
                        SecurityPolicy.valueOf(it)
                    } catch (t: Throwable) {
                        logger.severe(t.message)
                        null
                    }
                }

                if (securityPolicies.isEmpty()) {
                    throw RuntimeException("no security policies configured")
                }

                for (securityPolicy in securityPolicies) {
                    if (securityPolicy == SecurityPolicy.None) {
                        endpointConfigurations.add(
                            builder.copy()
                                .setSecurityPolicy(SecurityPolicy.None)
                                .setSecurityMode(MessageSecurityMode.None)
                                .build()
                        )
                    } else {
                        endpointConfigurations.add(
                            builder.copy()
                                .setSecurityPolicy(securityPolicy)
                                .setSecurityMode(MessageSecurityMode.Sign)
                                .build()
                        )
                        endpointConfigurations.add(
                            builder.copy()
                                .setSecurityPolicy(securityPolicy)
                                .setSecurityMode(MessageSecurityMode.SignAndEncrypt)
                                .build()
                        )
                    }
                }
            }
        }
        return endpointConfigurations
    }
}

