package org.mtrpoint.geometry;

import java.util.*;

/** Shared support rows keep stock-rail fittings before nearby inner-route candidates. */
public final class FittingSeats {
    static record Seat(V3 center,V3 normal,boolean stock,V3 outside) {}
    private record Footprint(V3 center,V3 across,V3 along,double halfAcross,double halfAlong){
        boolean overlaps(Footprint other){
            V3 delta=other.center.sub(center); if(Math.abs(delta.y())>.05)return false;
            for(V3 axis:List.of(across,along,other.across,other.along)){
                double radius=halfAcross*Math.abs(across.dot(axis))+halfAlong*Math.abs(along.dot(axis))+other.halfAcross*Math.abs(other.across.dot(axis))+other.halfAlong*Math.abs(other.along.dot(axis));
                if(Math.abs(delta.dot(axis))>=radius-1e-6)return false;
            }
            return true;
        }
    }
    private final List<Seat> candidates=new ArrayList<>();
    private final List<Footprint> guardSeats=new ArrayList<>();
    private final List<V3[]> guardPairs=new ArrayList<>();
    public void add(V3 center,V3 normal,boolean stock){add(center,normal,stock,null);}
    public void add(V3 center,V3 normal,boolean stock,V3 outside){candidates.add(new Seat(center,normal,stock,outside));}
    public void excludeNear(V3 center,double radius){candidates.removeIf(s->s.center.distance(center)<radius);}
    boolean guardPair(V3 running,V3 guard){
        V3 across=guard.sub(running).lateral().lateral().mul(-1),along=new V3(across.z(),0,-across.x());
        guardSeats.add(new Footprint(running.lerp(guard,.5).sub(across.mul(.005)),across,along,running.distance(guard)/2+.205,.11));
        excludeNear(running,.2);excludeNear(guard,.2);
        if(guardPairs.stream().anyMatch(pair->pair[0].distance(running)<.08&&pair[1].distance(guard)<.08))return false;
        guardPairs.add(new V3[]{running,guard});return true;
    }
    private static Footprint footprint(Seat seat,Profile profile){
        var d=profile.detail();var vs=d.fittings().stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).toList();
        double lo=vs.stream().mapToDouble(V3::x).min().orElseThrow()-d.railCenter(),hi=vs.stream().mapToDouble(V3::x).max().orElseThrow()-d.railCenter();
        double front=vs.stream().mapToDouble(V3::z).min().orElseThrow(),back=vs.stream().mapToDouble(V3::z).max().orElseThrow();
        V3 across=seat.normal.unit(),along=new V3(across.z(),0,-across.x());
        return new Footprint(seat.center.add(across.mul((lo+hi)/2)).add(along.mul((front+back)/2)),across,along,(hi-lo)/2,(back-front)/2);
    }
    private List<Seat> available(Profile profile){
        var out=new ArrayList<Seat>();candidates.sort(Comparator.comparing((Seat seat)->!seat.stock));
        for(Seat seat:candidates){if(guardSeats.stream().anyMatch(g->g.overlaps(footprint(seat,profile))))continue;
            if(out.stream().anyMatch(old->old.stock==seat.stock&&old.center.distance(seat.center)<1e-4&&old.normal.dot(seat.normal)>.999))continue;out.add(seat);}
        return out;
    }
    public void emit(Mesh mesh,Profile profile,PointSettings settings,int index){
        if(profile.detail()==null)return;var placed=new ArrayList<V3>();
        for(Seat seat:available(profile))if(placed.stream().noneMatch(p->p.distance(seat.center)<.18)){
            placed.add(seat.center);profile.detail().seatBearer(mesh,seat.center,seat.normal,0,0,settings,profile,index);profile.detail().fitting(mesh,seat.center,seat.normal,settings,profile,index);
        }
    }
    public void emitTurnout(Mesh mesh,Profile profile,PointSettings settings,int index,String zone){
        if(profile.detail()==null)return;if(zone.equals("normal")||!TurnoutFittings.applicable(profile)){emit(mesh,profile,settings,index);return;}
        var seats=available(profile);var used=new HashSet<Seat>();
        var footprints=new HashMap<Seat,Footprint>();for(Seat seat:seats)footprints.put(seat,footprint(seat,profile));
        for(Seat seat:seats){if(used.contains(seat))continue;var group=new ArrayList<Seat>();group.add(seat);
            for(int i=0;i<group.size();i++)for(Seat other:seats)if(!used.contains(other)&&!group.contains(other)&&footprints.get(group.get(i)).overlaps(footprints.get(other)))group.add(other);
            used.addAll(group);
            if(zone.equals("blade")&&group.size()>1){TurnoutFittings.shared(mesh,group,profile,settings,index,zone);for(Seat support:group)if(support.stock)TurnoutFittings.stockClamp(mesh,support,profile,settings,index);}
            else if(group.size()>1)TurnoutFittings.shared(mesh,group,profile,settings,index,zone);
            else {profile.detail().seatBearer(mesh,seat.center,seat.normal,0,0,settings,profile,index);profile.detail().fitting(mesh,seat.center,seat.normal,settings,profile,index);}
        }
    }
}
