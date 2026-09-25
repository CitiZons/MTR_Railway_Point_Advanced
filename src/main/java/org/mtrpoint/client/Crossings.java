package org.mtrpoint.client;

import org.mtrpoint.geometry.Junction;
import org.mtrpoint.geometry.PointMesh;
import org.mtrpoint.geometry.PointSettings;
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

    /** Whether two rails cross in plan at one running height. Sampled, because either rail may be
     *  curved: the test is the same one the detector uses, so tangents and overpasses stay out. */
    public static boolean meets(Track a,Track b){
        double step=Math.max(.25,Math.min(1,a.length/64));
        V3 centerA=a.at(a.length/2),centerB=b.at(b.length/2);
        if(centerA.sub(centerB).length()>a.length/2+b.length/2+1)return false;
        for(double s=0;s<=a.length;s+=step){
            V3 p=a.at(s),tangent=a.tangent(s);
            double t=b.nearest(p);
            if(Double.isNaN(t))continue;
            V3 q=b.at(t);
            if(Math.abs(p.y()-q.y())>HEIGHT)continue;
            // The nearest point of the other rail has to be the crossing itself, not a parallel
            // neighbour standing beside it.
            if(p.sub(q).length()>.08)continue;
            if(Math.abs(V3.crossXZ(tangent,b.tangent(t)))<SINE)continue;
            return true;
        }
        return false;
    }

    /** Crossing points shared by several pairwise junctions, worked out from their centres. A
     *  single junction is a region of its own, which keeps plain diamonds unchanged. */
    public static List<Region> regions(List<Junction> junctions,PointSettings settings){
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
                double a=PointMesh.extent(o,settings),b=PointMesh.extent(owner,settings);
                if(a>b||a==b&&o.id().compareTo(owner.id())<0)owner=o;
            }
            Map<String,Track> roads=new LinkedHashMap<>();
            for(Junction o:group)for(Track t:o.tracks())roads.putIfAbsent(t.id,t);
            double limit=0;
            for(Junction o:group)limit=Math.max(limit,PointMesh.extent(o,settings));
            List<double[]> windows=new ArrayList<>();
            for(Track t:roads.values()){
                double c=t.nearest(owner.center());
                windows.add(RailSampler.lastCellRange(t,settings,c-limit,c+limit));
            }
            out.add(new Region(owner,List.copyOf(group),List.copyOf(roads.values()),new PointMesh.DiamondBoundary(windows.toArray(double[][]::new))));
        }
        out.sort(Comparator.comparing((Region r)->r.owner().id()));
        return out;
    }
}
