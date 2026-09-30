package org.mtrpoint.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class PointRenderType {
    private PointRenderType() {}
    public static RenderType texture(ResourceLocation texture){
        // Use the vanilla RenderType itself. Iris/Oculus recognizes this exact state
        // and routes it through the shader pack's entity pipeline; a custom RenderType
        // with the same shader still gets treated as an unclassified draw.
        return RenderType.entityCutoutNoCull(texture);
    }
}
