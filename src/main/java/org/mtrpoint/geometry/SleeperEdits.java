package org.mtrpoint.geometry;

import java.util.*;

/** Persistent per-bearer overrides shared by turnout, diamond and scissors generators. */
public final class SleeperEdits {
    private SleeperEdits(){}

    public static Track path(List<Track> tracks,int requested){return tracks.get(Math.max(0,Math.min(tracks.size()-1,requested)));}
    public static boolean split(PointSettings settings,int index){return settings.sleeper(index,PointSettings.SLEEPER_SPLIT);}
    public static boolean full(PointSettings settings,int index){return settings.sleeper(index,PointSettings.SLEEPER_FULL);}

    public static void finish(Mesh mesh,Junction junction,List<Track> tracks,PointSettings settings,Profile profile){
        for(var entry:settings.addedSleepers().entrySet()){
            int index=entry.getKey();var added=entry.getValue();Track road=path(tracks,added.path());
            double distance=Math.max(0,Math.min(road.length,added.distance()+settings.sleeperShifts().getOrDefault(index,0D)));
            V3 center=road.at(distance),normal=road.tangent(distance).lateral();
            double t=distance/Math.max(.001,road.length),angle=Math.toRadians(settings.sleeperAngle()+(settings.sleeperEndAngle()-settings.sleeperAngle())*t);
            normal=rotate(normal,angle);double half=profile.centerOffset()+settings.sleeperOverhang();
            if(profile.detail()!=null){
                if(!profile.detail().siding())profile.detail().bearer(mesh,center,normal,-half,half,settings,profile,index);
                for(int sign:new int[]{-1,1})profile.detail().fitting(mesh,center.add(normal.mul(sign*profile.centerOffset())),normal,settings,profile,index);
            }else{
                double top=profile.top()-profile.railHeight()+settings.verticalOffset();
                mesh.beam(center.sub(normal.mul(half)),center.add(normal.mul(half)),settings.sleeperWidth(),settings.sleeperWidth(),top-settings.sleeperHeight(),top,profile.sleeper(),"sleeper",index);
            }
        }
        mesh.quads.removeIf(q->q.index()>=0&&settings.sleeper(q.index(),PointSettings.SLEEPER_DELETED)&&(q.part().equals("sleeper")||q.part().equals("fastener")));
    }

    public static void finish(Mesh mesh,Junction junction,PointSettings settings,Profile profile){finish(mesh,junction,junction.tracks(),settings,profile);}
    public static V3 rotate(V3 normal,double angle){return new V3(normal.x()*Math.cos(angle)-normal.z()*Math.sin(angle),0,normal.x()*Math.sin(angle)+normal.z()*Math.cos(angle));}
}
