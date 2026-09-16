package org.mtrpoint.mixin;

import org.mtr.core.simulation.Simulator;
import org.mtrpoint.compat.BrObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=Simulator.class,remap=false,priority=500)
public abstract class SimulatorObserverMixin {
    @Inject(method="tick",at=@At("RETURN"))
    private void point$observe(CallbackInfo ci){BrObserver.observe((Simulator)(Object)this);}
}
