package org.mtrpoint.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=org.mtr.core.generated.data.VehicleSchema.class,remap=false)
public interface VehicleProgressAccess { @Accessor("railProgress") double point$progress(); }
