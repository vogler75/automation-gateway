package at.rocworks.gateway.core.data;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

public class CodecTopic implements MessageCodec<Topic, Topic> {

    @Override
    public void encodeToWire(Buffer buffer, Topic mqttTopic) {
        Buffer data = mqttTopic.encodeToJson().toBuffer();
        buffer.appendInt(data.length());
        buffer.appendBuffer(data);
    }

    @Override
    public Topic decodeFromWire(int i, Buffer buffer) {
        int len = buffer.getInt(i);
        JsonObject json = (JsonObject)Json.decodeValue(buffer.getBuffer(i, i+len));
        return Topic.Companion.decodeFromJson(json);
    }

    @Override
    public Topic transform(Topic mqttTopic) {
        return mqttTopic;
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
