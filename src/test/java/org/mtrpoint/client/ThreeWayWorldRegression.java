package org.mtrpoint.client;

import org.mtrpoint.ThreeWayShapeRegression;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Verify the shared component assembly, after the renderer hides per-view check steel. */
public final class ThreeWayWorldRegression {
    public static void run(){
        Junction j=ThreeWayShapeRegression.common();Profile p=Profile.STANDARD;
        var views=PointRenderer.viewsForTest(List.of(j),PointSettings.DEFAULT,p);
        Mesh world=PointRenderer.worldForTest(views);
        var checks=world.quads.stream().filter(q->(q.part().equals("guard")||q.part().equals("wing"))&&List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-p.top())<1e-7)).toList();
        if(checks.isEmpty())throw new AssertionError("Shared three-way check steel disappeared");
        int samples=0;
        for(var q:checks)for(int[] corners:new int[][]{{0,1,2},{0,2,3}}){
            var vs=List.of(q.a(),q.b(),q.c(),q.d());V3 a=vs.get(corners[0]),b=vs.get(corners[1]),c=vs.get(corners[2]);
            if(Math.abs(V3.crossXZ(b.sub(a),c.sub(a)))<1e-9)continue;
            V3 point=a.mul(.23).add(b.mul(.31)).add(c.mul(.46));
            long count=checks.stream().filter(other->inside(point,other.a(),other.b(),other.c())||inside(point,other.a(),other.c(),other.d())).count();
            if(count!=1)throw new AssertionError("Three-way pooled guard/wing heads overlap: "+point+" faces="+count);
            samples++;
        }
        if(samples<20)throw new AssertionError("Three-way check overlap sweep too small");
        System.out.println("THREE_WAY_WORLD: PASS pooled guard/wing steel remains present without overlapping head surfaces; samples="+samples);
    }
    private static boolean inside(V3 p,V3 a,V3 b,V3 c){double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;return u>1e-8&&v>1e-8&&u+v<1-1e-8;}
}
