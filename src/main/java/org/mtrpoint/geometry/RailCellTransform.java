package org.mtrpoint.geometry;

/** The MTR OBJ cell transform, including Optional Rail's model-offset roll pivot.
 *  Both attachments and generated turnout faces use this same affine transform. */
public final class RailCellTransform {
    private final V3 center,forward,right,origin,xAxis,yAxis,zAxis;
    private final double sign,offset,horizontal;
    public RailCellTransform(V3 a,V3 b,boolean flip,double offset,double cantRadians){
        center=a.lerp(b,.5);forward=b.sub(a).unit();right=forward.lateral().mul(-1);
        horizontal=Math.hypot(forward.x(),forward.z());sign=flip?-1:1;this.offset=offset;
        double yaw=Math.PI/2-Math.atan2(b.z()-a.z(),b.x()-a.x())+(flip?Math.PI:0);
        double pitch=Math.PI-Math.atan2(b.y()-a.y(),Math.hypot(b.x()-a.x(),b.z()-a.z()))*sign;
        double jitter=Math.toRadians((float)((a.x()*a.z())%10)/100F);
        java.util.function.Function<V3,V3> nativeMatrix=v->{
            // MTR's OBJ loader first rotates X by PI. Apply the render stack in reverse order.
            V3 p=new V3(v.x(),-v.y(),-v.z()).add(0,-offset,0);
            p=rz(p,-cantRadians*sign).add(0,offset,0);
            return ry(rx(rz(p,jitter),pitch),yaw).add(center).add(0,offset,0);
        };
        origin=nativeMatrix.apply(V3.ZERO);xAxis=nativeMatrix.apply(new V3(1,0,0)).sub(origin);
        yAxis=nativeMatrix.apply(new V3(0,1,0)).sub(origin);zAxis=nativeMatrix.apply(new V3(0,0,1)).sub(origin);
    }
    public V3 model(V3 v){return origin.add(xAxis.mul(v.x())).add(yAxis.mul(v.y())).add(zAxis.mul(v.z()));}
    /** Turnout templates are authored above the unbanked path in world Y. Recover their
     *  height above that path before applying precisely the native cell's pitch and roll. */
    public V3 world(V3 p){
        V3 d=p.sub(center);double along=(d.x()*forward.x()+d.z()*forward.z())/Math.max(1e-12,horizontal*horizontal);
        double height=d.y()-along*forward.y()-offset;
        return model(new V3(d.dot(right)*sign,height,along*sign));
    }
    private static V3 rz(V3 p,double a){double c=Math.cos(a),s=Math.sin(a);return new V3(p.x()*c-p.y()*s,p.x()*s+p.y()*c,p.z());}
    private static V3 rx(V3 p,double a){double c=Math.cos(a),s=Math.sin(a);return new V3(p.x(),p.y()*c-p.z()*s,p.y()*s+p.z()*c);}
    private static V3 ry(V3 p,double a){double c=Math.cos(a),s=Math.sin(a);return new V3(p.x()*c+p.z()*s,p.y(),-p.x()*s+p.z()*c);}
}
