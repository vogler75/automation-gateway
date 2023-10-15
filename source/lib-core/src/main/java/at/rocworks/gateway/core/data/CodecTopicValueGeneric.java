package at.rocworks.gateway.core.data;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

public class CodecTopicValueGeneric implements MessageCodec<TopicValueGeneric, TopicValueGeneric> {

    @Override
    public void encodeToWire(Buffer buffer, TopicValueGeneric value) {
        Buffer data = value.encodeToJson().toBuffer();
        buffer.appendInt(data.length());
        buffer.appendBuffer(data);
    }

    @Override
    public TopicValueGeneric decodeFromWire(int i, Buffer buffer) {
        int len = buffer.getInt(i);
        JsonObject json = (JsonObject) Json.decodeValue(buffer.getBuffer(i, i+len));
        return TopicValueGeneric.Companion.fromJsonObject(json);
    }

    @Override
    public TopicValueGeneric transform(TopicValueGeneric value) {
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

