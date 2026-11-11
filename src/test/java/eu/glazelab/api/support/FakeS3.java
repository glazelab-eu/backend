package eu.glazelab.api.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal in-process S3 endpoint (path-style PUT, GET and DELETE of whole objects) on a dynamic loopback
 * port, for the S3 adapter's tests and API tests that run against {@code GLAZELAB_STORAGE=s3}.
 */
public final class FakeS3 implements AutoCloseable {
    public record Request(String method, String path, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    /** Stored objects by request path ({@code /<bucket>/<key>}). */
    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    /** Answers every request with a 500 while set. */
    public volatile boolean failing;

    public FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
        if (failing) {
            error(exchange, 500, "InternalError");
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "PUT" -> { objects.put(path, body); exchange.sendResponseHeaders(200, -1); }
            case "GET" -> {
                byte[] object = objects.get(path);
                if (object == null) { error(exchange, 404, "NoSuchKey"); return; }
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, object.length);
                exchange.getResponseBody().write(object);
            }
            case "DELETE" -> { objects.remove(path); exchange.sendResponseHeaders(204, -1); }
            default -> exchange.sendResponseHeaders(405, -1);
        }
        exchange.close();
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code>"
                + "<Message>Test error</Message></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, xml.length);
        exchange.getResponseBody().write(xml);
        exchange.close();
    }
}
