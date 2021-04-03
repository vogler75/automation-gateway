package at.rocworks.gateway.core.data;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

public class CodecTopicValueDds implements MessageCodec<TopicValueDds, TopicValueDds> {

    @Override
    public void encodeToWire(Buffer buffer, TopicValueDds value) {
        var data = value.encodeToJson().toBuffer();
        buffer.appendInt(data.length());
        buffer.appendBuffer(data);
    }

    @Override
    public TopicValueDds decodeFromWire(int i, Buffer buffer) {
        int len = buffer.getInt(i);
        var json = (JsonObject) Json.decodeValue(buffer.getBuffer(i, i+len));
        return TopicValueDds.Companion.fromJsonObject(json);
    }

    @Override
    public TopicValueDds transform(TopicValueDds value) {
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

