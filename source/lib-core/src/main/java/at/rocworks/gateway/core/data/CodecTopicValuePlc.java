package at.rocworks.gateway.core.data;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

public class CodecTopicValuePlc implements MessageCodec<TopicValuePlc, TopicValuePlc> {

    @Override
    public void encodeToWire(Buffer buffer, TopicValuePlc value) {
        var data = value.encodeToJson().toBuffer();
        buffer.appendInt(data.length());
        buffer.appendBuffer(data);
    }

    @Override
    public TopicValuePlc decodeFromWire(int i, Buffer buffer) {
        int len = buffer.getInt(i);
        var json = (JsonObject) Json.decodeValue(buffer.getBuffer(i, i+len));
        return TopicValuePlc.Companion.fromJsonObject(json);
    }

    @Override
    public TopicValuePlc transform(TopicValuePlc value) {
        return value;
    }

    @Override
    public String name() {
        return this.getClass().getSimpleName();
    }

    @Override
    public byte systemCodecID() {
        return -1;
    }
}

