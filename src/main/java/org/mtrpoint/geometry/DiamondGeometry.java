package org.mtrpoint.geometry;

import java.util.*;

/** Fixed V and K crossings: union of full-width rails, cut by continuous flange channels.
 * All construction uses detached visual polylines, never the simulation graph. */
public final class DiamondGeometry {
    /** How many oblique (sheared) rail end faces this process has emitted, for the regression that
     *  has to prove the oblique cut face is a real section rather than assume it. */
    public static int OBLIQUE_CAPS;
    private record Plane(V3 n,double d) {
        Plane(V3 n,V3 p){this(n,n.dot(p));}
        Plane reverse(){return new Plane(n.mul(-1),-d);}
        V3 origin(){return n.mul(d/n.dot(n));}
    }
    private record Span(int road,int line,V3 a,V3 b,Plane start,Plane end,double width,String part,boolean first,boolean last,boolean cutter) {
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
    /** One view's swept crossing steel plus the roads that own it. It is offered to a pooled check
     *  bake as cut planes only, is never emitted there, and its flange channels cut as well. */
    public record Steel(Mesh mesh,double top,Profile profile,PointSettings settings,List<Track> roads){}

    public static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent){
        build(out,j,s,p,extent,null);
    }
    /** A crossing runs through the junction: the window per road follows the native repeat cells the
     *  renderer hides, so the hand-over to the MTR rail lands on a cell edge on both sides. */
    public static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent,PointMesh.DiamondBoundary diamond){
        build(out,j,s,p,extent,j.tracks(),windowsOf(j,s,extent,diamond),j.tracks(),null);
    }
    /** A crossing point shared by several rails: one mesh draws every road that meets there, every
     *  road's flange channels cut that one mesh, and no other view draws the same rails again. */
    public static void centre(Mesh out,Junction owner,PointSettings s,Profile p,double extent,List<Track> drawn,PointMesh.DiamondBoundary windows,List<Track> channelRoads){
        build(out,owner,s,p,extent,drawn,windows==null?null:windows.windows(),channelRoads,null);
    }
    public static void buildScissors(Mesh out,ScissorsLayout group,PointSettings s,Profile p){
        build(out,group.crossing(),s,p,0,group.tracks(),null,group.tracks(),group);
    }
    /** The station window of every road of a plain crossing, in the order the roads are swept. */
    private static double[][] windowsOf(Junction j,PointSettings s,double extent,PointMesh.DiamondBoundary diamond){
        var roads=j.tracks();var out=new double[roads.size()][];
        for(int road=0;road<roads.size();road++){
            Track t=roads.get(road);double c=t.nearest(j.center());
            double start=Math.max(0,c-extent),end=Math.min(t.length,c+extent);
            if(diamond!=null&&road<diamond.roads()){double[] window=diamond.window(road);start=Math.max(0,Math.min(window[0],window[1]));end=Math.min(t.length,Math.max(window[0],window[1]));}
            out[road]=new double[]{start,end};
        }
        return out;
    }
    public static void three(Mesh out,Junction j,PointSettings s,Profile p,double[] starts,double[] ends){
        var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();var checks=new ArrayList<GuardRails.Run>();
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta()),width=Math.max(p.headWidth(),p.footWidth());
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
            Junction pair=new Junction(j.id(),Junction.Kind.Y,j.tracks().get(a),j.tracks().get(b),j.center(),0,0,j.extent());
            FrogGeometry frog=new FrogGeometry(pair,s,p,PointMesh.extent(j,s));double side=TurnoutFrame.side(pair,PointMesh.extent(j,s));
            for(int local=0;local<2;local++){
                int road=local==0?a:b;Track t=j.tracks().get(road);double sign=local==0?side:-side;
                checks.add(new GuardRails.Run(t,Math.max(starts[road],frog.toe(local)),Math.min(ends[road],frog.heel(local)+.65),sign*(p.centerOffset()-p.headWidth()-gap),true,true,p,s,"","wing"));
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
        var originalChecks=new ArrayList<GuardRails.Run>();
        for(int i=0;i<checks.size();i++)if(!s.guardEdits().containsKey(i))originalChecks.add(checks.get(i));
        for(var run:GuardRails.merge(originalChecks))if(run.end()>run.start()){rails.addAll(path(run,id,id,width));id++;}
        bake(out,rails,own(p,s,channels),List.of(),p,s);
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
    public static Mesh guards(List<GuardRails.Run> runs){return guards(runs,List.of());}
    /** Pooled check steel, cut by the flange channels of every crossing rail it really crosses.
     *  The intervals arrive from independent owners (crossings, turnouts, manual edits), so the
     *  union has to be cut once, here, against the whole component instead of per source. */
    public static Mesh guards(List<GuardRails.Run> runs,List<Steel> steel){
        Mesh out=new Mesh();record Style(Profile p,double height){}
        var groups=new LinkedHashMap<Style,List<GuardRails.Run>>();
        for(var r:GuardRails.merge(runs))groups.computeIfAbsent(new Style(r.profile(),r.settings().verticalOffset()),k->new ArrayList<>()).add(r);
        for(var group:groups.values()){
            Profile p=group.get(0).profile();PointSettings s=group.get(0).settings();double top=p.top()+s.verticalOffset();
            var rails=new ArrayList<Span>();int id=0;
            for(var original:group){
                boolean start=original.flareStart(),end=original.flareEnd();
                for(var other:group)if(other!=original){
                    if(coveredEnd(original,original.start(),other))start=false;
                    if(coveredEnd(original,original.end(),other))end=false;
                }
                var run=new GuardRails.Run(original.road(),original.start(),original.end(),original.offset(),start,end,p,original.settings(),original.mergeGroup(),original.part());
                for(var span:path(run,id,id,Math.max(p.headWidth(),p.footWidth())))rails.add(new Span(span.road,span.line,span.a,span.b,span.start,span.end,span.width,run.part(),span.first,span.last,false));id++;
            }
            bake(out,rails,crossingChannels(steel,top,p.railHeight()),crossingRails(steel,top,p.railHeight()),p,s);
        }
        return out;
    }
    /** Flange channels of the crossing rails offered as cutters, keyed by the running height they
     *  are drawn at. Each channel is a cut plane only and never emits steel of its own. */
    private static Map<Double,List<Span>> crossingChannels(List<Steel> steel,double top,double railHeight){
        var result=new TreeMap<Double,List<Span>>();
        for(Steel other:steel){
            if(Math.abs(other.top()-top)>railHeight)continue;
            var list=result.computeIfAbsent(other.top(),k->new ArrayList<>());
            list.addAll(channels(other.roads(),other.profile(),other.settings()));
        }
        return result;
    }
    /** The flange channels of a set of already drawn roads, offered as cut planes. A channel plane
     *  that lies exactly on a neighbouring head edge would delete a degenerate coincident face or
     *  leave a zero-area cut wall, so shrink by a micron. */
    private static List<Span> channels(List<Track> roads,Profile p,PointSettings s){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        double width=Math.max(1e-6,gap-2e-6);
        var list=new ArrayList<Span>();
        for(Track road:roads)for(int sign:new int[]{-1,1})
            for(Span span:path(road,0,road.length,sign*(p.centerOffset()-p.headWidth()/2-gap/2),0,-1,-2,width,"channel"))
                list.add(cutter(span));
        return list;
    }
    /** Remove the flange channels of a shared layout from steel some other node drew. A rail that
     *  runs through a crossing region still has to yield where a shared flangeway crosses it, even
     *  when the junction it belongs to cannot know about that layout; the cut runs once per view
     *  build, so it never adds per-frame work. Only steel that reaches the running band is touched:
     *  below the channel floor a foot or web may safely continue. */
    public static Mesh cutChannels(Mesh source,List<Track> roads,Profile p,PointSettings s,double top){
        return cutChannels(source,roads,p,s,top,false);
    }
    /**
     * Cut flange channels while keeping a fixed number of face slots for an animated turnout.
     * A channel can split one long head face into a different number of outside fragments at the
     * three endpoint poses (the taper changes the intersection by a few microns).  The renderer
     * interpolates cached quads by index, so allowing that variable list to escape here either
     * throws or pairs unrelated faces.  The stable path keeps the real clipped fragments and pads
     * the remainder with zero-area quads carrying the same material/part metadata.  Four slots are
     * sufficient for a convex rail face minus the four-sided channel prism; non-rail support faces
     * retain the ordinary variable clipping path.
     */
    public static Mesh cutChannels(Mesh source,List<Track> roads,Profile p,PointSettings s,double top,boolean fixedTopology){
        var cutters=channels(roads,p,s);
        if(cutters.isEmpty())return source;
        var channelBins=bins(cutters);
        Mesh out=new Mesh();
        for(var q:source.quads){
            // A switch blade is an animated, fixed-topology strip. Cutting it against a channel
            // here can split a different set of quads at the two endpoint poses, so View.mesh()
            // cannot interpolate the cached frames. ScissorsLayout already keeps the blade on its
            // stable seam side; crossing channels are applied to the static rail/frog/support
            // faces instead.
            if(q.part().equals("blade")){out.quad(q);continue;}
            double high=-Double.MAX_VALUE;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))high=Math.max(high,v.y());
            var section=new Mesh();section.quad(q);
            if(high>top-.055){
                Span face=spine(q,layerWidth(q,p,top));
                if(face!=null)for(Span channel:nearby(face,channelBins))
                    if(channel.line!=face.line&&meets(q,face,channel))
                        section=clipLayer(section,channel.channel(),p,top,top-.055);
            }
            if(fixedTopology&&(q.part().equals("rail")||q.part().equals("frog")||q.part().equals("stretcher"))&&high>top-.055){
                // A convex face clipped by the four channel side planes has at most four outside
                // pieces.  Keep every real piece, then fill the stable slot count with degenerates.
                int slots=4;int kept=Math.min(slots,section.quads.size());
                for(int i=0;i<kept;i++)out.quad(section.quads.get(i));
                Mesh.Quad seed=kept>0?section.quads.get(kept-1):q;
                for(int i=kept;i<slots;i++){
                    V3 v=seed.a();out.quad(new Mesh.Quad(v,v,v,v,seed.surface(),seed.part(),seed.index(),seed.uv()));
                }
            }else out.quads.addAll(section.quads);
        }
        return out;
    }
    /** The swept running steel of every crossing rail offered as a cutter: its own centre line and
     *  the width of the course that shares the running surface. Only the head course is offered,
     *  both because a wheel flange only ever meets the head and because the plan model ignores
     *  height: a foot is far wider than a head but lies below the check steel it would otherwise
     *  be allowed to cut. One span per course also keeps the cutter list small enough to bin. */
    private static List<Span> crossingRails(List<Steel> steel,double top,double railHeight){
        var result=new ArrayList<Span>();
        for(Steel other:steel){
            if(Math.abs(other.top()-top)>railHeight)continue;
            for(var q:other.mesh().quads){
                if(!isCrossingSteel(q.part()))continue;
                boolean head=true;
                for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(Math.abs(v.y()-other.top())>1e-6)head=false;
                if(!head)continue;
                Span span=spine(q,other.profile().headWidth());if(span!=null)result.add(cutter(span));
            }
        }
        return result;
    }
    /** Running rails, switch blades and the crossing nose: the steel check rails must yield to. */
    private static boolean isCrossingSteel(String part){return part.equals("rail")||part.equals("blade")||part.equals("frog");}
    private static boolean isCheckSteel(String part){return part.equals("guard")||part.equals("wing");}
    private static Span cutter(Span span){return new Span(span.road,span.line,span.a,span.b,span.start,span.end,span.width,span.part,span.first,span.last,true);}
    /** The sweep direction of one steel face, used to decide whether a crossing really cuts it. */
    private static Span spine(Mesh.Quad q,double width){
        // A swept face arrives in either winding: the built-in beams emit (start,start,end,end) while
        // the native model emits (start,end,end,start). Pair the opposite corners both ways and keep
        // the pairing that runs along the rail - the long one in plan - so a model section cannot
        // silently turn its section edge into the sweep direction and escape every cut.
        V3 first=q.a().lerp(q.d(),.5),firstEnd=q.b().lerp(q.c(),.5);
        V3 second=q.a().lerp(q.b(),.5),secondEnd=q.d().lerp(q.c(),.5);
        double along=Math.hypot(firstEnd.x()-first.x(),firstEnd.z()-first.z());
        double across=Math.hypot(secondEnd.x()-second.x(),secondEnd.z()-second.z());
        V3 a=along>=across?first:second,b=along>=across?firstEnd:secondEnd;
        if(a.distance(b)<1e-9)return null;
        V3 u=new V3(b.x()-a.x(),0,b.z()-a.z()).unit();
        return new Span(-1,-1,a,b,new Plane(u.mul(-1),a),new Plane(u,b),width,q.part(),true,true,true);
    }
    /** The width of the section layer one swept face belongs to: foot, web or head. */
    private static double layerWidth(Mesh.Quad q,Profile p,double top){
        double low=Double.MAX_VALUE,high=-Double.MAX_VALUE;
        for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){low=Math.min(low,v.y());high=Math.max(high,v.y());}
        if(high>top-.036)return p.headWidth();
        if(low<top-p.railHeight()+.025)return p.footWidth();
        return .022;
    }
    /** True when two swept centre lines really cross in plan: a parallel neighbour never does. */
    private static boolean crosses(Span rail,Span channel){
        V3 p=rail.a,q=rail.b,r=channel.a,t=channel.b;
        double ux=q.x()-p.x(),uz=q.z()-p.z(),vx=t.x()-r.x(),vz=t.z()-r.z();
        double scale=Math.hypot(ux,uz)*Math.hypot(vx,vz),den=ux*vz-uz*vx;
        if(scale<1e-12||Math.abs(den)<1e-7*scale)return false;
        double wx=r.x()-p.x(),wz=r.z()-p.z();
        double along=(wx*vz-wz*vx)/den,across=(wx*uz-wz*ux)/den;
        return along>=-.02&&along<=1.02&&across>=-.02&&across<=1.02;
    }
    /** Distance between two swept centre lines in plan, ignoring their height. */
    private static double planar(V3 a,V3 b,V3 c,V3 d){
        double ux=b.x()-a.x(),uz=b.z()-a.z(),vx=d.x()-c.x(),vz=d.z()-c.z(),wx=a.x()-c.x(),wz=a.z()-c.z();
        double uu=ux*ux+uz*uz,vv=ux*vx+uz*vz,ww=vx*vx+vz*vz,uw=ux*wx+uz*wz,vw=vx*wx+vz*wz,den=uu*ww-vv*vv;
        if(den<1e-12)return Math.min(pointSegment(a.x(),a.z(),ux,uz,uu,c.x(),c.z()),pointSegment(c.x(),c.z(),vx,vz,ww,a.x(),a.z()));
        double s=Math.max(0,Math.min(1,(vv*vw-ww*uw)/den)),t=Math.max(0,Math.min(1,(uu*vw-vv*uw)/den));
        return Math.hypot(wx+s*ux-t*vx,wz+s*uz-t*vz);
    }
    private static double pointSegment(double px,double pz,double ux,double uz,double uu,double qx,double qz){
        if(uu<1e-12)return Math.hypot(px-qx,pz-qz);
        double t=Math.max(0,Math.min(1,((px-qx)*ux+(pz-qz)*uz)/uu));
        return Math.hypot(qx+t*ux-px,qz+t*uz-pz);
    }
    /** True when two swept sections physically interpenetrate, whatever angle they meet at. A
     *  flange channel is only ever cut out of steel, so this needs no angle test: steel that
     *  touches the channel band has to go, steel that merely runs beside it stays. */
    private static boolean overlaps(Span rail,Span other){
        return planar(rail.a,rail.b,other.a,other.b)<(rail.width+other.width)/2-1e-9;
    }
    /** True when any part of this face's plan footprint reaches another swept section. A face that
     *  an earlier bake already clipped may be a sliver whose mid-line no longer represents the
     *  steel its corners still occupy, so those corners and edge midpoints are tested too. */
    private static boolean meets(Mesh.Quad q,Span face,Span other){
        double limit=(face.width+other.width)/2-1e-9;
        if(planar(face.a,face.b,other.a,other.b)<limit)return true;
        V3[] corners={q.a(),q.b(),q.c(),q.d()};
        for(int i=0;i<4;i++){
            if(pointToSegment(corners[i],other)<limit)return true;
            if(pointToSegment(corners[i].lerp(corners[(i+1)%4],.5),other)<limit)return true;
        }
        return false;
    }
    private static double pointToSegment(V3 p,Span other){
        double ux=other.b.x()-other.a.x(),uz=other.b.z()-other.a.z();
        return pointSegment(p.x(),p.z(),ux,uz,ux*ux+uz*uz,other.a.x(),other.a.z());
    }
    /** True when a cutter runs alongside this steel closely enough for the two sections to
     *  interpenetrate. A parallel neighbour must not be cut at an angle, but it may not be left
     *  inside the crossing rail either: that is what a check rail wedged into a wing looks like. */
    private static boolean alongside(Span rail,Span other){
        V3 a=rail.normal(),b=other.normal();
        if(Math.abs(Math.abs(a.dot(b))-1)>1e-6)return false;
        return overlaps(rail,other);
    }
    /** Clip one swept face by a cutter. The cut is the part of the face inside the cutter's own
     *  section that reaches up to a floor: a flange channel only has to clear the wheel flange
     *  depth, a crossing rail has to clear the whole section. The face itself bounds the cut from
     *  above, so a flat head top is severed by the cutter's walls instead of surviving a
     *  degenerate zero-height slab. */
    private static Mesh clipLayer(Mesh section,List<Plane> planes,Profile p,double top,double floor){
        var bounded=new ArrayList<Plane>(planes);
        bounded.add(new Plane(new V3(0,1,0),Math.min(floor,top)).reverse());
        return subtract(section,bounded,false,new Plane(new V3(0,1,0),top));
    }
    /** True when a plane really severs some face of this steel: a cutter that merely passes beside
     *  it leaves no end face behind. */
    private static boolean severed(Mesh source,Plane plane){
        for(var q:source.quads){
            double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){double d=plane.n.dot(v)-plane.d;min=Math.min(min,d);max=Math.max(max,d);}
            if(min<-1e-9&&max>1e-9)return true;
        }
        return false;
    }
    /** Cut a swept rail by cutter planes and close every cut face that severs the rail across its
     *  own direction: a plane whose normal runs along the rail gets the rail's real section there,
     *  taken from the native model outline or the built-in I-beam and triangulated from its boundary
     *  so the concave notches stay empty. It is clipped by the remaining planes and the flange floor,
     *  so it closes the cut instead of becoming a covering plate. A plane that only runs alongside
     *  the rail is a flangeway wall and gets no end face. */
    private static Mesh cut(Mesh section,List<Plane> planes,boolean walls,Plane top,V3 onRail,V3 direction,Profile p,PointSettings s,String part,double floor){
        Mesh out=subtract(section,planes,walls,top);
        V3 axis=horizontal(direction);if(axis.dot(axis)<1e-12)return out;
        for(Plane plane:planes){
            double length=Math.sqrt(plane.n.dot(plane.n));if(length<1e-12)continue;
            V3 unit=plane.n.mul(1/length);double facing=unit.dot(axis);
            if(Math.abs(facing)<.3)continue;
            if(!severed(section,plane))continue;
            V3 center=onRail.add(axis.mul((plane.d-unit.dot(onRail))/facing));
            // Winding measured, not inferred: the end-face regression counts faces "wound into their
            // own material" and "wound away from it" on real crossings, and reversing this flag made
            // 4..11 oblique faces point into the material with none pointing away. The original flag
            // is the one that matches the geometry, so it stays.
            // Winding stays (facing>0). A measured self-correction was tried this round - rebuild the
            // cap when its faces do not look along +unit - but the only independent measurement
            // available then reported every corrected cap as pointing into its own material, so the
            // change was reverted instead of being kept on an unverified derivation.
            Mesh capMesh=new Mesh();capMesh.railCutCap(center,axis,p,s,part,facing>0);
            if(capMesh.quads.isEmpty())continue;
            if(Math.abs(facing)<1-1e-9){
                // A bisector between two crossing rails is oblique: an axis-perpendicular section would
                // cover the flangeway like a plate. The same real I-beam outline is slid along the rail
                // onto the cutter plane instead, so the end face lies in the cut.
                OBLIQUE_CAPS++;
                Mesh sheared=new Mesh();
                for(var q:capMesh.quads)sheared.quad(new Mesh.Quad(shear(q.a(),plane,unit,axis,facing),shear(q.b(),plane,unit,axis,facing),
                    shear(q.c(),plane,unit,axis,facing),shear(q.d(),plane,unit,axis,facing),q.surface(),q.part(),q.index(),q.uv()));
                capMesh=sheared;
            }
            var bounded=new ArrayList<Plane>();
            for(Plane other:planes)if(other!=plane)bounded.add(other);
            if(floor<top.d)bounded.add(new Plane(new V3(0,1,0),floor).reverse());
            out.quads.addAll(subtract(capMesh,bounded,false,top).quads);
        }
        return out;
    }
    private static V3 shear(V3 v,Plane plane,V3 unit,V3 axis,double facing){
        return v.add(axis.mul((plane.d-unit.dot(v))/facing));
    }
    private static List<Plane> bisectors(Span rail,Span other){
        V3 a=rail.normal(),b=other.normal();double da=a.dot(rail.a),db=b.dot(other.a);
        return List.of(other.start,other.end,new Plane(b.sub(a),db-da),new Plane(b.mul(-1).sub(a),-db-da));
    }
    private static List<Plane> bisectorsMirror(Span rail,Span other){
        V3 a=rail.normal(),b=other.normal();double da=a.dot(rail.a),db=b.dot(other.a);
        return List.of(other.start,other.end,new Plane(b.add(a),db+da),new Plane(a.sub(b),da-db));
    }
    /** Remove every crossing rail and flange channel that really crosses this check steel from
     *  already-swept faces. Check steel reaches the world renderer from pooled runs, copied
     *  crossing wings and manual edits, so the cut has to run on the swept faces as well. */
    public static Mesh cutSteel(Mesh source,List<Steel> steel,Profile p,PointSettings s,double top){
        if(steel.isEmpty())return source;
        var channels=new ArrayList<Span>();for(var list:crossingChannels(steel,top,p.railHeight()).values())channels.addAll(list);
        var rails=crossingRails(steel,top,p.railHeight());
        if(channels.isEmpty()&&rails.isEmpty())return source;
        var railBins=bins(rails);var channelBins=channels.isEmpty()?null:bins(channels);
        Mesh out=new Mesh();
        for(var q:source.quads){
            if(!isCheckSteel(q.part())){out.quad(q);continue;}
            var section=new Mesh();section.quad(q);
            double low=Double.MAX_VALUE,high=-Double.MAX_VALUE;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){low=Math.min(low,v.y());high=Math.max(high,v.y());}
            Span face=spine(q,layerWidth(q,p,top));
            if(face!=null){
                for(Span other:nearby(face,railBins))if(other.line!=face.line&&meets(q,face,other)){
                    boolean flat=Math.abs(Math.abs(face.normal().dot(other.normal()))-1)<1e-6;
                    if(flat){
                        if(!alongside(face,other))continue;
                        section=cut(section,other.channel(),false,new Plane(new V3(0,1,0),top),face.a,face.normal().lateral(),p,s,q.part(),top-p.railHeight());
                        continue;
                    }
                    if(!crosses(face,other))continue;
                    section=clipLayer(section,bisectors(face,other),p,top,top-p.railHeight());
                    section=clipLayer(section,bisectorsMirror(face,other),p,top,top-p.railHeight());
                }
                if(channelBins!=null&&high>top-.055)for(Span channel:nearby(face,channelBins))
                    if(meets(q,face,channel))
                        section=cut(section,channel.channel(),false,new Plane(new V3(0,1,0),top),face.a,face.normal().lateral(),p,s,q.part(),top-.055);
            }
            out.quads.addAll(section.quads);
        }
        return out;
    }
    private static boolean coveredEnd(GuardRails.Run run,double d,GuardRails.Run other){
        V3 point=run.road().at(d).add(run.road().tangent(d).lateral().mul(run.offset()));
        double near=other.road().nearest(point);if(near<=other.start()+.4||near>=other.end()-.4)return false;
        V3 target=other.road().at(near).add(other.road().tangent(near).lateral().mul(other.offset()));
        return point.distance(target)<run.profile().headWidth()*.5;
    }
    private static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent,List<Track> drawn,double[][] windows,List<Track> channelRoads,ScissorsLayout group){
        var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        double sine=Math.max(.025,Math.abs(V3.crossXZ(j.a().tangent(j.sa()),j.b().tangent(j.sb()))));
        double cosine=Math.abs(j.a().tangent(j.sa()).dot(j.b().tangent(j.sb())));
        // Guard/wing working lengths cover both rail intersections, even at acute angles.
        double reach=p.centerOffset()*(1+cosine)/sine+p.headWidth()/sine+.9;
        double sectionWidth=Math.max(p.footWidth(),p.headWidth());
        if(p.detail()!=null)for(var q:p.detail().rails())for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))
            sectionWidth=Math.max(sectionWidth,2*Math.abs(v.x()-p.detail().railCenter())*p.headWidth()/p.detail().headWidth());
        for(int road=0;road<drawn.size();road++){
            Track t=drawn.get(road);double c=t.nearest(j.center());
            double start,end;
            if(windows!=null&&road<windows.length){start=Math.max(0,Math.min(windows[road][0],windows[road][1]));end=Math.min(t.length,Math.max(windows[road][0],windows[road][1]));}
            else if(group!=null){double a=group.intersection(t,group.lo()),b=group.intersection(t,group.hi());start=Math.max(0,Math.min(a,b)-1);end=Math.min(t.length,Math.max(a,b)+1);}
            else{start=Math.max(0,c-extent);end=Math.min(t.length,c+extent);}
            double guardStart=Math.max(start+.1,c-reach-s.guardLengthDelta()/2+s.guardShift());
            double guardEnd=Math.min(end-.1,c+reach+s.guardLengthDelta()/2+s.guardShift());
            for(int sign:new int[]{-1,1}){
                int id=road*4+(sign==1?2:0);
                rails.addAll(path(t,start,end,sign*p.centerOffset(),0,road,id,sectionWidth,"frog"));
                // These inside rails become the wings of the acute crossings and the check
                // rails of the obtuse crossings. Only their terminal mouths flare inward.
                double checkOffset=sign*(p.centerOffset()-p.headWidth()-gap-Math.max(0,s.guardGapDelta()));
                if(group==null||t.id.equals(j.a().id)||t.id.equals(j.b().id)){
                    // Preserve the baseline crossing unless this precise check rail is edited.
                    if(guardEnd>guardStart&&!s.guardEdits().containsKey(road*2+(sign==1?1:0)))
                        rails.addAll(path(t,guardStart,guardEnd,checkOffset,sign*.075,road,id+1,sectionWidth,"wing"));
                }else if(!s.guardEdits().containsKey(road*2+(sign==1?1:0))){
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
        // Roads that only pass over this crossing: their flanges cut this mesh even though this
        // view never draws them. Cutting by the true crossing set is what keeps every flangeway
        // clear when three, five or more tracks meet at one point.
        for(int road=0;road<channelRoads.size();road++){
            Track t=channelRoads.get(road);if(drawn.stream().anyMatch(d->d.id.equals(t.id)))continue;
            double c=t.nearest(j.center());double start=Math.max(0,c-extent),end=Math.min(t.length,c+extent);
            for(int sign:new int[]{-1,1})channels.addAll(path(t,start,end,sign*(p.centerOffset()-p.headWidth()/2-gap/2),0,road,road*4+(sign==1?2:0),gap,"channel"));
        }
        bake(out,rails,own(p,s,channels),List.of(),p,s);
    }
    /** Channels built by a view itself: one appearance, hence one running height. */
    private static Map<Double,List<Span>> own(Profile p,PointSettings s,List<Span> channels){
        return channels.isEmpty()?Map.of():Map.of(p.top()+s.verticalOffset(),channels);
    }
    private static void bake(Mesh out,List<Span> rails,Map<Double,List<Span>> channelGroups,List<Span> cutters,Profile p,PointSettings s){
        List<Span> binned=rails;
        if(!cutters.isEmpty()){binned=new ArrayList<>(rails);binned.addAll(cutters);}
        var railBins=bins(binned);var channelBins=new LinkedHashMap<Double,Map<Long,List<Span>>>();
        for(var group:channelGroups.entrySet())channelBins.put(group.getKey(),bins(group.getValue()));
        for(Span rail:rails){
            Mesh section=new Mesh();section.rail(rail.a,rail.b,1,1,p,s,rail.part);
            if(p.detail()==null||p.detail().rails().isEmpty()){
                // Generic beams have end caps; retain only the exposed ends of each run.
                for(int i=section.quads.size()-1;i>=0;i--)if((i%6==4&&!rail.first)||(i%6==5&&!rail.last))section.quads.remove(i);
            }else{
                // A native rail model repeats and carries no end faces at all, so an exposed end of a
                // run is an open section unless its own zMin cross-section is closed here.
                if(rail.first)section.railCap(rail.a,rail.b.sub(rail.a),1,p,s,rail.part,true);
                if(rail.last)section.railCap(rail.b,rail.b.sub(rail.a),1,p,s,rail.part,false);
            }
            section=miter(section,rail);
            V3 direction=rail.b.sub(rail.a);
            Plane level=rail.level(p.top()+s.verticalOffset());
            // Voronoi seams join equal native cross sections into a solid V, without
            // double top faces or tapering each incoming rail before the heads merge.
            for(Span other:nearby(rail,railBins))if(rail.line!=other.line&&(other.cutter?overlaps(rail,other):rail.near(other))){
                if(!other.cutter&&rail.part.equals("guard")){
                    Plane guardLevel=rail.level(0);
                    if(Math.abs(guardLevel.n.dot(other.a)-guardLevel.d)>.005||Math.abs(guardLevel.n.dot(other.b)-guardLevel.d)>.005)continue;
                }
                // A cutter is another view's running steel: it may only remove check steel, and
                // only where the two swept sections physically meet. A crossing rail severs check
                // steel along their shared seam; a parallel neighbour that interpenetrates it is
                // bounded by the crossing rail's own section instead, never by a wedge.
                if(other.cutter){
                    if(!isCheckSteel(rail.part))continue;
                    boolean flat=Math.abs(Math.abs(rail.normal().dot(other.normal()))-1)<1e-6;
                    if(flat){
                        if(!alongside(rail,other))continue;
                        section=cut(section,other.channel(),false,level,rail.a,direction,p,s,rail.part,level.d-.055);
                        continue;
                    }
                    if(!crosses(rail,other))continue;
                }
                V3 a=rail.normal(),b=other.normal();double da=a.dot(rail.a),db=b.dot(other.a);
                if(!other.cutter&&Math.abs(Math.abs(a.dot(b))-1)<1e-10&&Math.abs(da-db*Math.signum(a.dot(b)))<1e-8){
                    // Equal-distance seams need one owner; subtracting both deletes steel.
                    if(other.line<rail.line)section=cut(section,other.channel(),false,level,rail.a,direction,p,s,rail.part,level.d-p.railHeight());
                    continue;
                }
                section=cut(section,List.of(other.start,other.end,new Plane(b.sub(a),db-da),new Plane(b.mul(-1).sub(a),-db-da)),false,level,rail.a,direction,p,s,rail.part,level.d-p.railHeight());
                section=cut(section,List.of(other.start,other.end,new Plane(b.add(a),db+da),new Plane(a.sub(b),da-db)),false,level,rail.a,direction,p,s,rail.part,level.d-p.railHeight());
            }
            // Exact oblique cuts, not whole .24 m chunks. The cut walls make the noses
            // solid; rail feet remain below the wheel flange clearance depth. Only a channel
            // that really crosses this rail at its own running height may remove steel.
            for(var group:channelBins.entrySet()){
                if(Math.abs(group.getKey()-(p.top()+s.verticalOffset()))>p.railHeight())continue;
                for(Span channel:nearby(rail,group.getValue()))if(channel.cutter?overlaps(rail,channel):rail.near(channel))
                    section=cut(section,channel.channel(),true,level,rail.a,direction,p,s,rail.part,level.d-.055);
            }
            out.quads.addAll(section.quads);
        }
    }

    /** Remove every crossing flange channel from already-swept check steel. Check steel reaches
     *  the world renderer from several owners (pooled runs, crossing wings, manual edits), so the
     *  cut has to be applied to the swept faces as well, not only to freshly built spans. */
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
            result.add(new Span(road,line,a,b,new Plane(before.mul(-1),a),new Plane(after,b),width,part,i==0,i==count-1,false));
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
