package ai.opencode.app;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P7: in-app HTTP proxy (CONNECT + absolute-URI GET/POST) on 127.0.0.1.
 *
 * OPT-IN ESCAPE HATCH (Diagnostics → "DNS bridge"), off by default. The
 * bundled opencode binary is an Android/NDK bionic build (interpreter
 * /system/bin/linker64), so it uses netd DNS natively and needs no proxy.
 * The bridge exists for devices where that path is broken (exotic VPN,
 * per-app private DNS quirks): it resolves hostnames with the ANDROID OS
 * resolver and tunnels raw bytes, and NO_PROXY covers our own loopback
 * API. Bun's fetch (what opencode uses) honours HTTP(S)_PROXY env vars,
 * which applyEnv exports only when the bridge is enabled.
 */
public final class ProxyServer {

    private ProxyServer() {}

    private static volatile int port = -1;
    private static volatile ServerSocket acceptor;
    private static final AtomicInteger conns = new AtomicInteger();
    private static final StringBuilder errTail = new StringBuilder();

    /** Start once; returns the local port, or -1 if the proxy is unavailable. */
    public static int ensureStarted(Context c) {
        if (port > 0 && acceptor != null && !acceptor.isClosed()) return port;
        synchronized (ProxyServer.class) {
            if (port > 0 && acceptor != null && !acceptor.isClosed()) return port;
            try {
                ServerSocket ss = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
                ss.setReuseAddress(true);
                acceptor = ss;
                port = ss.getLocalPort();
                Thread t = new Thread(() -> acceptLoop(ss), "oc-proxy-accept");
                t.setDaemon(true);
                t.start();
                note("proxy listening on 127.0.0.1:" + port);
                return port;
            } catch (Exception e) {
                note("proxy start FAILED: " + e);
                port = -1;
                return -1;
            }
        }
    }

    public static int port() { return port; }
    public static int connections() { return conns.get(); }
    public static String errors() { synchronized (errTail) { return errTail.toString(); } }

    private static void note(String s) {
        synchronized (errTail) {
            if (errTail.length() > 4000) errTail.delete(0, 2000);
            errTail.append(s).append('\n');
        }
    }

    private static void acceptLoop(ServerSocket ss) {
        while (true) {
            try {
                Socket client = ss.accept();
                Thread t = new Thread(() -> handle(client), "oc-proxy");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (ss.isClosed()) return;
                note("accept: " + e);
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** One proxied client connection. */
    private static void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(30_000); // header read only; tunnels clear it
            InputStream in = client.getInputStream();
            String reqline = readLine(in);
            if (reqline == null || reqline.isEmpty()) { client.close(); return; }
            // drain headers (we only need the request line for CONNECT; for
            // absolute-URI we re-emit the captured headers verbatim)
            StringBuilder headers = new StringBuilder();
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                headers.append(l).append("\r\n");
            }
            client.setSoTimeout(0);

            if (reqline.regionMatches(true, 0, "CONNECT ", 0, 8)) {
                doConnect(client, in, reqline.substring(8).trim());
            } else {
                doPlainHttp(client, in, reqline, headers.toString());
            }
        } catch (Exception e) {
            note("client: " + e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** CONNECT host:port — tunnel after resolving with the OS resolver. */
    private static void doConnect(Socket client, InputStream cin, String hostport) {
        conns.incrementAndGet();
        String host = hostport;
        int p = 443;
        int c = host.lastIndexOf(':');
        if (c > 0) {
            host = hostport.substring(0, c);
            try { p = Integer.parseInt(hostport.substring(c + 1)); } catch (Exception ignored) {}
        }
        Socket up = null;
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            IOException last = null;
            for (InetAddress a : addrs) {
                try {
                    up = new Socket();
                    up.connect(new java.net.InetSocketAddress(a, p), 15_000);
                    break;
                } catch (IOException e) {
                    last = e;
                    try { up.close(); } catch (Exception ignored) {}
                    up = null;
                }
            }
            if (up == null) throw (last != null ? last : new IOException("no addresses"));
            up.setTcpNoDelay(true);
            OutputStream cout = client.getOutputStream();
            cout.write(("HTTP/1.1 200 Connection established\r\n\r\n").getBytes("UTF-8"));
            cout.flush();
            pump(client, cin, up);
        } catch (Exception e) {
            note("CONNECT " + hostport + " failed: " + e);
            try {
                OutputStream cout = client.getOutputStream();
                cout.write(("HTTP/1.1 502 " + sanitize(e) + "\r\n\r\n").getBytes("UTF-8"));
                cout.flush();
            } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
            if (up != null) try { up.close(); } catch (Exception ignored) {}
        }
    }

    /** Absolute-URI plain-HTTP request — forward with Connection: close. */
    private static void doPlainHttp(Socket client, InputStream cin,
                                    String reqline, String headers) {
        conns.incrementAndGet();
        try {
            String[] parts = reqline.split(" ");
            if (parts.length < 2) throw new IOException("bad request line");
            URI u = URI.create(parts[1]);
            String host = u.getHost();
            int p = u.getPort() > 0 ? u.getPort() : 80;
            if (host == null) throw new IOException("no host in " + parts[1]);
            Socket up = new Socket();
            up.connect(new java.net.InetSocketAddress(InetAddress.getByName(host), p), 15_000);
            OutputStream uo = up.getOutputStream();
            String pathq = u.getRawPath() == null ? "/" : u.getRawPath()
                    + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
            uo.write((parts[0] + " " + pathq + " HTTP/1.1\r\n").getBytes("UTF-8"));
            uo.write(("Host: " + host + (p != 80 ? ":" + p : "") + "\r\n").getBytes("UTF-8"));
            uo.write("Connection: close\r\n".getBytes("UTF-8"));
            uo.write(headers.getBytes("UTF-8"));
            uo.write("\r\n".getBytes("UTF-8"));
            uo.flush();
            pump(client, cin, up);
        } catch (Exception e) {
            note("HTTP " + reqline + " failed: " + e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** Bidirectional copy until either side closes. */
    private static void pump(Socket client, InputStream cin, Socket up) {
        try { up.getInputStream(); } catch (IOException e) {
            try { client.close(); } catch (Exception ignored) {}
            return;
        }
        Thread c2u = new Thread(() -> copy(cin, up), "oc-proxy-c2u");
        c2u.setDaemon(true);
        c2u.start();
        try {
            copy(up.getInputStream(), client);
        } catch (Exception ignored) {}
        try { client.close(); } catch (Exception ignored) {}
        try { up.close(); } catch (Exception ignored) {}
    }

    private static void copy(InputStream in, Socket out) {
        byte[] buf = new byte[16 * 1024];
        try (OutputStream o = out.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.flush();
        } catch (Exception ignored) {
        } finally {
            try { out.shutdownOutput(); } catch (Exception ignored) {}
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder(80);
        int prev = -1;
        while (true) {
            int i = in.read();
            if (i < 0) return b.length() == 0 ? null : b.toString();
            if (prev == '\r' && i == '\n') return b.substring(0, b.length() - 1);
            b.append((char) i);
            prev = i;
            if (b.length() > 8192) throw new IOException("header line too long");
        }
    }

    private static String sanitize(Throwable t) {
        String m = String.valueOf(t.getMessage());
        m = m.replace("\r", " ").replace("\n", " ");
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
