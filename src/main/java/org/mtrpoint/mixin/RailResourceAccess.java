package org.mtrpoint.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=org.mtr.mod.generated.resource.RailResourceSchema.class,remap=false)
public interface RailResourceAccess {
    @Accessor("modelResource") String point$model();
    @Accessor("textureResource") String point$texture();
    @Accessor("flipTextureV") boolean point$flipV();
}
