package org.mtrpoint;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only presentation settings; these never affect saved point geometry. */
public final class PointConfig {
    private static final double DEFAULT_UI_SCALE=1D,DEFAULT_LOD_NEAR=4D,DEFAULT_LOD_FAR=12D,DEFAULT_ANIMATION_DISTANCE=24D,DEFAULT_TAKEOVER_DISTANCE=64D;
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue UI_SCALE;
    public static final ForgeConfigSpec.DoubleValue LOD_NEAR,LOD_FAR,ANIMATION_DISTANCE,TAKEOVER_DISTANCE;
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
        ANIMATION_DISTANCE=builder.comment("Turnout animation distance in metres; 0 freezes all turnout animation")
            .translation("mtrpoint.config.animation_distance").defineInRange("animationDistance",24D,0D,4096D);
        TAKEOVER_DISTANCE=builder.comment("Distance in metres at which Point Advanced takes over turnout rendering; 0 disables takeover")
            .translation("mtrpoint.config.takeover_distance").defineInRange("takeoverDistance",64D,0D,4096D);
        builder.pop();SPEC=builder.build();
    }
    private PointConfig(){}
    public static double uiScale(){return value(UI_SCALE,DEFAULT_UI_SCALE);}
    public static double lodNear(){return value(LOD_NEAR,DEFAULT_LOD_NEAR);}
    public static double lodFar(){return Math.max(lodNear(),value(LOD_FAR,DEFAULT_LOD_FAR));}
    public static double animationDistance(){return value(ANIMATION_DISTANCE,DEFAULT_ANIMATION_DISTANCE);}
    public static double takeoverDistance(){return value(TAKEOVER_DISTANCE,DEFAULT_TAKEOVER_DISTANCE);}
    private static double value(ForgeConfigSpec.DoubleValue setting,double fallback){try{return setting.get();}catch(IllegalStateException ignored){return fallback;}}
}
