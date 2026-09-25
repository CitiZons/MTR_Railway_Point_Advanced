package org.mtrpoint;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@Mod(PointMod.ID)
public final class PointMod {
    public static final String ID="mtr_railway_point_advanced";
    public static final org.slf4j.Logger LOG=com.mojang.logging.LogUtils.getLogger();
    public PointMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT,PointConfig.SPEC);
        PointNetwork.init();
        MinecraftForge.EVENT_BUS.addListener(this::login);
        MinecraftForge.EVENT_BUS.addListener(this::dimension);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener(this::stopped);
    }
    private void login(PlayerEvent.PlayerLoggedInEvent e){if(e.getEntity() instanceof ServerPlayer p)PointNetwork.full(p);}
    private void dimension(PlayerEvent.PlayerChangedDimensionEvent e){if(e.getEntity() instanceof ServerPlayer p)PointNetwork.full(p);}
    private void tick(TickEvent.ServerTickEvent e){if(e.phase==TickEvent.Phase.END)PointNetwork.flushMotion();}
    private void stopped(ServerStoppedEvent e){org.mtrpoint.compat.BrObserver.clear();}
}
