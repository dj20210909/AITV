package com.aitv.assistant;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans the local /24 for the AITV HTTP service.
 *
 * <p>AITV starts its NanoHTTPD server on the first free port in 9978..9998. The assistant scans
 * the preset 10-port window 9978..9987 first; it checks TCP connectivity, then verifies that
 * /device returns a valid AITV device description, so an unrelated open port is ignored.
 */
public final class LanScanner {

    private static final int PORT_START = 9978;
    private static final int PORT_COUNT = 10;
    private static final int PORT_END = PORT_START + PORT_COUNT - 1;
    private static final int CONNECT_TIMEOUT_MS = 180;
    private static final int THREADS = 96;

    public interface Listener {
        void onDevice(AitvDevice device);
        void onFinished(int count);
        void onError(String message);
    }

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile ExecutorService pool;

    public boolean isScanning() {
        return scanning.get();
    }

    public void cancel() {
        scanning.set(false);
        ExecutorService current = pool;
        if (current != null) current.shutdownNow();
    }

    public void scan(Listener listener) {
        cancel();
        String local = findLocalIpv4();
        if (local == null) {
            listener.onError("未找到局域网 IPv4 地址，请确认已连接 Wi-Fi/以太网");
            return;
        }
        int dot = local.lastIndexOf('.');
        if (dot <= 0) {
            listener.onError("无法识别网段: " + local);
            return;
        }
        String prefix = local.substring(0, dot + 1);
        scanning.set(true);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        pool = executor;
        AtomicInteger pending = new AtomicInteger(254);
        AtomicInteger found = new AtomicInteger();
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            executor.execute(() -> {
                try {
                    if (!scanning.get()) return;
                    AitvDevice device = probeHost(host);
                    if (device != null) {
                        found.incrementAndGet();
                        listener.onDevice(device);
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        scanning.set(false);
                        listener.onFinished(found.get());
                    }
                }
            });
        }
        executor.shutdown();
    }

    public void scanHost(String host, Listener listener) {
        cancel();
        scanning.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        pool = executor;
        executor.execute(() -> {
            try {
                AitvDevice device = probeHost(host.trim());
                if (device == null) listener.onError("未发现 AITV 服务: " + host);
                else listener.onDevice(device);
            } finally {
                scanning.set(false);
                listener.onFinished(0);
            }
        });
        executor.shutdown();
    }

    public void scanHost(String host, int port, Listener listener) {
        cancel();
        scanning.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        pool = executor;
        executor.execute(() -> {
            try {
                AitvDevice device = AitvClient.probe(host.trim(), port);
                if (device == null) listener.onError("未发现 AITV 服务: " + host + ":" + port);
                else listener.onDevice(device);
            } finally {
                scanning.set(false);
                listener.onFinished(0);
            }
        });
        executor.shutdown();
    }

    private AitvDevice probeHost(String host) {
        for (int port = PORT_START; port <= PORT_END; port++) {
            if (!scanning.get()) return null;
            if (!connect(host, port)) continue;
            AitvDevice device = AitvClient.probe(host, port);
            if (device != null) return device;
        }
        return null;
    }

    private boolean connect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String findLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
