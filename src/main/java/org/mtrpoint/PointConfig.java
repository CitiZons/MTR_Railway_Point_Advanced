package org.mtrpoint;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only presentation settings; these never affect saved point geometry. */
public final class PointConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue UI_SCALE;
    public static final ForgeConfigSpec.DoubleValue LOD_NEAR,LOD_FAR;
    static {
        ForgeConfigSpec.Builder builder=new ForgeConfigSpec.Builder();
        builder.comment("MTR Railway Point Advanced client settings").push("interface");
        UI_SCALE=builder.comment("Scale of this mod's blueprint and track-plan screens")
            .translation("mtrpoint.config.ui_scale").defineInRange("uiScale",1D,.5D,1.25D);
        builder.pop().push("rendering");
        LOD_NEAR=builder.comment("High-to-medium rail detail distance in metres; 0 disables the high detail band")
            .translation("mtrpoint.config.lod_near").defineInRange("lodNearDistance",4D,0D,4096D);
        LOD_FAR=builder.comment("Medium-to-low rail detail distance in metres; must be >= lodNearDistance. Equal distances skip medium detail; both 0 always select low detail")
            .translation("mtrpoint.config.lod_far").defineInRange("lodFarDistance",12D,0D,4096D);
        builder.pop();SPEC=builder.build();
    }
    private PointConfig(){}
    public static double uiScale(){return UI_SCALE.get();}
    public static double lodNear(){return LOD_NEAR.get();}
    public static double lodFar(){return Math.max(lodNear(),LOD_FAR.get());}
}
