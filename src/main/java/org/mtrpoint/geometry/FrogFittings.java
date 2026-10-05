package org.mtrpoint.geometry;

import java.util.List;

/** Omit only hardware inside the V nose; keep its outside check-rail fixtures. */
public final class FrogFittings {
    public static void clear(Mesh mesh,FrogGeometry frog,Profile p){
        V3 tip=frog.fittingTip(),forward=frog.fittingForward();
        var edges=List.of(
            new FrogGeometry.FittingEdge(tip.sub(forward.mul(.08)),forward.mul(-1)),
            new FrogGeometry.FittingEdge(tip.add(forward.mul(frog.fittingClearLength())),forward),
            frog.fittingEdge(0),frog.fittingEdge(1));
        Mesh out=new Mesh();
        for(var q:mesh.quads){
            if(!q.part().startsWith("fastener")){out.quad(q);continue;}
            Mesh inside=new Mesh();inside.quad(q);
            // Subtract the convex interior once. Faces spanning a V edge keep
            // their exterior portion, including the plate beneath guard fixtures.
            for(var edge:edges){
                Mesh next=new Mesh();
                for(var face:inside.quads){
                    var vertices=List.of(face.a(),face.b(),face.c(),face.d());
                    double lo=vertices.stream().mapToDouble(v->v.sub(edge.origin()).dot(edge.outward())).min().orElseThrow();
                    double hi=vertices.stream().mapToDouble(v->v.sub(edge.origin()).dot(edge.outward())).max().orElseThrow();
                    if(lo>=-1e-9){out.quad(face);continue;}
                    if(hi<=1e-9){next.quad(face);continue;}
                    Mesh.clip(out,face,edge.origin(),edge.outward().mul(-1));
                    Mesh.clip(next,face,edge.origin(),edge.outward());
                }
                inside=next;if(inside.quads.isEmpty())break;
            }
        }
        mesh.quads.clear();mesh.quads.addAll(out.quads);
    }
}
