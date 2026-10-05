package org.mtrpoint.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class PointRenderType {
    private PointRenderType() {}
    public static RenderType texture(ResourceLocation texture){
        // Keep the vanilla entity pipeline: it consumes per-face normals and therefore
        // preserves the rail/web/bed relief when shaders are disabled as well.
        return RenderType.entityCutoutNoCull(texture);
    }
}
