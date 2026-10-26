package com.epherical.datapackeditor.editor.http;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class EditorHttpServer implements AutoCloseable {
    private final HttpRoutes routes = new HttpRoutes();
    private HttpServer server;
    private ExecutorService executor;

    public static EditorHttpServer create(Consumer<HttpRoutes> configure) {
        EditorHttpServer application = new EditorHttpServer();
        configure.accept(application.routes);
        return application;
    }

    public synchronized EditorHttpServer start(String host, int port) {
        if (server != null) throw new IllegalStateException("Editor HTTP server is already running");
        HttpServer starting = null;
        ExecutorService startingExecutor = null;
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!address.isLoopbackAddress()) throw new IllegalArgumentException("Editor HTTP server must bind to loopback");
            starting = HttpServer.create(new InetSocketAddress(address, port), 32);
            startingExecutor = Executors.newVirtualThreadPerTaskExecutor();
            starting.setExecutor(startingExecutor);
            routes.freeze();
            starting.createContext(DatapackEditorConstants.PATH_SEPARATOR, routes::handle);
            starting.start();
            server = starting;
            executor = startingExecutor;
            return this;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not start editor HTTP server", exception);
        } finally {
            if (server == null) {
                if (starting != null) starting.stop(0);
                if (startingExecutor != null) startingExecutor.shutdownNow();
            }
        }
    }

    public synchronized int port() {
        if (server == null) throw new IllegalStateException("Editor HTTP server is stopped");
        return server.getAddress().getPort();
    }

    public synchronized void stop() {
        if (server == null) return;
        try {
            server.stop(1);
        } finally {
            server = null;
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
