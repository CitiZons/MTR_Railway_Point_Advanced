package org.mtrpoint.geometry;

import java.util.*;

/** Fixed V and K crossings: union of full-width rails, cut by continuous flange channels.
 * All construction uses detached visual polylines, never the simulation graph. */
public final class DiamondGeometry {
    private record Plane(V3 n,double d) {
        Plane(V3 n,V3 p){this(n,n.dot(p));}
        Plane reverse(){return new Plane(n.mul(-1),-d);}
        V3 origin(){return n.mul(d/n.dot(n));}
    }
    private record Span(int road,int line,V3 a,V3 b,Plane start,Plane end,double width,String part,boolean first,boolean last) {
        V3 normal(){return b.sub(a).lateral();}
        Plane level(double height){
            V3 u=b.sub(a);double horizontal=u.x()*u.x()+u.z()*u.z();
            V3 n=new V3(-u.x()*u.y()/horizontal,1,-u.z()*u.y()/horizontal);
            return new Plane(n,n.dot(a)+height);
        }
        boolean near(Span o){
            double r=(width+o.width)/2+.002;
            return Math.max(a.x(),b.x())+r>=Math.min(o.a.x(),o.b.x())&&Math.max(o.a.x(),o.b.x())+r>=Math.min(a.x(),b.x())
                &&Math.max(a.z(),b.z())+r>=Math.min(o.a.z(),o.b.z())&&Math.max(o.a.z(),o.b.z())+r>=Math.min(a.z(),b.z());
        }
        List<Plane> channel(){V3 n=normal();return List.of(start,end,new Plane(n,a.add(n.mul(width/2))),new Plane(n.mul(-1),a.sub(n.mul(width/2))));}
    }
    private DiamondGeometry(){}

