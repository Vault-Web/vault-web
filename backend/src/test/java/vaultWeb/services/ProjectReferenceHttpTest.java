package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ProjectReferenceHttpTest {
  @Test
  void slowResponseBodyAndOversizedResponseAreBounded() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/api/shares/shared-with-me",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            body.write('[');
            body.flush();
            Thread.sleep(10000);
            body.write(']');
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } catch (IOException ignored) {
          }
        });
    server.createContext(
        "/api/files/view",
        exchange -> {
          exchange.sendResponseHeaders(200, 1_048_577);
          try (var body = exchange.getResponseBody()) {
            body.write(new byte[1_048_577]);
          } catch (IOException ignored) {
          }
        });
    server.start();
    try {
      var client =
          new CloudPageMediaClient(
              "synthetic-signing-key-at-least-32-bytes-long",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              false);
      assertTimeoutPreemptively(
          Duration.ofSeconds(3),
          () ->
              assertThrows(
                  java.net.http.HttpTimeoutException.class,
                  () ->
                      client.fetchProjectReference(
                          "/api/shares/shared-with-me",
                          "Bearer synthetic",
                          Duration.ofMillis(100))));
      assertThrows(
          IOException.class,
          () ->
              client.fetchProjectReference(
                  "/api/files/view?path=%2Fa", "Bearer synthetic", Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
