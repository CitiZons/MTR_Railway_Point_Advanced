package org.mtrpoint.client;

import org.mtr.core.data.Rail;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Cached endpoint adjustment for styles that explicitly separate fixed and support groups. */
public final class SleeperSeams {
    private record Key(V3 a,V3 b) {}
    private record PlanKey(String railId,String style) {}
    private record Plan(Rail rail,Track sampled,Map<Key,V3> moves) {}
    private static final Map<PlanKey,Plan> PLANS=new HashMap<>();
    private static final Map<PlanKey,Plan> FRAME_PLANS=new HashMap<>();
    public static void begin(){FRAME_PLANS.clear();ContinuousGuards.begin();}
    public static void clear(){PLANS.clear();FRAME_PLANS.clear();ContinuousGuards.clear();}
    public static void retain(Set<String> ids){PLANS.entrySet().removeIf(e->!ids.contains(e.getValue().rail.getHexId()));}
    public static boolean render(Rail rail,RailResource resource,boolean flip,V3 a,V3 b){
        if(rail==null)return false;ProfileModel model=Profiles.model(resource.getId());if(model==null||!model.alignSleepers()&&model.continuousGuard()==null)return false;
        PlanKey key=new PlanKey(rail.getHexId(),resource.getId());Plan plan=FRAME_PLANS.get(key);
        if(plan==null){Track sampled=RailSampler.sample(rail);if(sampled==null)return false;plan=PLANS.get(key);
            if(plan==null||plan.rail!=rail||plan.sampled!=sampled){plan=build(rail,resource,sampled);PLANS.put(key,plan);}FRAME_PLANS.put(key,plan);}
        Key cell=new Key(a,b);boolean planned=plan.moves.containsKey(cell);if(!planned&&model.continuousGuard()==null)return false;
        model=RailLod.model(resource.getId(),a.lerp(b,.5));
        var guard=model.continuousGuard();Track track=plan.sampled;ContinuousGuards.Ends ends=guard==null?null:ContinuousGuards.exposed(rail,resource.getId());
        PointRenderer.cell(rail,resource,flip,a,b,model.fixed(),V3.ZERO,true);
        if(guard!=null){var body=ContinuousGuards.body(guard.rails(),model,track,RailSampler.sweep(rail,resource,a,b),ends,flip);PointRenderer.cell(rail,resource,flip,a,b,body,V3.ZERO,true);}
        V3 move=plan.moves.get(cell);if(move!=null){
            // The fixed sweep may trim an arc overrun. The layout target was measured from
            // the original callback centre, so preserve that target after trimming.
            var sweep=RailSampler.sweep(rail,resource,a,b);
            V3 layoutMove=move;move=layoutMove.add(a.lerp(b,.5).sub(sweep.a().lerp(sweep.b(),.5)));
            PointRenderer.cell(rail,resource,flip,a,b,model.supports(),move,false);
            if(guard!=null)ContinuousGuards.supports(rail,resource,flip,a,b,model,track,ends,layoutMove);
        }
        else if(guard!=null&&!planned){PointRenderer.cell(rail,resource,flip,a,b,model.supports(),V3.ZERO,false);ContinuousGuards.supports(rail,resource,flip,a,b,model,track,ends,V3.ZERO);}
        if(guard!=null)ContinuousGuards.endpoint(rail,resource,flip,a,b,model,track,ends);
        return true;
    }
    private static Plan build(Rail rail,RailResource resource,Track sampled){
        var reference=RailSampler.cells(rail,.5);var cells=RailSampler.cells(rail,resource.getRepeatInterval());
        if(reference.isEmpty()||cells.isEmpty())return new Plan(rail,sampled,Map.of());
        var points=new ArrayList<V3>();points.add(reference.get(0).a());for(var cell:reference)points.add(cell.b());
        Track line=new Track("supports","a","b",points);
        var ordered=new ArrayList<>(cells);ordered.sort(Comparator.comparingDouble(c->line.nearest(c.a().lerp(c.b(),.5))));
        var centers=ordered.stream().map(c->line.nearest(c.a().lerp(c.b(),.5))).toList();
        double[] fitted=SleeperLayout.fit(centers,line.length,resource.getRepeatInterval());var moves=new HashMap<Key,V3>();
        for(int i=0;i<ordered.size();i++){var cell=ordered.get(i);Key key=new Key(cell.a(),cell.b());
            if(Double.isNaN(fitted[i]))moves.put(key,null);
            else if(Math.abs(fitted[i]-centers.get(i))>1e-5)moves.put(key,line.at(fitted[i]).sub(cell.a().lerp(cell.b(),.5)));}
        return new Plan(rail,sampled,Collections.unmodifiableMap(moves));
    }
}
