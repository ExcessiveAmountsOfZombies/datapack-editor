package com.epherical.datapackeditor.editor;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.mojang.logging.LogUtils;
import com.sun.management.OperatingSystemMXBean;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.epherical.datapackeditor.editor.advancement.AdvancementCacher.SelectionException;
import io.javalin.config.JavalinConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public class MemoryChecker {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile MemoryChecker active;
    private final LongSupplier usedHeap;
    private final LongSupplier maximumHeap;
    private final LongSupplier availableRam;
    private final LongSupplier totalRam;
    private final Runnable shutdown;
    private final AtomicBoolean tripped = new AtomicBoolean();
    private final Semaphore requests = new Semaphore(3);
    private ScheduledExecutorService monitor;
    private volatile String reason;
    private long lastSample;

    public MemoryChecker(Runnable shutdown) {
        this(() -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                () -> Runtime.getRuntime().maxMemory(), MemoryChecker::availableRam, MemoryChecker::totalRam, shutdown);
    }

    public MemoryChecker(LongSupplier usedHeap, LongSupplier maximumHeap, LongSupplier availableRam,
                         LongSupplier totalRam, Runnable shutdown) {
        this.usedHeap = usedHeap;
        this.maximumHeap = maximumHeap;
        this.availableRam = availableRam;
        this.totalRam = totalRam;
        this.shutdown = shutdown;
    }

    public synchronized void start() {
        active = this;
        check();
        monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                .name("datapack-editor-memory-monitor").factory());
        monitor.scheduleWithFixedDelay(this::poll, DatapackEditorConstants.MEMORY_POLL_INTERVAL_MS, DatapackEditorConstants.MEMORY_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void close() {
        if (active == this) active = null;
        if (monitor != null) monitor.shutdownNow();
    }

    public static void checkpoint() {
        MemoryChecker watcher = active;
        if (watcher != null) watcher.check();
    }

    public static void requireRunning() {
        MemoryChecker watcher = active;
        if (watcher != null && watcher.tripped.get()) throw new CapacityException(watcher.reason);
    }

    public boolean isTripped() {
        return tripped.get();
    }

    public void check() {
        poll();
        if (tripped.get()) throw new CapacityException(reason);
    }

    public synchronized void poll() {
        if (tripped.get()) return;
        long now = System.nanoTime();
        if (lastSample != 0 && now - lastSample < TimeUnit.MILLISECONDS.toNanos(25)) return;
        lastSample = now;
        long maximum = maximumHeap.getAsLong();
        long reserve = Math.max(DatapackEditorConstants.BYTES_64_MIB, maximum / 8);
        if (usedHeap.getAsLong() >= maximum - reserve) {
            trip("Editor stopped: Java heap headroom fell below " + reserve / DatapackEditorConstants.MEBIBYTE + " MiB");
            return;
        }
        long total = totalRam.getAsLong();
        long free = availableRam.getAsLong();
            long ramReserve = Math.clamp(total / 16, (256 * DatapackEditorConstants.MEBIBYTE), (long) DatapackEditorConstants.KIBIBYTE * DatapackEditorConstants.MEBIBYTE);
        if (total > 0 && free >= 0 && free < ramReserve) {
            trip("Editor stopped: available system RAM fell below " + ramReserve / DatapackEditorConstants.MEBIBYTE + " MiB");
        }
    }

    public synchronized void trip(String message) {
        if (tripped.get()) return;
        reason = message;
        tripped.set(true);
        LOGGER.error("{}", message);
        // Jetty must never be stopped from the request thread it is trying to join.
        Thread.ofPlatform().daemon().name("datapack-editor-memory-stop").start(shutdown);
    }

    public void register(JavalinConfig config) {
        config.http.maxRequestSize = DatapackEditorConstants.EDITOR_JSON_MAX_BYTES + DatapackEditorConstants.KIBIBYTE;
        config.jetty.threadPool = new QueuedThreadPool(24, 4);
        config.routes.exception(CapacityException.class, (error, context) -> {
            JsonObject result = new JsonObject();
            result.addProperty(DatapackEditorConstants.JSON_ERROR, error.getMessage());
            if (error instanceof SelectionException selection) result.add(DatapackEditorConstants.JSON_ROOTS, selection.roots());
            context.status(413).contentType(DatapackEditorConstants.HTTP_JSON).result(result.toString());
        });
        config.routes.before(DatapackEditorConstants.ROUTE_API, unused1 -> check());
        // Hold admission until the response stream finishes, including client disconnects.
        config.jetty.modifyServletContextHandler(handler -> handler.addFilter(new FilterHolder((request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            if (httpRequest.getRequestURI().equals(DatapackEditorConstants.ROUTE_EDITOR_STOP)) {chain.doFilter(request, response); return;}
            if (!requests.tryAcquire()) {
                httpResponse.setStatus(DatapackEditorConstants.HTTP_TOO_MANY_REQUESTS);
                httpResponse.setHeader(DatapackEditorConstants.HTTP_RETRY_AFTER, DatapackEditorConstants.HTTP_RETRY_SECONDS);
                httpResponse.setContentType(DatapackEditorConstants.HTTP_JSON);
                httpResponse.getWriter().write("{\"error\":\"Editor is busy; try again shortly\"}");
                return;
            }
            try {chain.doFilter(request, response);}
            finally {requests.release();}
        }), DatapackEditorConstants.ROUTE_API, EnumSet.of(DispatcherType.REQUEST)));
        config.routes.post(DatapackEditorConstants.ROUTE_EDITOR_STOP, context -> {
            context.status(202).contentType(DatapackEditorConstants.HTTP_JSON).result("{\"state\":\"stopping\"}");
            trip("Editor stopped at the browser's memory safeguard request");
        });
    }

    public static InputStream input(InputStream input, long limit, String description) {
        return new LimitedInput(input, limit, description);
    }

    public static byte[] bytes(InputStream input, int limit, String description) throws IOException {
        checkpoint();
        byte[] bytes = input(input, limit, description).readNBytes(limit + 1);
        if (bytes.length > limit) throw new CapacityException(description + " exceeds the editor's " + limit / DatapackEditorConstants.KIBIBYTE + " KiB limit");
        checkpoint();
        return bytes;
    }

    public static JsonElement json(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        validateJson(text);
        return JsonParser.parseString(text);
    }

    public static void validateJson(byte[] bytes) throws IOException {
        validateJson(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void validateJson(String text) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(text));
        validateJson(reader, 0, new int[]{0});
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException(DatapackEditorConstants.MESSAGE_TRAILING_JSON);
    }

    private static void validateJson(JsonReader reader, int depth, int[] values) throws IOException {
        requireRunning();
        if (depth > DatapackEditorConstants.JSON_MAX_DEPTH || ++values[0] > DatapackEditorConstants.JSON_MAX_VALUES) throw new CapacityException("Asset JSON exceeds its complexity budget (depth " + DatapackEditorConstants.JSON_MAX_DEPTH + ", values " + DatapackEditorConstants.JSON_MAX_VALUES + ")");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                while (reader.hasNext()) {reader.nextName(); validateJson(reader, depth + 1, values);}
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) validateJson(reader, depth + 1, values);
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IOException("Invalid JSON");
        }
    }

    private static class LimitedInput extends FilterInputStream {
        private final long limit;
        private final String description;
        private long read;
        public LimitedInput(InputStream input, long limit, String description) {
            super(input);
            this.limit = limit;
            this.description = description;
        }
        private void count(int count) {
            requireRunning();
            if (count > 0) read += count;
            if (read > limit) throw new CapacityException(description + " exceeds its byte budget");
        }
        @Override public int read() throws IOException {
            requireRunning();
            int value = in.read();
            count(value < 0 ? 0 : 1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            requireRunning();
            int count = in.read(bytes, offset, (int) Math.min(length, Math.max(1, limit - read + 1)));
            count(count);
            return count;
        }
        @Override public long skip(long count) throws IOException {
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, count))];
            long skipped = 0;
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
    }

    private static long availableRam() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean ? bean.getFreeMemorySize() : -1;
    }

    private static long totalRam() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean ? bean.getTotalMemorySize() : -1;
    }

    public static class CapacityException extends RuntimeException {
        public CapacityException(String message) {
            super(message);
        }
    }
}
