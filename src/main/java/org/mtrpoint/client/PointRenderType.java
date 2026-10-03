package org.mtrpoint.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class PointRenderType {
    private PointRenderType() {}
    public static RenderType texture(ResourceLocation texture){
        // Rail textures already contain their material shading. The entity shader applies
        // another directional diffuse term to NEW_ENTITY normals, which makes the whole
        // rail web fall to ambient light from some camera angles. Vanilla text uses the
        // same packed lightmap without that second diffuse term, keeps the texture in a
        // normal classified pipeline for shader loaders, and has no face culling.
        return RenderType.text(texture);
    }
}
