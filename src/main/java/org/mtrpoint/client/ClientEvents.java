package org.mtrpoint.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.mtrpoint.PointMod;
import org.mtrpoint.geometry.V3;

@Mod.EventBusSubscriber(modid=PointMod.ID,value=Dist.CLIENT)
public final class ClientEvents {
    public static final KeyMapping EDIT=new KeyMapping("key.mtrpoint.blueprint",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_P,"key.categories.mtrpoint");
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){if(e.phase!=TickEvent.Phase.END)return;PointClient.tick();while(EDIT.consumeClick()){var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen!=null)continue;PointClient.rebuild();V3 p=mc.hitResult==null?new V3(mc.player.getX(),mc.player.getY(),mc.player.getZ()):new V3(mc.hitResult.getLocation().x,mc.hitResult.getLocation().y,mc.hitResult.getLocation().z);var view=PointClient.nearest(p);if(view!=null)mc.setScreen(new BlueprintScreen(view));else mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("mtrpoint.no_point"),true);}}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){PointClient.clear();}
    @Mod.EventBusSubscriber(modid=PointMod.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e){e.register(EDIT);}
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent e){e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)manager->PointClient.invalidate());}
    }
}
