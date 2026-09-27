package org.mtrpoint.geometry;

/** Shared endpoint sections: adjacent cells meet even when grade, curve or cant changes. */
public record RailSweep(V3 a,V3 b,V3 tangentA,V3 tangentB,double cantA,double cantB) {
    public V3 forward(double t){return tangentA.lerp(tangentB,Math.max(0,Math.min(1,t))).unit();}
    private V3 section(double x,double y,double t){
        V3 f=forward(t),right=f.lateral().mul(-1),up=new V3(f.y()*right.z(),f.z()*right.x()-f.x()*right.z(),-f.y()*right.x());
        double angle=cantA+(cantB-cantA)*Math.max(0,Math.min(1,t)),c=Math.cos(angle),s=Math.sin(angle);
        return a.lerp(b,t).add(right.mul(x*c-y*s)).add(up.mul(x*s+y*c));
    }
    public V3 model(V3 p,boolean flip,double offset,double zMin,double zMax){
        double t=flip?(zMax-p.z())/(zMax-zMin):(p.z()-zMin)/(zMax-zMin);
        return section(p.x()*(flip?-1:1),p.y()+offset,t);
    }
    public V3 rigid(V3 p,boolean flip,double offset){return section(p.x()*(flip?-1:1),p.y()+offset,.5).add(forward(.5).mul(p.z()*(flip?-1:1)));}
    public V3 world(V3 p){
        V3 delta=b.sub(a),d=p.sub(a);double t=(d.x()*delta.x()+d.z()*delta.z())/Math.max(1e-12,delta.x()*delta.x()+delta.z()*delta.z());
        V3 center=a.lerp(b,t);return section(p.sub(center).dot(delta.lateral().mul(-1)),p.y()-center.y(),t);
    }
}
