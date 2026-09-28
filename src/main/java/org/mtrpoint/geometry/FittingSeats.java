package org.mtrpoint.geometry;

import java.util.*;

/** Shared support rows keep stock-rail fittings before nearby inner-route candidates. */
public final class FittingSeats {
    static record Seat(V3 center,V3 normal,boolean stock,V3 outside) {}
    private final List<Seat> candidates=new ArrayList<>();
    public void add(V3 center,V3 normal,boolean stock){add(center,normal,stock,null);}
    public void add(V3 center,V3 normal,boolean stock,V3 outside){candidates.add(new Seat(center,normal,stock,outside));}
    public void excludeNear(V3 center,double radius){candidates.removeIf(s->s.center.distance(center)<radius);}
    public void emit(Mesh mesh,Profile profile,PointSettings settings,int index){
        if(profile.detail()==null)return;
        var placed=new ArrayList<V3>();
        // Stable sorting retains the existing order among equally important seats.
        candidates.sort(Comparator.comparing((Seat seat)->!seat.stock));
        for(Seat seat:candidates)if(placed.stream().noneMatch(p->p.distance(seat.center)<.18)){
            placed.add(seat.center);
            profile.detail().fitting(mesh,seat.center,seat.normal,settings,profile,index);
        }
    }
    public void emitTurnout(Mesh mesh,Profile profile,PointSettings settings,int index,String zone){
        if(profile.detail()==null)return;
        if(zone.equals("normal")||!TurnoutFittings.applicable(profile)){emit(mesh,profile,settings,index);return;}
        candidates.sort(Comparator.comparing((Seat seat)->!seat.stock));var used=new HashSet<Seat>();
        for(Seat seat:candidates){if(used.contains(seat))continue;var group=new ArrayList<Seat>();
            for(Seat other:candidates)if(!used.contains(other)&&other.center.distance(seat.center)<.48)group.add(other);
            used.addAll(group);
            if(zone.equals("blade")){TurnoutFittings.shared(mesh,group,profile,settings,index,zone);for(Seat support:group)if(support.stock)TurnoutFittings.stockClamp(mesh,support,profile,settings,index);}
            else if(group.size()>1)TurnoutFittings.shared(mesh,group,profile,settings,index,zone);
            else profile.detail().fitting(mesh,seat.center,seat.normal,settings,profile,index);
        }
    }
}
