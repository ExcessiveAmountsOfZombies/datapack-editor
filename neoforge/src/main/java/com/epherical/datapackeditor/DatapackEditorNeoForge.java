package com.epherical.datapackeditor;

import com.epherical.datapackeditor.editor.EditorCommands;
import com.epherical.datapackeditor.editor.EditorWebServer;
import com.epherical.datapackeditor.impl.schemas.ComponentSchemaRegister;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod("datapack_editor")
public class DatapackEditorNeoForge {

    private static DatapackEditorNeoForge mod;

    public DatapackEditorNeoForge() {
        mod = this;
        CommonPlatform.create(new ForgePlatform());
        ComponentSchemaRegister.register();
        NeoForgeSchemas.register();
        NeoForge.EVENT_BUS.addListener(this::onCommandRegister);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        //FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientInit);
        //FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonInit);
        //MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientInit(FMLClientSetupEvent event) {
        //DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> AModClient::initClient);
        //MinecraftForge.EVENT_BUS.register(new AModClient());
    }

    private void commonInit(FMLCommonSetupEvent event) {

    }

    private void onCommandRegister(RegisterCommandsEvent event) {
        EditorCommands.register(event.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        EditorWebServer.stop();
    }

}
