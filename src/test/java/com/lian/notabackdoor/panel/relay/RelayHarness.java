package com.lian.notabackdoor.panel.relay;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Private test process; never included in the production JAR. */
public final class RelayHarness {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("origin, data directory and optional resume required");
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        local.setExecutor(Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "relay-harness-local");
            thread.setDaemon(true);
            return thread;
        }));
        int port = local.getAddress().getPort();
        local.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/slow")) {
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[64 * 1024]);
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(3000);
                    exchange.getResponseBody().write(new byte[64 * 1024]);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            boolean localHost = ("127.0.0.1:" + port).equals(host);
            boolean localOrigin = exchange.getRequestMethod().equals("GET")
                    || ("http://127.0.0.1:" + port).equals(origin);
            byte[] body = ("{\"path\":\"" + exchange.getRequestURI().getPath()
                    + "\",\"host_ok\":" + localHost + ",\"origin_ok\":" + localOrigin
                    + ",\"length\":" + exchange.getRequestBody().readAllBytes().length + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (exchange.getRequestURI().getPath().equals("/api/login")) {
                exchange.getResponseHeaders().set("Set-Cookie",
                        "nab_session=HarnessSession; HttpOnly; SameSite=Strict; Path=/; Max-Age=7200");
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        local.start();
        try (RelayClient client = new RelayClient(Path.of(args[1]), port,
                Logger.getLogger("relay-integration-test"))) {
            if (args.length == 3 && args[2].equals("resume")) {
                System.out.println("READY " + client.status());
            } else {
                RelayClient.Pairing pair = client.pair(args[0]);
                System.out.println("PAIR " + pair.url() + " " + pair.code());
            }
            System.out.flush();
            BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
            for (String line; (line = commands.readLine()) != null; ) {
                if (line.equals("status")) {
                    System.out.println("STATUS " + client.status());
                    System.out.flush();
                } else if (line.equals("revoke")) {
                    System.out.println("REVOKE " + client.revoke());
                    System.out.flush();
                } else if (line.equals("stop")) {
                    break;
                }
            }
        } finally {
            local.stop(0);
        }
    }
}
