package org.mtrpoint.geometry;

import java.util.*;

/** Only appearance parameters; none of these values is used by pathfinding. */
public record PointSettings(double lengthScale,double bladeLength,double throwDistance,double flangeway,
                            double sleeperSpacing,double sleeperWidth,double sleeperHeight,double sleeperOverhang,
                            double gaugeOverride,double railHeightOverride,double headWidthOverride,
                            double verticalOffset,double frogShift,double animationSeconds,
                            boolean movableFrog,boolean enabled,Map<Integer,Double> sleeperShifts,String profileStyle,
                            double sleeperAngle,double sleeperEndAngle,int sleeperMode,
                            double guardShift,double guardLengthDelta,double guardGapDelta,double wingShift,
                            double wingLengthDelta,double wingGapDelta,double noseLengthDelta,
                            int sleeperPath,Map<Integer,Integer> sleeperOverrides,Map<Integer,ManualSleeper> addedSleepers,
                            Map<Integer,GuardEdit> guardEdits) {
    public static final int SLEEPER_DELETED=1,SLEEPER_SPLIT=2,SLEEPER_FULL=4;
    public record ManualSleeper(int path,double distance) {}
    /** Absolute stations make manual guard edits independent of later global guard defaults. */
    public record GuardEdit(double start,double end,boolean flareStart,boolean flareEnd,String mergeGroup) {}
    public static final PointSettings DEFAULT=new PointSettings(.9,0,.12,.055,.6,.24,.12,.38,0,0,0,0,0,1,false,true,Map.of(),"",0,0,4,0,0,0,0,0,0,0,0,Map.of(),Map.of(),Map.of());
    public PointSettings {
        check(lengthScale,.5,2); check(bladeLength,0,30); check(throwDistance,.02,.3); check(flangeway,.025,.15);
        check(sleeperSpacing,.3,1.2); check(sleeperWidth,.12,.5); check(sleeperHeight,.04,.3); check(sleeperOverhang,.1,1);
        check(gaugeOverride,0,4); check(railHeightOverride,0,1); check(headWidthOverride,0,.4);
        check(guardShift,-3,3);check(guardLengthDelta,-1,5);check(guardGapDelta,-.02,.2);check(wingShift,-1,1);check(wingLengthDelta,-.5,3);check(wingGapDelta,-.02,.2);check(noseLengthDelta,-.5,3);
        check(sleeperAngle,-45,45);check(sleeperEndAngle,-45,45);if(sleeperMode<0||sleeperMode>4)throw new IllegalArgumentException("Sleeper mode");
        check(verticalOffset,-.5,.5); check(frogShift,-1,1); check(animationSeconds,.1,3);
        if(sleeperPath<0||sleeperPath>7)throw new IllegalArgumentException("Sleeper path");
        sleeperShifts=sleeperShifts==null?Map.of():Map.copyOf(sleeperShifts);sleeperOverrides=sleeperOverrides==null?Map.of():Map.copyOf(sleeperOverrides);addedSleepers=addedSleepers==null?Map.of():Map.copyOf(addedSleepers);guardEdits=guardEdits==null?Map.of():Map.copyOf(guardEdits);
        if(sleeperShifts.size()>512||sleeperOverrides.size()>512||addedSleepers.size()>256||guardEdits.size()>128)throw new IllegalArgumentException("Manual edits exceed limit");
        sleeperShifts.forEach((i,v)->{checkIndex(i);check(v,-.25,.25);});
        sleeperOverrides.forEach((i,v)->{checkIndex(i);if(v<0||(v&~(SLEEPER_DELETED|SLEEPER_SPLIT|SLEEPER_FULL))!=0)throw new IllegalArgumentException("Sleeper override");});
        addedSleepers.forEach((i,v)->{if(i<256||i>511||v==null||v.path<0||v.path>7)throw new IllegalArgumentException("Manual sleeper");check(v.distance,0,1024);});
        guardEdits.forEach((i,v)->{if(i<0||i>127||v==null)throw new IllegalArgumentException("Guard edit");check(v.start,0,4096);check(v.end,0,4096);if(v.end<=v.start||v.mergeGroup==null||v.mergeGroup.length()>80)throw new IllegalArgumentException("Guard edit");});
        profileStyle=profileStyle==null?"":profileStyle;
        if(profileStyle.length()>160)throw new IllegalArgumentException("Style id too long");
    }
    private static void check(double v,double lo,double hi) { if(!Double.isFinite(v)||v<lo||v>hi)throw new IllegalArgumentException("Parameter outside "+lo+".."+hi); }
    private static void checkIndex(int index){if(index<0||index>511)throw new IllegalArgumentException("Sleeper index");}
    public double[] values(){return new double[]{lengthScale,bladeLength,throwDistance,flangeway,sleeperSpacing,sleeperWidth,sleeperHeight,sleeperOverhang,gaugeOverride,railHeightOverride,headWidthOverride,verticalOffset,frogShift,animationSeconds,sleeperAngle,sleeperEndAngle,sleeperMode,guardShift,guardLengthDelta,guardGapDelta,wingShift,wingLengthDelta,wingGapDelta,noseLengthDelta};}
    public PointSettings with(int index,double value) { double[] v=values();v[index]=value;return of(v,movableFrog,enabled,sleeperShifts,profileStyle,sleeperPath,sleeperOverrides,addedSleepers,guardEdits); }
    public PointSettings flags(boolean frog,boolean on) { return of(values(),frog,on,sleeperShifts,profileStyle,sleeperPath,sleeperOverrides,addedSleepers,guardEdits); }
    public PointSettings style(String id) { return of(values(),movableFrog,enabled,sleeperShifts,id,sleeperPath,sleeperOverrides,addedSleepers,guardEdits); }
    public PointSettings sleeper(int index,double shift){var m=new HashMap<>(sleeperShifts);m.put(index,shift);return of(values(),movableFrog,enabled,m,profileStyle,sleeperPath,sleeperOverrides,addedSleepers,guardEdits);}
    public PointSettings sleeperPath(int path){return of(values(),movableFrog,enabled,sleeperShifts,profileStyle,path,sleeperOverrides,addedSleepers,guardEdits);}
    public boolean sleeper(int index,int flag){return (sleeperOverrides.getOrDefault(index,0)&flag)!=0;}
    public PointSettings toggleSleeper(int index,int flag){checkIndex(index);var overrides=new HashMap<>(sleeperOverrides);int value=overrides.getOrDefault(index,0)^flag;if(value==0)overrides.remove(index);else overrides.put(index,value);return of(values(),movableFrog,enabled,sleeperShifts,profileStyle,sleeperPath,overrides,addedSleepers,guardEdits);}
    public PointSettings addSleeper(int path,double distance){var added=new HashMap<>(addedSleepers);int index=256;while(added.containsKey(index)&&index<=511)index++;if(index>511)throw new IllegalArgumentException("Too many manual sleepers");added.put(index,new ManualSleeper(path,distance));return of(values(),movableFrog,enabled,sleeperShifts,profileStyle,sleeperPath,sleeperOverrides,added,guardEdits);}
    public PointSettings removeSleeper(int index){if(index<256)return toggleSleeper(index,SLEEPER_DELETED);var added=new HashMap<>(addedSleepers);added.remove(index);var shifts=new HashMap<>(sleeperShifts);shifts.remove(index);var overrides=new HashMap<>(sleeperOverrides);overrides.remove(index);return of(values(),movableFrog,enabled,shifts,profileStyle,sleeperPath,overrides,added,guardEdits);}
    public PointSettings resetSleepers(){double[] v=values(),defaults=DEFAULT.values();for(int i:new int[]{4,5,6,7,14,15,16})v[i]=defaults[i];return of(v,movableFrog,enabled,Map.of(),profileStyle,0,Map.of(),Map.of(),guardEdits);}
    public PointSettings guard(int index,GuardEdit edit){var guards=new HashMap<>(guardEdits);if(edit==null)guards.remove(index);else guards.put(index,edit);return of(values(),movableFrog,enabled,sleeperShifts,profileStyle,sleeperPath,sleeperOverrides,addedSleepers,guards);}
    private static double extra(double[] values,int i){return values.length>i?values[i]:0;}
    public static PointSettings of(double[] v,boolean f,boolean e,Map<Integer,Double> s,String p){return of(v,f,e,s,p,0,Map.of(),Map.of(),Map.of());}
    public static PointSettings of(double[] v,boolean f,boolean e,Map<Integer,Double> s,String p,int path,Map<Integer,Integer> overrides,Map<Integer,ManualSleeper> added){return of(v,f,e,s,p,path,overrides,added,Map.of());}
    public static PointSettings of(double[] v,boolean f,boolean e,Map<Integer,Double> s,String p,int path,Map<Integer,Integer> overrides,Map<Integer,ManualSleeper> added,Map<Integer,GuardEdit> guards){return new PointSettings(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],f,e,s,p,v.length>14?v[14]:0,v.length>15?v[15]:0,v.length>16?(int)v[16]:0,extra(v,17),extra(v,18),extra(v,19),extra(v,20),extra(v,21),extra(v,22),extra(v,23),path,overrides,added,guards);}
}
