package org.mtrpoint.geometry;

import java.util.*;
import java.util.stream.Stream;

/** Fixed support hardware only. Steel paths and the blade animation are never changed. */
public final class TurnoutFittings {
    private TurnoutFittings(){}
    public static boolean applicable(Profile p){
        return p.detail()!=null&&!p.detail().nativeAtlas()&&!p.detail().siding()
            &&!p.detail().rails().isEmpty()&&!p.detail().bearers().isEmpty()&&!p.detail().fittings().isEmpty();
    }
    private static double bearer(Profile p,PointSettings s){return p.top()-p.detail().railTop()+s.verticalOffset()+p.detail().fittings().stream()
        .flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElse(p.detail().bearerTop());}
    private static double foot(Profile p,PointSettings s){
        return p.top()-p.detail().railTop()+s.verticalOffset()+p.detail().rails().stream()
            .flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElseThrow();
    }
    /** Sample metal UVs from the fitting, not the middle of its whole atlas. */
    private static Profile.Surface metal(Profile p){
        ModelDetail d=p.detail();
        double fittingBase=d.fittings().stream().flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElseThrow();
        Mesh.Quad sample=d.fittings().stream().filter(q->q.uv()!=null)
            .filter(q->Math.abs(q.center().x()-d.railCenter())>.08&&q.center().y()>fittingBase+.04)
            .findFirst().orElse(d.fittings().get(0));
        List<Float> uv=sample.uv()==null?Mesh.centerUv(sample.surface()):sample.uv();
        float u=0,v=0;for(int i=0;i<8;i+=2){u+=uv.get(i)/4;v+=uv.get(i+1)/4;}
        return new Profile.Surface(sample.surface().texture(),u,v,u,v,sample.surface().color());
    }
    static void shared(Mesh mesh,List<FittingSeats.Seat> seats,Profile p,PointSettings s,int index,String zone){
        if(seats.isEmpty()||!applicable(p))return;
        V3 n=seats.get(0).normal().unit(),lo=seats.get(0).center(),hi=lo;
        for(var seat:seats){if(seat.center().sub(lo).dot(n)<0)lo=seat.center();if(seat.center().sub(hi).dot(n)>0)hi=seat.center();}
        boolean slide=zone.equals("blade");
        double margin=p.footWidth()/2+.065+(slide?s.throwDistance():0);
        V3 a=lo.sub(n.mul(margin)),b=hi.add(n.mul(margin));
        double extension=Math.max(0,margin-.19);
        p.detail().seatBearer(mesh,lo,n,-extension,hi.sub(lo).dot(n)+extension,s,p,index);
        Profile.Surface surface=metal(p);double base=bearer(p,s),top=foot(p,s);
        box(mesh,a,b,Math.min(.22,s.sleeperWidth()*.9),base,top,surface,p,index,2);
        // Anchors stay beyond the swept feet; the sliding surface remains smooth.
        bolt(mesh,a.add(n.mul(.035)),n,top,surface,p,index);
        bolt(mesh,b.sub(n.mul(.035)),n,top,surface,p,index);
        if(!slide){outerClamp(mesh,lo,n.mul(-1),p,s,index);outerClamp(mesh,hi,n,p,s,index);}
    }
    static void stockClamp(Mesh mesh,FittingSeats.Seat seat,Profile p,PointSettings s,int index){
        if(seat.outside()!=null)outerClamp(mesh,seat.center(),seat.outside(),p,s,index);
    }
    private static void outerClamp(Mesh mesh,V3 center,V3 outside,Profile p,PointSettings s,int index){
        Mesh fitting=new Mesh();V3 n=outside.unit();p.detail().fitting(fitting,center,n,s,p,index);
        V3 cut=center.add(n.mul(p.footWidth()*.28));
        for(var q:fitting.quads)Mesh.clip(mesh,q,cut,n.mul(-1));
    }
    /** Common base, outer running-rail clamp, upright guard cheek, triangular ribs and anchor. */
    public static void guardPair(Mesh mesh,V3 running,V3 guard,V3 normal,Profile p,PointSettings s,int index){
        if(!applicable(p)||running.distance(guard)<.04)return;
        V3 delta=guard.sub(running),n=new V3(delta.x(),0,delta.z()).unit(),f=n.lateral();
        Profile.Surface surface=metal(p);double base=bearer(p,s),top=foot(p,s);
        if(p.detail().bearers().stream().allMatch(q->q.part().equals("track_bed"))){
            var faces=p.detail().bearers().stream().filter(q->q.center().x()>0).toList();
            if(!faces.isEmpty()){
                double lo=faces.stream().flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::x).min().orElseThrow();
                double hi=faces.stream().flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::x).max().orElseThrow();
                double margin=Math.max(p.detail().halfBearer()-p.detail().railCenter(),.23),width=running.distance(guard)+2*margin;
                V3 origin=running.sub(n.mul(margin));
                java.util.function.Function<V3,V3> transform=v->origin.add(n.mul((v.x()-lo)/(hi-lo)*width))
                    .add(f.mul(v.z()*(s.sleeperSpacing()+.02)/(p.detail().zMax()-p.detail().zMin())))
                    .add(0,v.y()+p.top()-p.detail().railTop()+s.verticalOffset(),0);
                for(var q:faces)mesh.quad(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()),q.surface(),"track_bed",index,q.uv()));
            }
        }
        box(mesh,running.sub(n.mul(.21)),guard.add(n.mul(.20)),Math.min(.22,s.sleeperWidth()*.9),base,top,surface,p,index,2);
        outerClamp(mesh,running,n.mul(-1),p,s,index);
        box(mesh,guard.add(n.mul(.013)),guard.add(n.mul(.024)),.16,top+.036,top+.10,surface,p,index,2);
        for(double along:new double[]{-.055,0,.055})gusset(mesh,guard.add(f.mul(along)),n,f,top,surface,p,index,along==0?2:0);
        bolt(mesh,guard.add(n.mul(.155)),n,top,surface,p,index);
    }
    public static void guardsAtRow(Mesh mesh,List<GuardRails.Run> guards,V3 center,V3 normal,Profile p,PointSettings s,int index){
        guardsAtRow(mesh,null,guards,center,normal,p,s,index);
    }
    public static void guardsAtRow(Mesh mesh,FittingSeats seats,List<GuardRails.Run> guards,V3 center,V3 normal,Profile p,PointSettings s,int index){
        if(!applicable(p))return;
        V3 forward=new V3(normal.z(),0,-normal.x());
        for(GuardRails.Run run:guards){
            double near=run.road().nearest(center),at=near;
            for(int k=0;k<8;k++){
                double den=run.road().tangent(at).dot(forward);if(Math.abs(den)<.1)break;
                at=Math.max(run.start(),Math.min(run.end(),at-run.point(at).sub(center).dot(forward)/den));
            }
            V3 check=run.point(at);
            if(near<run.start()-.2||near>run.end()+.2||Math.abs(check.sub(center).dot(forward))>s.sleeperWidth()/2)continue;
            V3 running=run.road().at(at).add(run.road().tangent(at).lateral().mul(Math.signum(run.offset())*p.centerOffset()));
            if(seats!=null&&!seats.guardPair(running,check))continue;
            guardPair(mesh,running,check,normal,p,s,index);
        }
    }
    private static void box(Mesh out,V3 a,V3 b,double width,double bottom,double top,Profile.Surface material,Profile p,int index,int lastLod){
        if(top<=bottom||a.distance(b)<1e-8)return;
        Mesh shape=new Mesh();shape.beam(a,b,width,width,bottom,top,material,"fastener",index);emit(out,shape,p,index,lastLod);
    }
    private static void gusset(Mesh out,V3 origin,V3 n,V3 f,double top,Profile.Surface material,Profile p,int index,int lastLod){
        // Tall edge against the web; the outer toe lands on the common plate.
        double[][] section={{.024,0},{.145,0},{.145,.012},{.024,.095}};
        V3[] front=new V3[4],back=new V3[4];
        for(int i=0;i<4;i++){
            V3 v=origin.add(n.mul(section[i][0])).add(0,top+section[i][1],0);
            front[i]=v.sub(f.mul(.009));back[i]=v.add(f.mul(.009));
        }
        Mesh shape=new Mesh();
        oriented(shape,front[0],front[1],front[2],front[3],f.mul(-1),material,index);
        oriented(shape,back[0],back[1],back[2],back[3],f,material,index);
        for(int i=0;i<4;i++){
            int j=(i+1)%4;V3 wanted=n.mul(section[j][1]-section[i][1]).add(0,section[i][0]-section[j][0],0);
            oriented(shape,front[i],back[i],back[j],front[j],wanted,material,index);
        }
        emit(out,shape,p,index,lastLod);
    }
    private static void bolt(Mesh out,V3 c,V3 n,double top,Profile.Surface material,Profile p,int index){
        // Middle keeps a hex nut; near adds its washer and exposed stud.
        prism(out,c,n,.025,top,top+.006,10,material,p,index,0);
        prism(out,c,n,.017,top+.006,top+.027,6,material,p,index,1);
        prism(out,c,n,.008,top+.027,top+.034,8,material,p,index,0);
    }
    private static void prism(Mesh out,V3 c,V3 n,double radius,double bottom,double top,int sides,Profile.Surface material,Profile p,int index,int lastLod){
        V3 f=n.lateral();V3[] lo=new V3[sides],hi=new V3[sides];Mesh shape=new Mesh();
        for(int i=0;i<sides;i++){
            double angle=i*Math.PI*2/sides;V3 point=c.add(n.mul(radius*Math.cos(angle))).add(f.mul(radius*Math.sin(angle)));
            lo[i]=point.add(0,bottom,0);hi[i]=point.add(0,top,0);
        }
        for(int i=0;i<sides;i++){
            int j=(i+1)%sides;
            oriented(shape,lo[i],lo[j],hi[j],hi[i],lo[i].lerp(lo[j],.5).sub(c.add(0,bottom,0)),material,index);
            oriented(shape,c.add(0,top,0),hi[i],hi[j],hi[j],new V3(0,1,0),material,index);
            oriented(shape,c.add(0,bottom,0),lo[i],lo[j],lo[j],new V3(0,-1,0),material,index);
        }
        emit(out,shape,p,index,lastLod);
    }
    private static void oriented(Mesh mesh,V3 a,V3 b,V3 c,V3 d,V3 wanted,Profile.Surface material,int index){
        V3 u=b.sub(a),v=c.sub(a),cross=new V3(u.y()*v.z()-u.z()*v.y(),u.z()*v.x()-u.x()*v.z(),u.x()*v.y()-u.y()*v.x());
        if(cross.dot(wanted)<0){
            if(c.equals(d))mesh.quad(a,c,b,b,material,"fastener",index);
            else mesh.quad(a,d,c,b,material,"fastener",index);
        }else mesh.quad(a,b,c,d,material,"fastener",index);
    }
    private static void emit(Mesh out,Mesh shape,Profile p,int index,int lastLod){
        if(p.detail().fittingLods().isEmpty()){out.quads.addAll(shape.quads);return;}
        String[] names={"fastener_near","fastener_mid","fastener_far"};
        for(int lod=0;lod<=lastLod;lod++)for(var q:shape.quads)
            out.quad(new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),names[lod],index,q.uv()));
    }
}
