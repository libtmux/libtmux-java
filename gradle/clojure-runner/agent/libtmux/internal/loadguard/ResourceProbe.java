package libtmux.internal.loadguard;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

/** Isolates JDK class initialization while checking each additional interception path. */
public final class ResourceProbe {
    private ResourceProbe() {}

    @SuppressWarnings("try") // Construction is the probe; close resources if interception fails.
    public static void main(String[] arguments) throws Exception {
        LoadGuard.begin();
        Throwable rejected = null;
        try {
            switch (arguments[0]) {
                case "fork-join" -> { try (var resource = new ForkJoinPool(1)) {} }
                case "virtual-executor" -> { try (var resource = Executors.newVirtualThreadPerTaskExecutor()) {} }
                case "thread" -> {
                    var thread = new Thread(() -> {});
                    thread.start();
                    thread.join();
                }
                case "virtual-thread" -> Thread.startVirtualThread(() -> {}).join();
                case "client-socket" -> { try (var resource = new Socket()) {} }
                case "datagram" -> { try (var resource = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {} }
                case "nio-socket" -> { try (var resource = SocketChannel.open()) {} }
                case "unix-server-socket" -> {
                    try (var resource = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {}
                }
                case "nio-datagram" -> { try (var resource = DatagramChannel.open()) {} }
                default -> throw new IllegalArgumentException("unknown resource probe");
            }
        } catch (Throwable failure) {
            rejected = failure;
        }
        var effects = LoadGuard.end();
        if (effects.isEmpty()) {
            throw new AssertionError("namespace guard did not reject " + arguments[0], rejected);
        }
        System.out.println("NAMESPACE_LOAD_EFFECTS " + effects);
    }
}
