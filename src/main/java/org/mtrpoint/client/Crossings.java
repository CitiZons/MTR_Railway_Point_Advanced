package org.mtrpoint.client;

import org.mtrpoint.geometry.Junction;
import org.mtrpoint.geometry.PointMesh;
import org.mtrpoint.geometry.PointSettings;
import org.mtrpoint.geometry.Profile;
import org.mtrpoint.geometry.Track;
import org.mtrpoint.geometry.V3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planar crossings worked out from the rails themselves, without any turnout or scissors layout:
 * which physical rails really meet at one running height, whose flange channels have to cut the
 * other rails' steel, and which single view draws a meeting point instead of every pairwise
 * junction drawing the same rails again.
 */
public final class Crossings {
    /**
     * Junctions that meet at one point. One of them draws the meeting; the others only keep MTR's
     * own rails hidden there, so nothing is drawn twice and no native cell is left showing.
     */
    public record Region(Junction owner,List<Junction> junctions,List<Track> roads,PointMesh.DiamondBoundary windows){}

    /** Junctions closer than this share one crossing point. */
    private static final double SAME_POINT=.6;
    /** Rails below this angle are neighbours, not crossings. */
    private static final double SINE=.025;
    private static final double HEIGHT=.08;

    private Crossings(){}

    /** Every rail that really crosses one of these roads at the same running height. Its flange
     *  channels have to cut that junction's steel, however the crossing rail is pieced. */
    public static List<Track> cutters(List<Track> roads,List<Track> all){
        Map<String,Track> out=new LinkedHashMap<>();
        for(Track other:all){
            if(roads.stream().anyMatch(r->r.id.equals(other.id)))continue;
            boolean meets=false;
            for(Track road:roads)if(meets(road,other)){meets=true;break;}
            if(meets)out.putIfAbsent(other.id,other);
        }
        return List.copyOf(out.values());
    }
    /** Track centre lines can miss each other while a running rail crosses the neighbouring
     * route's flange channel, notably beside the last frog of an asymmetric three-way turnout. */
    public static List<Track> cutters(List<Track> roads,List<Track> all,Profile p,PointSettings settings){
        var result=new LinkedHashMap<String,Track>();
        for(var track:cutters(roads,all))result.put(track.id,track);
        var running=new ArrayList<Track>();
        for(var road:roads)for(int sign:new int[]{-1,1})running.add(offset(road,sign*p.centerOffset()));
        double gap=Math.max(.02,settings.flangeway()+settings.wingGapDelta());
        double channel=p.centerOffset()-p.headWidth()/2-gap/2;
        for(var other:all){
            if(result.containsKey(other.id)||roads.stream().anyMatch(r->r.id.equals(other.id)))continue;
            for(int sign:new int[]{-1,1}){
                Track flange=offset(other,sign*channel);
                if(running.stream().anyMatch(rail->meets(rail,flange))){result.put(other.id,other);break;}
            }
        }
        return List.copyOf(result.values());
    }
    private static Track offset(Track track,double offset){
        var points=new ArrayList<V3>();
        for(int i=0;i<track.points.size();i++)points.add(track.points.get(i).add(track.tangent(track.distance[i]).lateral().mul(offset)));
        return new Track(track.id,track.startNode,track.endNode,points);
    }

    /** Test segment intersections, not distances from a sparse set of sample stations. */
    public static boolean meets(Track a,Track b){
        V3 centerA=a.at(a.length/2),centerB=b.at(b.length/2);
        if(centerA.sub(centerB).length()>a.length/2+b.length/2+1)return false;
        for(int i=1;i<a.points.size();i++)for(int k=1;k<b.points.size();k++){
            V3 u=a.points.get(i-1),v=a.points.get(i),x=b.points.get(k-1),y=b.points.get(k);
            if(Math.max(u.x(),v.x())<Math.min(x.x(),y.x())||Math.max(x.x(),y.x())<Math.min(u.x(),v.x())
                ||Math.max(u.z(),v.z())<Math.min(x.z(),y.z())||Math.max(x.z(),y.z())<Math.min(u.z(),v.z()))continue;
            if(Math.abs(V3.crossXZ(v.sub(u).unit(),y.sub(x).unit()))<SINE)continue;
            double[] at=org.mtrpoint.geometry.Detector.intersection(u,v,x,y);
            if(at!=null&&Math.abs(u.lerp(v,at[0]).y()-x.lerp(y,at[1]).y())<=HEIGHT)return true;
        }
        return false;
    }

    /** Crossing points shared by several pairwise junctions, worked out from their centres. A
     *  single junction is a region of its own, which keeps plain diamonds unchanged. */
    public static List<Region> regions(List<Junction> junctions,PointSettings settings){
        return regions(junctions,j->settings);
    }
    public static List<Region> regions(List<Junction> junctions,java.util.function.Function<Junction,PointSettings> settings){
        List<Junction> diamonds=new ArrayList<>();
        for(Junction j:junctions)if(j.kind()==Junction.Kind.DIAMOND)diamonds.add(j);
        List<Region> out=new ArrayList<>();
        List<String> done=new ArrayList<>();
        for(Junction seed:diamonds){
            if(done.contains(seed.id()))continue;
            List<Junction> group=new ArrayList<>();group.add(seed);done.add(seed.id());
            for(Junction other:diamonds)if(!done.contains(other.id())&&other.center().distance(seed.center())<=SAME_POINT){group.add(other);done.add(other.id());}
            Junction owner=group.get(0);
            for(Junction o:group){
                double a=PointMesh.extent(o,settings.apply(o)),b=PointMesh.extent(owner,settings.apply(owner));
                if(a>b||a==b&&o.id().compareTo(owner.id())<0)owner=o;
            }
            Map<String,Track> roads=new LinkedHashMap<>();
            for(Junction o:group)for(Track t:o.tracks())roads.putIfAbsent(t.id,t);
            double limit=0;
            for(Junction o:group)limit=Math.max(limit,PointMesh.extent(o,settings.apply(o)));
            List<double[]> windows=new ArrayList<>();
            for(Track t:roads.values()){
                double c=t.nearest(owner.center());
                windows.add(RailSampler.lastCellRange(t,settings.apply(owner),c-limit,c+limit));
            }
            out.add(new Region(owner,List.copyOf(group),List.copyOf(roads.values()),new PointMesh.DiamondBoundary(windows.toArray(double[][]::new))));
        }
        out.sort(Comparator.comparing((Region r)->r.owner().id()));
        return out;
    }
}
