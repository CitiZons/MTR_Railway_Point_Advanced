package org.mtrpointprobe.mixin;

import org.mtrpointprobe.RuntimeProbe;

import org.mtrpoint.PointNetwork;
import org.mtrpoint.client.PointClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Development jar only: latch receipt before a later BR snapshot replaces the display state. */
@Mixin(value=PointClient.class,remap=false)
public class ProbeMotionMixin {
    @Inject(method="motion",at=@At("HEAD"))
    private static void observed(PointNetwork.Motion packet,CallbackInfo ci){RuntimeProbe.observeMotion(packet);}
}
