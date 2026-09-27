package com.epherical.epherolib.editor;

import com.epherical.epherolib.impl.DataPackSchemaImpl;
import com.epherical.epherolib.impl.schemas.RecipeSchemaBuilder;
import com.epherical.epherolib.impl.schemas.TagSchemaBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class EditorWebServer {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 8700;

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String NO_STORE = "no-store";
    public static final String CONTENT_TYPE = "application/json";
    public static final String HTTP = "http://";
    private static Javalin application;
    private static String accessToken;

    public static synchronized StartResult start(MinecraftServer server) {
        if (application != null) {
            return new StartResult(false, editorUrl());
        }

        accessToken = createToken();
        Javalin newApplication = null;
        try {
            EditorWorkspace.initialize();
            EditorDatapacks datapacks = EditorDatapacks.create(server);
            newApplication = Javalin.create(config -> {
                config.staticFiles.add(files -> {
                    files.hostedPath = "/";
                    files.directory = "/epherolib/editor";
                    files.location = Location.CLASSPATH;
                });

                config.routes.before(ctx -> {
                    ctx.header("X-Content-Type-Options", "nosniff");
                    ctx.header("Referrer-Policy", "no-referrer");
                    ctx.header("Content-Security-Policy",
                            """
                                    default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline'; \
                                    img-src 'self' data: blob:; connect-src 'self' https://cdn.jsdelivr.net; worker-src 'self' blob:
                                    """);
                });
                config.routes.before("/api/*", EditorWebServer::requireAccessToken);
                config.routes.get("/api/status", ctx -> ctx
                        .contentType(CONTENT_TYPE)
                        .result(statusJson()));
                config.routes.get("/api/schema/recipes", ctx -> ctx
                        .header(CACHE_CONTROL, NO_STORE)
                        .contentType("application/schema+json")
                        .result(RecipeSchemaBuilder.build(server)));
                config.routes.get("/api/schema/tags", ctx -> ctx
                        .header(CACHE_CONTROL, NO_STORE)
                        .contentType(CONTENT_TYPE)
                        .result(TagSchemaBuilder.build(server.registryAccess())));
                config.routes.get("/api/schema/datapack", ctx -> ctx
                        .header(CACHE_CONTROL, NO_STORE)
                        .contentType(CONTENT_TYPE)
                        .result(DataPackSchemaImpl.build(server.registryAccess())));
                EditorWorkspace.registerRoutes(config);
                datapacks.registerRoutes(config);
                new EditorAssets(server).registerRoutes(config);
                new EditorLootSimulation(server).registerRoutes(config);
                new EditorRecipeCatalog(server).registerRoutes(config);
                // todo; maybe add a visualizer for other loot table types...
                // todo; add a visualizer for configured or placed features
                // todo; biome visualizer?
                // todo; recipe visualizer for sure, maybe it looks at the corresponding Menu class
                //  so it can get the slots and the texture used? not sure, need something that'll work with
                //  fairly easily though...
                // todo; tag visualizer? eh


                // todo; probably more of a feature, but what if you could edit the file by also clicking on buttons
                //  does it reference the json schema we created? probably the best way to handle it, in a generic fashion...
            });
            newApplication.start(HOST, PORT);
            application = newApplication;
            return new StartResult(true, editorUrl());
        } catch (RuntimeException | LinkageError exception) {
            accessToken = null;
            if (newApplication != null) {
                try {
                    newApplication.stop();
                } catch (RuntimeException | LinkageError ignored) {
                    // Preserve the startup exception, which contains the useful cause.
                }
            }
            throw exception;
        }
    }

    public static synchronized boolean stop() {
        if (application == null) {
            return false;
        }

        Javalin runningApplication = application;
        application = null;
        accessToken = null;
        runningApplication.stop();
        return true;
    }

    public static synchronized boolean isRunning() {
        return application != null;
    }

    public static synchronized String editorUrl() {
        if (accessToken == null) {
            return HTTP + HOST + ":" + PORT + "/";
        }
        return HTTP + HOST + ":" + PORT + "/#token=" + accessToken;
    }

    private static void requireAccessToken(Context context) {
        String authorization = context.header("Authorization");
        String expected = "Bearer " + accessToken;
        if (authorization == null || !constantTimeEquals(authorization, expected)) {
            context.status(HttpStatus.UNAUTHORIZED)
                    .contentType(CONTENT_TYPE)
                    .result("{\"error\":\"Open the editor from the in-game command to authenticate.\"}")
                    .skipRemainingHandlers();
        }
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String statusJson() {
        return """
                {
                  "name": "EpheroLib Editor",
                  "state": "running",
                  "host": "%s",
                  "port": %d,
                  "security": "loopback + bearer token",
                  "workspace": "%s",
                  "features": ["Preact", "Tailwind CSS", "Monaco", "Three.js"]
                }
                """.formatted(HOST, PORT, EditorWorkspace.DIRECTORY_NAME);
    }

    public record StartResult(boolean started, String url) {}
}
