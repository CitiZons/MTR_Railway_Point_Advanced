package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Slide beds are needed by intersecting fastening footprints, not whole blade windows. */
final class TurnoutOverlapRegression {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static List<Mesh.Quad> hardware(Mesh mesh){return mesh.quads.stream().filter(q->q.part().startsWith("fastener")).toList();}
    static void run(ModelDetail detail){
        Profile p=new Profile(1.435,.26428,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"overlap",true,detail);
        V3 n=new V3(1,0,0);
        for(V3 second:List.of(new V3(.47,0,0),new V3(.25,0,.24),new V3(.6,0,.07))){
            var seats=new FittingSeats();seats.add(V3.ZERO,n,true,n.mul(-1));seats.add(second,n,false);
            Mesh actual=new Mesh();seats.emitTurnout(actual,p,PointSettings.DEFAULT,7,"blade");
            Mesh ordinary=new Mesh();detail.fitting(ordinary,V3.ZERO,n,PointSettings.DEFAULT,p,7);detail.fitting(ordinary,second,n,PointSettings.DEFAULT,p,7);
            require(hardware(actual).equals(hardware(ordinary)),"Separate blade fittings lost their centre clips despite non-overlapping footprints: "+second);
        }
        for(boolean stock:new boolean[]{false,true}){
            var seats=new FittingSeats();seats.add(V3.ZERO,n,stock,stock?n.mul(-1):null);seats.add(V3.ZERO,n,stock,stock?n.mul(-1):null);
            Mesh actual=new Mesh();seats.emitTurnout(actual,p,PointSettings.DEFAULT,8,"blade");
            Mesh ordinary=new Mesh();detail.fitting(ordinary,V3.ZERO,n,PointSettings.DEFAULT,p,8);
            require(hardware(actual).equals(hardware(ordinary)),"One rail's duplicate tip seats produced a slide bed or overlapping clamps");
        }
        double top=p.top()-detail.railTop()+detail.rails().stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElseThrow();
        var seats=new FittingSeats();seats.add(V3.ZERO,n,true,n.mul(-1));seats.add(new V3(.30,0,0),n,false);
        Mesh shared=new Mesh();seats.emitTurnout(shared,p,PointSettings.DEFAULT,9,"blade");
        require(shared.quads.stream().anyMatch(q->q.part().startsWith("fastener")&&List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-top)<1e-8)&&q.a().distance(q.b())>.4),"Overlapping stock/blade fittings did not get a common slide plate");
        require(shared.quads.stream().filter(q->q.part().startsWith("fastener")).flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).noneMatch(v->v.y()>top+.04&&v.x()>.04&&v.x()<.26),"A centre clamp occupies the shared stock/blade slide bed");
        // At a guard mouth the normal fitting's centre can be outside a 0.2 m circle,
        // while part of that fitting still overlaps the common guard plate.
        Track road=new Track("mouth","a","b",List.of(V3.ZERO,new V3(0,0,10)));
        var run=new GuardRails.Run(road,4.7,8,p.centerOffset()-p.headWidth()-.055,true,false,p,PointSettings.DEFAULT);
        V3 row=road.at(5),normal=road.tangent(5).lateral(),running=row.add(normal.mul(p.centerOffset())),check=run.point(5);
        var guardSeats=new FittingSeats();guardSeats.add(running.add(0,0,.205),normal,true);
        Mesh actual=new Mesh();TurnoutFittings.guardsAtRow(actual,guardSeats,List.of(run),row,normal,p,PointSettings.DEFAULT,10);
        guardSeats.emitTurnout(actual,p,PointSettings.DEFAULT,10,"normal");
        Mesh expected=new Mesh();TurnoutFittings.guardPair(expected,running,check,normal,p,PointSettings.DEFAULT,10);
        require(hardware(actual).equals(hardware(expected)),"A normal clamp intrudes into the guard mouth's common plate");
        V3 toward=check.sub(running).unit();double distance=running.distance(check);
        require(actual.quads.stream().filter(q->q.part().startsWith("fastener")).flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).noneMatch(v->{double x=v.sub(running).dot(toward);return v.y()>top+.04&&x>.04&&x<distance-.005;}),"Guard/stock gap contains an unwanted spring clamp");
        System.out.println("TURNOUT_OVERLAP: PASS complete independent clips, real overlap slide beds, duplicate tip seats and clear guard mouths");
    }
}
