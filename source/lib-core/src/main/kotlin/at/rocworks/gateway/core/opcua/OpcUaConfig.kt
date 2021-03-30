package at.rocworks.gateway.core.opcua

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
    /*
     "opc.tcp://desktop-9o6hthf:4890"; // WinCC UA NUC
     "opc.tcp://desktop-pc4fa6r:4890"; // WinCC UA Laptop
     "opc.tcp://centos1.rocworks.local:4840"; // WinCC OA
     "opc.tcp://ubuntu1:62541/discovery" // Ignition

     1/ns=2;s=[default]/Input_Watt // Ignition
     2/ns=1;s=16.687.1.0.0.0 // Unified
     3/ns=2;s=ExampleDP_Float.ExampleDP_Arg1 // OA
     3/ns=2;s=ExampleDP_Float.ExampleDP_Arg2 // OA

     For WinCC Unified OPC UA Server do not use SecurityPolicy.None
    */

    fun endpointFilter(): Predicate<EndpointDescription> {
        return Predicate { e: EndpointDescription ->
            (securityPolicy == null) || (e.securityPolicyUri == securityPolicy.uri)
        }
    }
}