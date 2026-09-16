package org.mtrpoint.geometry;

import java.util.*;

/** Only appearance parameters; none of these values is used by pathfinding. */
public record PointSettings(double lengthScale,double bladeLength,double throwDistance,double flangeway,
                            double sleeperSpacing,double sleeperWidth,double sleeperHeight,double sleeperOverhang,
                            double gaugeOverride,double railHeightOverride,double headWidthOverride,
                            double verticalOffset,double frogShift,double animationSeconds,
                            boolean movableFrog,boolean enabled,Map<Integer,Double> sleeperShifts,String profileStyle) {
    public static final PointSettings DEFAULT=new PointSettings(1,0,.12,.055,.6,.24,.12,.38,0,0,0,0,0,.65,false,true,Map.of(),"");
    public PointSettings {
        check(lengthScale,.5,2); check(bladeLength,0,30); check(throwDistance,.02,.3); check(flangeway,.025,.15);
        check(sleeperSpacing,.3,1.2); check(sleeperWidth,.12,.5); check(sleeperHeight,.04,.3); check(sleeperOverhang,.1,1);
        check(gaugeOverride,0,4); check(railHeightOverride,0,1); check(headWidthOverride,0,.4);
        check(verticalOffset,-.5,.5); check(frogShift,-1,1); check(animationSeconds,.1,3);
        if(sleeperShifts==null||sleeperShifts.size()>256) throw new IllegalArgumentException("Sleeper edits exceed limit");
        sleeperShifts.forEach((i,v)->{if(i<0||i>255)throw new IllegalArgumentException("Sleeper index");check(v,-.25,.25);});
        sleeperShifts=Map.copyOf(sleeperShifts); profileStyle=profileStyle==null?"":profileStyle;
        if(profileStyle.length()>160)throw new IllegalArgumentException("Style id too long");
    }
    private static void check(double v,double lo,double hi) { if(!Double.isFinite(v)||v<lo||v>hi)throw new IllegalArgumentException("Parameter outside "+lo+".."+hi); }
    public double[] values(){return new double[]{lengthScale,bladeLength,throwDistance,flangeway,sleeperSpacing,sleeperWidth,sleeperHeight,sleeperOverhang,gaugeOverride,railHeightOverride,headWidthOverride,verticalOffset,frogShift,animationSeconds};}
    public PointSettings with(int index,double value) { double[] v=values(); v[index]=value; return of(v,movableFrog,enabled,sleeperShifts,profileStyle); }
    public PointSettings flags(boolean frog,boolean on) { return of(values(),frog,on,sleeperShifts,profileStyle); }
    public PointSettings style(String id) { return of(values(),movableFrog,enabled,sleeperShifts,id); }
    public PointSettings sleeper(int index,double shift){var m=new HashMap<>(sleeperShifts);m.put(index,shift);return of(values(),movableFrog,enabled,m,profileStyle);}
    public static PointSettings of(double[] v,boolean f,boolean e,Map<Integer,Double> s,String p){return new PointSettings(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],f,e,s,p);}
}
