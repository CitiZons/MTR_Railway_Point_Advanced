package org.mtrpoint;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only presentation settings; these never affect saved point geometry. */
public final class PointConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue UI_SCALE;
    static {
        ForgeConfigSpec.Builder builder=new ForgeConfigSpec.Builder();
        builder.comment("MTR Railway Point Advanced client settings").push("interface");
        UI_SCALE=builder.comment("Scale of this mod's blueprint and track-plan screens")
            .translation("mtrpoint.config.ui_scale").defineInRange("uiScale",1D,.5D,1.25D);
        builder.pop();SPEC=builder.build();
    }
    private PointConfig(){}
    public static double uiScale(){return UI_SCALE.get();}
}
