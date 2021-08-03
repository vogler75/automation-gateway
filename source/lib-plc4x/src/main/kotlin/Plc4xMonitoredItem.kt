import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.driver.MonitoredItem
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle

class Plc4xMonitoredItem(override val item: PlcSubscriptionHandle) : MonitoredItem() {
}

class Plc4xPolledItem(override val item: Topic) : MonitoredItem() {
}