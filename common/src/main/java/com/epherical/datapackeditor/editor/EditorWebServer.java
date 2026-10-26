package com.epherical.datapackeditor.editor;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.editor.assets.EditorAssets;
import com.epherical.datapackeditor.editor.assets.EditorDatapacks;
import com.epherical.datapackeditor.editor.assets.EditorSchemas;
import com.epherical.datapackeditor.editor.assets.EditorStaticFiles;
import com.epherical.datapackeditor.editor.feature.EditorConfiguredFeaturePreview;
import com.epherical.datapackeditor.editor.loot.EditorLootSimulation;
import com.epherical.datapackeditor.editor.recipe.EditorIngredientPreview;
import com.epherical.datapackeditor.editor.recipe.EditorRecipeCatalog;
import com.epherical.datapackeditor.editor.recipe.EditorRecipePreview;
import com.epherical.datapackeditor.editor.http.EditorHttpServer;
import com.epherical.datapackeditor.editor.http.HttpRoutes;
import net.minecraft.server.MinecraftServer;

public class EditorWebServer {

    private static EditorHttpServer application;
    private static EditorAssets assets;
    private static MemoryChecker memoryWatcher;
    private static EditorSchemas schemas;

    public static synchronized StartResult start(MinecraftServer server) {
        if (application != null) {
            return new StartResult(false, editorUrl());
        }

        EditorHttpServer newApplication = null;
        EditorAssets newAssets = null;
        MemoryChecker newMemoryWatcher = null;
        EditorSchemas newSchemas = null;
        try {
            newMemoryWatcher = new MemoryChecker(EditorWebServer::stopForPressure);
            memoryWatcher = newMemoryWatcher;
            newMemoryWatcher.start();
            MemoryChecker configuredMemoryWatcher = newMemoryWatcher;
            EditorWorkspace.initialize();
            EditorDatapacks datapacks = EditorDatapacks.create(server);
            newAssets = new EditorAssets(server);
            EditorAssets configuredAssets = newAssets;
            newSchemas = new EditorSchemas(server);
            EditorSchemas configuredSchemas = newSchemas;
            newApplication = EditorHttpServer.create(routes -> {
                configuredMemoryWatcher.register(routes);
                EditorStaticFiles.register(routes);

                registerSecurityHeaders(routes);
                configuredSchemas.registerRoutes(routes);
                EditorWorkspace.registerRoutes(routes);
                datapacks.registerRoutes(routes);
                configuredAssets.registerRoutes(routes);
                new EditorLootSimulation(server).registerRoutes(routes);
                new EditorRecipeCatalog(server).registerRoutes(routes);
                new EditorIngredientPreview(server).registerRoutes(routes);
                new EditorRecipePreview(server).registerRoutes(routes);
                new EditorConfiguredFeaturePreview(server).registerRoutes(routes);
                // todo; maybe add a visualizer for other loot table types...
                // todo; biome visualizer?
                // todo; recipe visualizer for sure, maybe it looks at the corresponding Menu class
                //  so it can get the slots and the texture used? not sure, need something that'll work with
                //  fairly easily though...
                // todo; tag visualizer? eh

                // todo; probably more of a feature, but what if you could edit the file by also clicking on buttons
                //  does it reference the json schema we created? probably the best way to handle it, in a generic fashion...
            });
            newApplication.start(DatapackEditorConstants.HTTP_LOOPBACK_HOST, DatapackEditorConstants.EDITOR_HTTP_PORT);
            application = newApplication;
            assets = newAssets;
            schemas = newSchemas;
            return new StartResult(true, editorUrl());
        } catch (RuntimeException | LinkageError exception) {
            if (newSchemas != null) newSchemas.close();
            if (newApplication != null) {
                try {
                    newApplication.stop();
                } catch (RuntimeException | LinkageError ignored) {
                    // Preserve the startup exception, which contains the useful cause.
                }
            }
            if (newAssets != null) newAssets.close();
            if (newMemoryWatcher != null) newMemoryWatcher.close();
            memoryWatcher = null;
            throw exception;
        }
    }

    public static void registerSecurityHeaders(HttpRoutes routes) {
        routes.before(context -> {
            context.header("X-Content-Type-Options", "nosniff");
            context.header("Referrer-Policy", "no-referrer");
            context.header("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data: blob:; connect-src 'self'; worker-src 'self' blob:");
        });
    }

    public static synchronized boolean stop() {
        if (application == null) {
            return false;
        }

        EditorHttpServer runningApplication = application;
        application = null;
        if (schemas != null) schemas.close();
        schemas = null;
        try {
            runningApplication.stop();
        } finally {
            if (assets != null) assets.close();
            assets = null;
            if (memoryWatcher != null) memoryWatcher.close();
            memoryWatcher = null;
        }
        return true;
    }

    public static synchronized boolean isRunning() {
        return application != null;
    }

    private static synchronized void stopForPressure() {
        if (memoryWatcher != null && memoryWatcher.isTripped()) stop();
    }

    public static synchronized String editorUrl() {
        return DatapackEditorConstants.HTTP_ORIGIN_PREFIX + DatapackEditorConstants.HTTP_LOOPBACK_HOST + ":" + DatapackEditorConstants.EDITOR_HTTP_PORT + DatapackEditorConstants.PATH_SEPARATOR;
    }

    public record StartResult(boolean started, String url) {}
}
