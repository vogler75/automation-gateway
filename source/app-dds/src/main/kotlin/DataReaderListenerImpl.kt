import DDS.*

class DataReaderListenerImpl(
    topicTypeName: String,
    val callback: (sampleInfo: DDS.SampleInfo, data: Any)->Unit
) : _DataReaderListenerLocalBase() {

    private val topicClass = Class.forName(topicTypeName)
    private val holderClass = Class.forName(topicTypeName + "Holder")
    private val holderValue = holderClass.getField("value")

    private val dataReaderClass = Class.forName(topicTypeName + "DataReader")
    private val dataReaderTakeNextSample = dataReaderClass.getMethod("take_next_sample", holderClass, DDS.SampleInfoHolder::class.java)

    private val dataReaderHelperClass = Class.forName(topicTypeName + "DataReaderHelper")
    private val dataReaderHelperNarrow = dataReaderHelperClass.getMethod("narrow", org.omg.CORBA.Object::class.java)

    @Synchronized
    override fun on_data_available(reader: DataReader) {
        val dataReader = try {
            dataReaderHelperNarrow.invoke(null, reader)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (dataReader == null) {
            println("ERROR: read: narrow failed.")
            return
        }

        val topic = topicClass.getConstructor().newInstance()
        
        val holder = holderClass.getConstructor(topicClass).newInstance(topic)
        
        val sampleInfoHolder = SampleInfoHolder(
            SampleInfo(0, 0, 0, Time_t(), 0, 0, 0, 0, 0, 0, 0, false, 0)
        )

        when (val status = dataReaderTakeNextSample.invoke(dataReader, holder, sampleInfoHolder)) {
            RETCODE_OK.value -> {
                //System.out.println("SampleInfo.sample_rank = " + sih.value.sample_rank);
                //System.out.println("SampleInfo.instance_state = " + sih.value.instance_state);
                when {
                    sampleInfoHolder.value.valid_data -> {
                        callback(sampleInfoHolder.value, holderValue.get(holder))
                    }
                    sampleInfoHolder.value.instance_state == NOT_ALIVE_DISPOSED_INSTANCE_STATE.value -> {
                        println("instance is disposed")
                    }
                    sampleInfoHolder.value.instance_state == NOT_ALIVE_NO_WRITERS_INSTANCE_STATE.value -> {
                        println("instance is unregistered")
                    }
                    else -> {
                        println(
                            "DataReaderListenerImpl::on_data_available: "
                                    + "ERROR: received unknown instance state "
                                    + sampleInfoHolder.value.instance_state
                        )
                    }
                }
            }
            RETCODE_NO_DATA.value -> {
                println("ERROR: reader received DDS::RETCODE_NO_DATA!")
            }
            else -> {
                println("ERROR: read Message: Error: $status")
            }
        }
    }

    override fun on_requested_deadline_missed(reader: DataReader, status: RequestedDeadlineMissedStatus) {
        println("DataReaderListenerImpl.on_requested_deadline_missed")
    }

    override fun on_requested_incompatible_qos(reader: DataReader, status: RequestedIncompatibleQosStatus) {
        println("DataReaderListenerImpl.on_requested_incompatible_qos")
    }

    override fun on_sample_rejected(reader: DataReader, status: SampleRejectedStatus) {
        println("DataReaderListenerImpl.on_sample_rejected")
    }

    override fun on_liveliness_changed(reader: DataReader, status: LivelinessChangedStatus) {
        println("DataReaderListenerImpl.on_liveliness_changed")
    }

    override fun on_subscription_matched(reader: DataReader, status: SubscriptionMatchedStatus) {
        println("DataReaderListenerImpl.on_subscription_matched")
    }

    override fun on_sample_lost(reader: DataReader, status: SampleLostStatus) {
        println("DataReaderListenerImpl.on_sample_lost")
    }
}