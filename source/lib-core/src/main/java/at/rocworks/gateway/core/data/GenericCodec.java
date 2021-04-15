package at.rocworks.gateway.core.data;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;

public class GenericCodec<T> implements MessageCodec<T, T> {
    private final Class<T> genericClass;

    public GenericCodec(Class<T> genericClass) {
        super();
        this.genericClass = genericClass;
    }

    @Override
    public void encodeToWire(Buffer buffer, T object) {
        ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        ObjectOutput objectOutput = null;
        try {
            objectOutput = new ObjectOutputStream(byteOutput);
            objectOutput.writeObject(object);
            objectOutput.flush();
            byte[] objectBytes = byteOutput.toByteArray();
            buffer.appendInt(objectBytes.length);
            buffer.appendBytes(objectBytes);
            objectOutput.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                byteOutput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public T decodeFromWire(int pos, Buffer buffer) {
        // Length of JSON
        int length = buffer.getInt(pos);

        // Jump 4 because getInt() == 4 bytes
        byte[] bytes = buffer.getBytes(pos += 4, pos + length);
        ByteArrayInputStream byteInput = new ByteArrayInputStream(bytes);
        try {
            ObjectInputStream objectInput = new ObjectInputStream(byteInput);
            @SuppressWarnings("unchecked")
            T object = (T) objectInput.readObject();
            objectInput.close();
            return object;
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("Decode failed "+e.getMessage());
            return null;
        } finally {
            try {
                byteInput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public T transform(T object) {
        return object;
    }

    @Override
    public String name() {
        return "Codec"+genericClass.getSimpleName();
    }

    @Override
    public byte systemCodecID() { return -1; }
}