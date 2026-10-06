package org.mtrpoint.client;

import org.mtr.core.data.Rail;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Endpoint selection and cached cell submission for opt-in full-length guard rail styles. */
final class ContinuousGuards {
    record Ends(boolean start,boolean end) {}
    private static final Map<List<Mesh.Quad>,Map<String,List<Mesh.Quad>>> CLIPS=Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<String,Ends> FRAME_ENDS=new HashMap<>();
    private static final Set<String> FRAME_TERMINALS=new HashSet<>();
    private static final int MAX_CLIP_SOURCES=512;
    static void begin(){FRAME_ENDS.clear();FRAME_TERMINALS.clear();}
    static void clear(){FRAME_ENDS.clear();FRAME_TERMINALS.clear();CLIPS.clear();}

    static Ends exposed(Rail rail,String style){
        Track track=RailSampler.sample(rail);if(track==null)return new Ends(false,false);
        String key=rail.getHexId()+"/"+Profiles.canonical(style);return FRAME_ENDS.computeIfAbsent(key,k->exposed(track,style,RailSampler.styledTracks(style)));
    }

    static Ends exposed(Track track,String style,Collection<Track> sameStyle){
        boolean start=true,end=true;
        for(Track other:sameStyle)if(!other.id.equals(track.id)){
            if(other.startNode.equals(track.startNode)||other.endNode.equals(track.startNode))start=false;
            if(other.startNode.equals(track.endNode)||other.endNode.equals(track.endNode))end=false;
        }
        return new Ends(start,end);
    }

    static double reach(Track track,double length,Ends ends,boolean start){if(start&&!ends.start||!start&&!ends.end)return 0;return Math.min(length,track.length/(ends.start&&ends.end?2:1));}
    static List<Mesh.Quad> clip(List<Mesh.Quad> faces,double from,double to){
        String key=Double.doubleToLongBits(from)+":"+Double.doubleToLongBits(to);
        var cached=CLIPS.get(faces);
        if(cached==null){
            synchronized(CLIPS){
                while(CLIPS.size()>=MAX_CLIP_SOURCES&&!CLIPS.containsKey(faces)){var it=CLIPS.keySet().iterator();if(!it.hasNext())break;it.next();it.remove();}
                cached=CLIPS.computeIfAbsent(faces,k->new HashMap<>());
            }
        }var found=cached.get(key);if(found!=null)return found;
        Mesh after=new Mesh();for(var q:faces)Mesh.clip(after,q,new V3(0,0,from),new V3(0,0,-1));
        Mesh before=new Mesh();for(var q:after.quads)Mesh.clip(before,q,new V3(0,0,to),new V3(0,0,1));found=List.copyOf(before.quads);cached.put(key,found);return found;
    }
    static List<Mesh.Quad> body(List<Mesh.Quad> faces,ProfileModel model,Track track,RailSweep sweep,Ends ends,boolean flip){
        double sa=track.nearest(sweep.a()),sb=track.nearest(sweep.b()),start=reach(track,model.continuousGuard().length(),ends,true),end=track.length-reach(track,model.continuousGuard().length(),ends,false);
        double cellLo=Math.min(sa,sb),cellHi=Math.max(sa,sb);if(cellLo>=start&&cellHi<=end)return faces;if(cellHi<=start||cellLo>=end)return List.of();
        double keep0=Math.max(cellLo,start),keep1=Math.min(cellHi,end),ta=(keep0-sa)/(sb-sa),tb=(keep1-sa)/(sb-sa);
        double z0=model.detail().zMin(),z1=model.detail().zMax(),range=z1-z0,from=flip?z1-ta*range:z0+ta*range,to=flip?z1-tb*range:z0+tb*range;
        return clip(faces,Math.min(from,to),Math.max(from,to));
    }
    static double endpointDistance(Track track,V3 center,Ends ends){double s=track.nearest(center),d=Double.POSITIVE_INFINITY;if(ends.start)d=s;if(ends.end)d=Math.min(d,track.length-s);return d;}
    static boolean claimTerminal(String key,double distance,double reach,double interval){
        return distance<=reach+interval/2+.001&&FRAME_TERMINALS.add(key);
    }
    static void supports(Rail rail,RailResource resource,boolean flip,V3 a,V3 b,ProfileModel model,Track track,Ends ends,V3 move){
        var guard=model.continuousGuard();double center=track.nearest(a.lerp(b,.5).add(move)),startReach=reach(track,guard.length(),ends,true),endReach=reach(track,guard.length(),ends,false),d=ends.start?center:Double.POSITIVE_INFINITY;if(ends.end)d=Math.min(d,track.length-center);
        double effective=d==center?startReach:endReach,scale=effective/guard.length();if(d<guard.noseLength()*scale)return;double inset=d<effective?guard.supportInset()*(1-d/effective):0;
        RailSweep sweep=RailSampler.sweep(rail,resource,a,b);move=move.add(a.lerp(b,.5).sub(sweep.a().lerp(sweep.b(),.5)));
        RailCellCache.submitGuard(guard.supports(),sweep,flip,resource.getModelYOffset(),model.detail().zMin(),model.detail().zMax(),move,inset,model.detail().railCenter(),guard.guardCenter(),guard.sharedSupports());
    }

    static void endpoint(Rail rail,RailResource resource,boolean flip,V3 a,V3 b,ProfileModel model,Track track,Ends ends){
        var guard=model.continuousGuard();if(guard==null)return;double station=track.nearest(a.lerp(b,.5));String key=rail.getHexId()+"/"+resource.getId();double interval=resource.getRepeatInterval();
        double startReach=reach(track,guard.length(),ends,true);
        if(ends.start&&claimTerminal(key+"/start",station,startReach,interval)){for(var slice:RailSampler.endpointSlices(resource,track,true,startReach)){
            double from=guard.length()*slice.from()/startReach,to=guard.length()*slice.to()/startReach;RailCellCache.submit(clip(guard.endpoint(),from,to),slice.sweep(),false,resource.getModelYOffset(),from,to,V3.ZERO,true);}}
        double endReach=reach(track,guard.length(),ends,false);
        if(ends.end&&claimTerminal(key+"/end",track.length-station,endReach,interval)){for(var slice:RailSampler.endpointSlices(resource,track,false,endReach)){
            double from=guard.length()*slice.from()/endReach,to=guard.length()*slice.to()/endReach;RailCellCache.submit(clip(guard.endpoint(),from,to),slice.sweep(),false,resource.getModelYOffset(),from,to,V3.ZERO,true);}}
    }
}
