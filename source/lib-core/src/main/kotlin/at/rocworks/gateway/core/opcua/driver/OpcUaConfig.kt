package at.rocworks.gateway.core.opcua.driver

import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider
import java.util.function.Predicate

data class OpcUaConfig(
    val endpointUrl: String,
    val securityPolicy: SecurityPolicy?,
    val identityProvider: IdentityProvider = AnonymousProvider()
) {
    fun endpointFilter(): Predicate<EndpointDescription> {
        return Predicate { e: EndpointDescription ->
            (securityPolicy == null) || (e.securityPolicyUri == securityPolicy.uri)
        }
    }
}