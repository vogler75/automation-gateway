package at.rocworks.gateway.core.data;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

public class ValueCodec implements MessageCodec<Value, Value> {

    @Override
    public void encodeToWire(Buffer buffer, Value value) {
        var data = value.encodeToJson().toBuffer();
        buffer.appendInt(data.length());
        buffer.appendBuffer(data);
    }

    @Override
    public Value decodeFromWire(int i, Buffer buffer) {
        int len = buffer.getInt(i);
        var json = (JsonObject) Json.decodeValue(buffer.getBuffer(i, i+len));
        return Value.Companion.decodeFromJson(json);
    }

    @Override
    public Value transform(Value value) {
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

