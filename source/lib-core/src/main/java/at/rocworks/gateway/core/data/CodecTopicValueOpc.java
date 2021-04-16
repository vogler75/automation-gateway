package at.rocworks.gateway.core.data;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

public class CodecTopicValueOpc implements MessageCodec<TopicValueOpc, TopicValueOpc> {

    @Override
    public void encodeToWire(Buffer buffer, TopicValueOpc value) {
        Buffer data = value.encodeToJson().toBuffer();
        buffer.appendInt(data.length());
        buffer.appendBuffer(data);
    }

    @Override
    public TopicValueOpc decodeFromWire(int i, Buffer buffer) {
        int len = buffer.getInt(i);
        JsonObject json = (JsonObject) Json.decodeValue(buffer.getBuffer(i, i+len));
        return TopicValueOpc.Companion.fromJsonObject(json);
    }

    @Override
    public TopicValueOpc transform(TopicValueOpc value) {
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

