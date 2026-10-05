package org.mtrpoint.geometry;

/** Cheap checks before claiming native rail cells or constructing a turnout mesh. */
public final class GeometryEligibility {
    private GeometryEligibility(){}
    public static boolean supported(Junction j){
        return rejection(j).isEmpty();
    }
    public static String rejection(Junction j){
        if(j.kind()==Junction.Kind.DIAMOND)return "";
        double end=Math.min(j.extent(),j.tracks().stream().mapToDouble(t->t.length).min().orElse(0));
        if(!Double.isFinite(end)||end<2.5||end>80)return "extent";
        for(Track road:j.tracks())for(double d=0;d<=end;d+=.5){
            V3 point=road.at(d),direction=road.tangent(d);
            if(!Double.isFinite(point.x()+point.y()+point.z())||direction.dot(road.tangent(0))<.25)return "folded path";
        }
        for(int a=0;a<j.tracks().size();a++)for(int b=a+1;b<j.tracks().size();b++){
            Track first=j.tracks().get(a),second=j.tracks().get(b);double side=0;
            for(double d=.5;d<=end;d+=.5){
                V3 point=first.at(d);double other=second.nearestHorizontal(point);V3 delta=second.at(other).sub(point);
                if(Math.abs(delta.y())>.08)return "branch height";
                double lateral=delta.dot(first.tangent(d).lateral());
                // Asymmetric three-way fans can change order inside the common
                // crossing. Reject recrossing only after the roads fully separate.
                if(Math.abs(lateral)>2.5){if(side!=0&&Math.signum(lateral)!=side)return "recrossing";side=Math.signum(lateral);}
            }
        }
        return "";
    }
}