    public static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent){
        build(out,j,s,p,extent,null);
    }
    public static void buildScissors(Mesh out,ScissorsLayout group,PointSettings s,Profile p){
        build(out,group.crossing(),s,p,0,group);
    }
    public static void three(Mesh out,Junction j,PointSettings s,Profile p,double[] starts,double[] ends){
        var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();var checks=new ArrayList<GuardRails.Run>();
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta()),width=Math.max(p.headWidth(),p.footWidth());
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
            Junction pair=new Junction(j.id(),Junction.Kind.Y,j.tracks().get(a),j.tracks().get(b),j.center(),0,0,j.extent());
            FrogGeometry frog=new FrogGeometry(pair,s,p,PointMesh.extent(j,s));double side=TurnoutFrame.side(pair,PointMesh.extent(j,s));
            for(int local=0;local<2;local++){
                int road=local==0?a:b;Track t=j.tracks().get(road);double sign=local==0?side:-side;
                checks.add(new GuardRails.Run(t,Math.max(starts[road],frog.toe(local)),Math.min(ends[road],frog.heel(local)+.65),sign*(p.centerOffset()-p.headWidth()-gap),true,true,p,s));
                checks.add(frog.guard(local));
            }
        }
        for(int road=0;road<3;road++){
            Track t=j.tracks().get(road);
            for(int sign:new int[]{-1,1}){
                int id=road*2+(sign>0?1:0);
                rails.addAll(path(t,starts[road],ends[road],sign*p.centerOffset(),0,road,id,width,"rail"));
                channels.addAll(path(t,starts[road],ends[road],sign*(p.centerOffset()-p.headWidth()/2-gap/2),0,road,id,gap,"channel"));
            }
        }
        int id=6;
        for(var run:GuardRails.merge(checks))if(run.end()>run.start()){rails.addAll(path(run,id,id,width));id++;}
        bake(out,rails,channels,p,s);
    }
    /** Reserve a fixed pocket for the complete throw of a moving crossing insert. */
    public static Mesh pocket(Mesh source,Mesh left,Mesh right,double top){
        var points=new ArrayList<V3>();for(Mesh m:List.of(left,right))for(var q:m.quads){
            // Animation padding has zero area and must not enlarge the physical pocket.
            if(Math.abs(V3.crossXZ(q.b().sub(q.a()),q.c().sub(q.a())))+Math.abs(V3.crossXZ(q.c().sub(q.a()),q.d().sub(q.a())))<1e-10)continue;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))points.add(new V3(v.x(),0,v.z()));
        }
        if(points.isEmpty())return source;
        points.sort(Comparator.comparingDouble(V3::x).thenComparingDouble(V3::z));
        var hull=new ArrayList<V3>();
        for(V3 v:points){while(hull.size()>1&&V3.crossXZ(hull.get(hull.size()-1).sub(hull.get(hull.size()-2)),v.sub(hull.get(hull.size()-1)))<=1e-10)hull.remove(hull.size()-1);hull.add(v);}
        int lower=hull.size();
        for(int i=points.size()-2;i>=0;i--){V3 v=points.get(i);while(hull.size()>lower&&V3.crossXZ(hull.get(hull.size()-1).sub(hull.get(hull.size()-2)),v.sub(hull.get(hull.size()-1)))<=1e-10)hull.remove(hull.size()-1);hull.add(v);}
        if(hull.size()<4)return source;hull.remove(hull.size()-1);var planes=new ArrayList<Plane>();V3 center=new V3(0,0,0);for(V3 v:hull)center=center.add(v);center=center.mul(1D/hull.size());
        for(int i=0;i<hull.size();i++){V3 a=hull.get(i),n=hull.get((i+1)%hull.size()).sub(a).lateral();if(center.sub(a).dot(n)>0)n=n.mul(-1);planes.add(new Plane(n,a));}
        return subtract(source,planes,true,new Plane(new V3(0,1,0),top));
    }
    /** Geometric union also covers guards that coincide only for part of their curves. */
    public static Mesh guards(List<GuardRails.Run> runs){
        Mesh out=new Mesh();record Style(Profile p,double height){}
        var groups=new LinkedHashMap<Style,List<GuardRails.Run>>();
        for(var r:GuardRails.merge(runs))groups.computeIfAbsent(new Style(r.profile(),r.settings().verticalOffset()),k->new ArrayList<>()).add(r);
        for(var group:groups.values()){
            Profile p=group.get(0).profile();PointSettings s=group.get(0).settings();var rails=new ArrayList<Span>();int id=0;
            for(var original:group){
                boolean start=original.flareStart(),end=original.flareEnd();
                for(var other:group)if(other!=original){
                    if(coveredEnd(original,original.start(),other))start=false;
                    if(coveredEnd(original,original.end(),other))end=false;
                }
                var run=new GuardRails.Run(original.road(),original.start(),original.end(),original.offset(),start,end,p,original.settings());
                for(var span:path(run,id,id,Math.max(p.headWidth(),p.footWidth())))rails.add(new Span(span.road,span.line,span.a,span.b,span.start,span.end,span.width,"guard",span.first,span.last));id++;
            }
            bake(out,rails,List.of(),p,s);
        }
        return out;
    }
    private static boolean coveredEnd(GuardRails.Run run,double d,GuardRails.Run other){
        V3 point=run.road().at(d).add(run.road().tangent(d).lateral().mul(run.offset()));
        double near=other.road().nearest(point);if(near<=other.start()+.4||near>=other.end()-.4)return false;
        V3 target=other.road().at(near).add(other.road().tangent(near).lateral().mul(other.offset()));
        return point.distance(target)<run.profile().headWidth()*.5;
    }
    private static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent,ScissorsLayout group){
        var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        double sine=Math.max(.025,Math.abs(V3.crossXZ(j.a().tangent(j.sa()),j.b().tangent(j.sb()))));
        double cosine=Math.abs(j.a().tangent(j.sa()).dot(j.b().tangent(j.sb())));
        // Guard/wing working lengths cover both rail intersections, even at acute angles.
        double reach=p.centerOffset()*(1+cosine)/sine+p.headWidth()/sine+.9;
        double sectionWidth=Math.max(p.footWidth(),p.headWidth());
        if(p.detail()!=null)for(var q:p.detail().rails())for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))
            sectionWidth=Math.max(sectionWidth,2*Math.abs(v.x()-p.detail().railCenter())*p.headWidth()/p.detail().headWidth());
        var roads=group==null?j.tracks():group.tracks();
        for(int road=0;road<roads.size();road++){
            Track t=roads.get(road);double c=t.nearest(j.center());
            double start=Math.max(0,c-extent),end=Math.min(t.length,c+extent);
            if(group!=null){double a=group.intersection(t,group.lo()),b=group.intersection(t,group.hi());start=Math.max(0,Math.min(a,b)-1);end=Math.min(t.length,Math.max(a,b)+1);}
            double guardStart=Math.max(start+.1,c-reach-s.guardLengthDelta()/2+s.guardShift());
            double guardEnd=Math.min(end-.1,c+reach+s.guardLengthDelta()/2+s.guardShift());
            for(int sign:new int[]{-1,1}){
                int id=road*4+(sign==1?2:0);
                rails.addAll(path(t,start,end,sign*p.centerOffset(),0,road,id,sectionWidth,"frog"));
                // These inside rails become the wings of the acute crossings and the check
                // rails of the obtuse crossings. Only their terminal mouths flare inward.
                double checkOffset=sign*(p.centerOffset()-p.headWidth()-gap-Math.max(0,s.guardGapDelta()));
                if(group==null||t.id.equals(j.a().id)||t.id.equals(j.b().id)){
                    if(guardEnd>guardStart)rails.addAll(path(t,guardStart,guardEnd,checkOffset,sign*.075,road,id+1,sectionWidth,"wing"));
                }else{
                    var checks=new ArrayList<GuardRails.Run>();
                    for(Junction y:group.turnouts())for(var run:GuardRails.forJunction(y,s,p))if(run.road().id.equals(t.id)){
                        double a=t.nearest(run.road().at(run.start())),b=t.nearest(run.road().at(run.end()));
                        double offset=run.offset()*(run.road().tangent(run.start()).dot(t.tangent(a))<0?-1:1);
                        if(Math.signum(offset)==sign)checks.add(new GuardRails.Run(t,Math.min(a,b),Math.max(a,b),offset,
                            a<b?run.flareStart():run.flareEnd(),a<b?run.flareEnd():run.flareStart(),p,s));
                    }
                    // Sample the complete run before the shared-region clip. A clip boundary
                    // is not a physical end and must not acquire another flared mouth.
                    for(var run:GuardRails.merge(checks)){
                        // merge() canonicalizes direction; compare distances only after
                        // projecting the merged endpoints back onto this road.
                        double a=t.nearest(run.road().at(run.start())),b=t.nearest(run.road().at(run.end()));
                        if(Math.max(a,b)>start&&Math.min(a,b)<end)rails.addAll(path(run,road,id+1,sectionWidth));
                    }
                }
                channels.addAll(path(t,start,end,sign*(p.centerOffset()-p.headWidth()/2-gap/2),0,road,id,gap,"channel"));
            }
        }
        bake(out,rails,channels,p,s);
    }
    private static void bake(Mesh out,List<Span> rails,List<Span> channels,Profile p,PointSettings s){
        var railBins=bins(rails);var channelBins=bins(channels);
        for(Span rail:rails){
            Mesh section=new Mesh();section.rail(rail.a,rail.b,1,1,p,s,rail.part);
            if(p.detail()==null||p.detail().rails().isEmpty()){
                // Generic beams have end caps; retain only the exposed ends of each run.
                for(int i=section.quads.size()-1;i>=0;i--)if((i%6==4&&!rail.first)||(i%6==5&&!rail.last))section.quads.remove(i);
            }
            section=miter(section,rail);
            // Voronoi seams join equal native cross sections into a solid V, without
            // double top faces or tapering each incoming rail before the heads merge.
            for(Span other:nearby(rail,railBins))if(rail.line!=other.line&&rail.near(other)){
                if(rail.part.equals("guard")){
                    Plane level=rail.level(0);
                    if(Math.abs(level.n.dot(other.a)-level.d)>.005||Math.abs(level.n.dot(other.b)-level.d)>.005)continue;
                }
                V3 a=rail.normal(),b=other.normal();double da=a.dot(rail.a),db=b.dot(other.a);
                if(Math.abs(Math.abs(a.dot(b))-1)<1e-10&&Math.abs(da-db*Math.signum(a.dot(b)))<1e-8){
                    // Equal-distance seams need one owner; subtracting both deletes steel.
                    if(other.line<rail.line)section=subtract(section,other.channel(),false,rail.level(p.top()+s.verticalOffset()));
                    continue;
                }
                section=subtract(section,List.of(other.start,other.end,new Plane(b.sub(a),db-da),new Plane(b.mul(-1).sub(a),-db-da)),false,rail.level(p.top()+s.verticalOffset()));
                section=subtract(section,List.of(other.start,other.end,new Plane(b.add(a),db+da),new Plane(a.sub(b),da-db)),false,rail.level(p.top()+s.verticalOffset()));
            }
            // Exact oblique cuts, not whole .24 m chunks. The cut walls make the noses
            // solid; rail feet remain below the wheel flange clearance depth.
            for(Span channel:nearby(rail,channelBins))if(rail.near(channel)){
                var planes=new ArrayList<Plane>(channel.channel());
                planes.add(rail.level(p.top()+s.verticalOffset()-.055).reverse());
                section=subtract(section,planes,true,rail.level(p.top()+s.verticalOffset()));
            }
            out.quads.addAll(section.quads);
        }
    }

    private static long cell(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static Map<Long,List<Span>> bins(List<Span> spans){
        var bins=new HashMap<Long,List<Span>>();
        for(Span s:spans)for(int x=(int)Math.floor(Math.min(s.a.x(),s.b.x())-s.width);x<=(int)Math.floor(Math.max(s.a.x(),s.b.x())+s.width);x++)
            for(int z=(int)Math.floor(Math.min(s.a.z(),s.b.z())-s.width);z<=(int)Math.floor(Math.max(s.a.z(),s.b.z())+s.width);z++)bins.computeIfAbsent(cell(x,z),k->new ArrayList<>()).add(s);
        return bins;
    }
    private static Set<Span> nearby(Span s,Map<Long,List<Span>> bins){
        var result=new LinkedHashSet<Span>();
        for(int x=(int)Math.floor(Math.min(s.a.x(),s.b.x())-s.width);x<=(int)Math.floor(Math.max(s.a.x(),s.b.x())+s.width);x++)
            for(int z=(int)Math.floor(Math.min(s.a.z(),s.b.z())-s.width);z<=(int)Math.floor(Math.max(s.a.z(),s.b.z())+s.width);z++)result.addAll(bins.getOrDefault(cell(x,z),List.of()));
        return result;
    }

    private static List<Span> path(Track t,double start,double end,double offset,double flare,int road,int line,double width,String part){
        int count=Math.max(1,(int)Math.ceil((end-start)/.24));var points=new ArrayList<V3>();
        for(int i=0;i<=count;i++){
            double d=start+(end-start)*i/count;
            double mouth=flare*Math.max(0,1-Math.min(d-start,end-d)/.35);
            points.add(t.at(d).add(t.tangent(d).lateral().mul(offset-mouth)));
        }
        return spans(points,road,line,width,part);
    }
    private static List<Span> path(GuardRails.Run run,int road,int line,double width){
        int count=Math.max(2,(int)Math.ceil((run.end()-run.start())/.24));var points=new ArrayList<V3>();
        for(int i=0;i<=count;i++)points.add(run.point(run.start()+(run.end()-run.start())*i/count));
        return spans(points,road,line,width,"wing");
    }
    private static List<Span> spans(List<V3> points,int road,int line,double width,String part){
        int count=points.size()-1;var result=new ArrayList<Span>();
        for(int i=0;i<count;i++){
            V3 a=points.get(i),b=points.get(i+1),u=horizontal(b.sub(a));
            V3 before=i==0?u:horizontal(a.sub(points.get(i-1))).add(u).unit();
            V3 after=i==count-1?u:horizontal(points.get(i+2).sub(b)).add(u).unit();
            result.add(new Span(road,line,a,b,new Plane(before.mul(-1),a),new Plane(after,b),width,part,i==0,i==count-1));
        }
        return result;
    }
    private static V3 horizontal(V3 v){return new V3(v.x(),0,v.z()).unit();}
    private static Mesh miter(Mesh source,Span s){
        Mesh out=new Mesh();V3 u=s.b.sub(s.a);double length=u.dot(u);
        for(var q:source.quads){var vertices=new ArrayList<V3>();
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){
                Plane end=v.sub(s.a).dot(u)/length<.5?s.start:s.end;
                vertices.add(v.add(u.mul((end.d-end.n.dot(v))/end.n.dot(u))));
            }
            out.quad(new Mesh.Quad(vertices.get(0),vertices.get(1),vertices.get(2),vertices.get(3),q.surface(),q.part(),q.index(),q.uv()));
        }
        return out;
    }
    /** Subtract a convex prism, keeping disjoint outside pieces and their original UVs. */
    private static Mesh subtract(Mesh source,List<Plane> planes,boolean walls,Plane top){
        Mesh outside=new Mesh(),inside=source;
        for(int i=0;i<planes.size();i++){
            Plane plane=planes.get(i);
            if(plane.n.dot(plane.n)<1e-16){
                if(plane.d< -1e-10){outside.quads.addAll(inside.quads);return outside;}
                continue;
            }
            Mesh next=new Mesh(),piece=new Mesh();V3 origin=plane.origin();
            for(var q:inside.quads){
                double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
                for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){double d=plane.n.dot(v)-plane.d;min=Math.min(min,d);max=Math.max(max,d);}
                if(max<=1e-10){next.quad(q);continue;}
                if(min>=-1e-10){piece.quad(q);continue;}
                if(walls&&(i==2||i==3)){
                    Mesh cut=new Mesh();Mesh.clip(cut,q,origin,plane.n.mul(-1));cap(cut,plane,top);piece.quads.addAll(cut.quads);
                }else Mesh.clip(piece,q,origin,plane.n.mul(-1));
                Mesh.clip(next,q,origin,plane.n);
            }
            outside.quads.addAll(piece.quads);inside=next;
            if(inside.quads.isEmpty())break;
        }
        return outside;
    }
    private static void cap(Mesh mesh,Plane plane,Plane top){
        var caps=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            var vertices=List.of(q.a(),q.b(),q.c(),q.d());
            if(vertices.stream().anyMatch(v->Math.abs(top.n.dot(v)-top.d)>.00001))continue;
            for(int k=0;k<4;k++){
                V3 a=vertices.get(k),b=vertices.get((k+1)%4);
                if(a.distance(b)<1e-8||Math.abs(plane.n.dot(a)-plane.d)>1e-8||Math.abs(plane.n.dot(b)-plane.d)>1e-8)continue;
                caps.add(new Mesh.Quad(b,a,a.add(0,-.055,0),b.add(0,-.055,0),q.surface(),"frog_wall",-1,q.uv()));
            }
        }
        mesh.quads.addAll(caps);
    }
}
