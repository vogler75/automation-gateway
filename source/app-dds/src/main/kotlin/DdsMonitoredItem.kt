import at.rocworks.gateway.core.driver.MonitoredItem

class DdsMonitoredItem(override val item: DDS.DataReader) : MonitoredItem() {
}