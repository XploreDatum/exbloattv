package dev.xpdt.exbloattv.adb;

import dadb.AdbConnection;
import dadb.AdbKeyPair;
import dadb.AdbStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Java shim: dadb's {@code AdbConnection} is Kotlin-{@code internal} (public in bytecode), so Kotlin code
 * in this app cannot name it. This lets {@link DadbConnector} own the socket and still use dadb's handshake.
 */
final class RawAdbConnection {
    private final AdbConnection connection;

    private RawAdbConnection(AdbConnection connection) {
        this.connection = connection;
    }

    /** Blocks until the TV completes (or rejects) the auth handshake on [socket]. */
    static RawAdbConnection connect(Socket socket, AdbKeyPair keys) {
        return new RawAdbConnection(AdbConnection.Companion.connect(socket, keys, 10_000L));
    }

    AdbStream open(String destination) throws IOException {
        return connection.open(destination);
    }

    boolean supportsFeature(String feature) {
        return connection.supportsFeature(feature);
    }

    void close() throws Exception {
        connection.close();
    }
}
