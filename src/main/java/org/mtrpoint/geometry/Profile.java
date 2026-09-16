package org.mtrpoint.geometry;

public record Profile(double gauge,double top,double headWidth,double footWidth,double railHeight,
                      Surface steel,Surface sleeper,String source,boolean automatic) {
    public record Surface(String texture,float u0,float v0,float u1,float v1,int color) {}
    public static final Surface STEEL=new Surface("mtr_railway_point_advanced:textures/steel.png",0,0,1,1,0xffffffff);
    public static final Surface TIMBER=new Surface("mtr_railway_point_advanced:textures/timber.png",0,0,1,1,0xffffffff);
    public static final Profile STANDARD=new Profile(1.435,.26428,.068,.14,.165,STEEL,TIMBER,"default",true);
    public Profile tune(PointSettings s) {
        return new Profile(s.gaugeOverride()>0?s.gaugeOverride():gauge,s.railHeightOverride()>0?s.railHeightOverride():top,
            s.headWidthOverride()>0?s.headWidthOverride():headWidth,footWidth,railHeight,steel,sleeper,source,automatic);
    }
    public double centerOffset(){return (gauge+headWidth)/2;}
}
