package at.rocworks.gateway.core.opcua.driver

import at.rocworks.gateway.core.driver.MonitoredItem
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem

class OpcUaMonitoredItem(override val item: UaMonitoredItem) : MonitoredItem() {
}