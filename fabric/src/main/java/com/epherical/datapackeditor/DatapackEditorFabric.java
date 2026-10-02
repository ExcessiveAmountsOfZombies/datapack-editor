package com.epherical.datapackeditor;

import com.epherical.datapackeditor.editor.EditorCommands;
import com.epherical.datapackeditor.editor.EditorWebServer;
import com.epherical.datapackeditor.impl.schemas.ComponentSchemaRegister;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class DatapackEditorFabric implements ModInitializer {


    @Override
    public void onInitialize() {
        CommonPlatform.create(new FabricPlatform());
        ComponentSchemaRegister.register();


        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EditorCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EditorWebServer.stop());

    }
}
