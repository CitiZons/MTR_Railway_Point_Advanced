package org.mtrpoint.geometry;

import java.util.*;

/** Shared support rows keep stock-rail fittings before nearby inner-route candidates. */
public final class FittingSeats {
    private record Seat(V3 center,V3 normal,boolean stock) {}
    private final List<Seat> candidates=new ArrayList<>();
    public void add(V3 center,V3 normal,boolean stock){candidates.add(new Seat(center,normal,stock));}
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
}
