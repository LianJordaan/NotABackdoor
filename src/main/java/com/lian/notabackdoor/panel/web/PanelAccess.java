package com.lian.notabackdoor.panel.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/** The addresses from which the HTTP panel may be reached. */
public final class PanelAccess {
    public enum Mode { LOCAL, PUBLIC_HTTP }

    private final Mode mode;
    private final int port;
    private final String advertisedOrigin;
    private final String advertisedHost;

    private PanelAccess(Mode mode, int port, String advertisedOrigin, String advertisedHost) {
        this.mode = mode;
        this.port = port;
        this.advertisedOrigin = advertisedOrigin;
        this.advertisedHost = advertisedHost;
    }

    public static PanelAccess local(int port) {
        validatePort(port);
        return new PanelAccess(Mode.LOCAL, port, "http://127.0.0.1:" + port, null);
    }

    public static PanelAccess publicHttp(String origin, int port) {
        validatePort(port);
        Objects.requireNonNull(origin, "advertised origin");
        if (origin.isBlank() || !origin.equals(origin.trim())) {
            throw new IllegalArgumentException("The advertised origin must be an exact http://host:port URL");
        }
        URI parsed;
        try {
            parsed = new URI(origin);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("The advertised origin must be an exact http://host:port URL", invalid);
        }
        if (!"http".equals(parsed.getScheme()) || parsed.getHost() == null
                || parsed.getRawUserInfo() != null
                || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty())
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                || parsed.getPort() != port || parsed.getRawAuthority() == null
                || !parsed.getRawAuthority().endsWith(":" + port)
                || !origin.equals(origin.toLowerCase(Locale.ROOT))
                || !origin.equals("http://" + parsed.getRawAuthority())) {
            throw new IllegalArgumentException("The advertised origin must be an exact http://host:port URL matching the panel port");
        }
        String host = parsed.getRawAuthority();
        if (host.startsWith("@") || host.contains("@") || host.contains("\\")
                || isLocalOnlyHost(parsed.getHost())) {
            throw new IllegalArgumentException("The advertised origin needs a connectable host");
        }
        return new PanelAccess(Mode.PUBLIC_HTTP, port, origin, host);
    }

    public Mode mode() { return mode; }
    public int port() { return port; }
    public String advertisedOrigin() { return advertisedOrigin; }
    public String bindAddress() { return isPublic() ? "0.0.0.0" : "127.0.0.1"; }
    public boolean isPublic() { return mode == Mode.PUBLIC_HTTP; }

    boolean allowsHost(String host, InetAddress peer) {
        if (host == null) return false;
        if (isLoopbackHost(host)) return peer != null && peer.isLoopbackAddress();
        return isPublic() && advertisedHost.equals(host);
    }

    boolean allowsOrigin(String origin, InetAddress peer, boolean required) {
        if (origin == null) return !required;
        if (isLoopbackOrigin(origin)) return peer != null && peer.isLoopbackAddress();
        return isPublic() && advertisedOrigin.equals(origin);
    }

    private boolean isLoopbackHost(String host) {
        return host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port);
    }

    private boolean isLoopbackOrigin(String origin) {
        return origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port);
    }

    private static boolean isLocalOnlyHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.equals("localhost.")
                || lower.endsWith(".localhost") || lower.endsWith(".localhost.")) {
            return true;
        }
        if (host.startsWith("[") || host.matches("[0-9.]+")) {
            try {
                InetAddress address = InetAddress.getByName(host);
                return address.isLoopbackAddress() || address.isAnyLocalAddress();
            } catch (UnknownHostException invalid) {
                return true;
            }
        }
        return false;
    }

    private static void validatePort(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("panel.port must be between 1024 and 65535");
        }
    }
}
