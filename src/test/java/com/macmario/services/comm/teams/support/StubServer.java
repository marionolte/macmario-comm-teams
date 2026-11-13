package com.macmario.services.comm.teams.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * A minimal local HTTP server for tests: records every request and replies with a
 * response produced by a configurable responder. Backed by the JDK's
 * {@code com.sun.net.httpserver.HttpServer}, so tests make real HTTP round-trips
 * with no mocking library and work offline.
 */
public final class StubServer implements AutoCloseable {

    public record Recorded(String method, String path, String query, String body, Headers headers) {
        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    public record Response(int status, String body) {
        public static Response json(String body) {
            return new Response(200, body);
        }
        public static Response status(int status) {
            return new Response(status, "");
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Recorded, Response> responder = r -> Response.json("{}");

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start stub server", e);
        }
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            var recorded = new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    new String(in, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders());
            requests.add(recorded);

            Response response;
            try {
                response = responder.apply(recorded);
            } catch (RuntimeException e) {
                response = new Response(500, String.valueOf(e.getMessage()));
            }
            byte[] out = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), out.length == 0 ? -1 : out.length);
            try (var os = exchange.getResponseBody()) {
                if (out.length > 0) {
                    os.write(out);
                }
            }
        });
        server.start();
    }

    public void respond(Function<Recorded, Response> responder) {
        this.responder = responder;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public int requestCount() {
        return requests.size();
    }

    public Recorded lastRequest() {
        synchronized (requests) {
            if (requests.isEmpty()) {
                throw new IllegalStateException("No requests recorded");
            }
            return requests.get(requests.size() - 1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
