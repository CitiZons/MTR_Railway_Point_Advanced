package org.mtrpoint.geometry;

import java.util.*;

/** Fixed V and K crossings: union of full-width rails, cut by continuous flange channels.
 * All construction uses detached visual polylines, never the simulation graph. */
public final class DiamondGeometry {
    /** A normal request uses the crossing's symmetric extent; a fixed Y owns exact toe/heel intervals. */
    public record Request(Junction junction,PointSettings settings,Profile profile,double extent,
                          double aStart,double aEnd,double bStart,double bEnd,List<FrogGeometry.WingRun> wings) {
        public Request(Junction junction,PointSettings settings,Profile profile,double extent){this(junction,settings,profile,extent,Double.NaN,Double.NaN,Double.NaN,Double.NaN,List.of());}
        public Request {wings=wings==null?List.of():List.copyOf(wings);}
        public boolean fixedY(){return junction.kind()==Junction.Kind.Y&&Double.isFinite(aStart)&&Double.isFinite(aEnd)&&Double.isFinite(bStart)&&Double.isFinite(bEnd);}
    }
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
        /** Same centre line and mouths, but a section width that matches one beam layer. */
        Span withWidth(double value){return new Span(road,line,a,b,start,end,value,part,first,last);}
    }
    private DiamondGeometry(){}

    public static Optional<Request> fixedY(Junction j,PointSettings s,Profile p,double extent){
        if(j.kind()!=Junction.Kind.Y||s.movableFrog())return Optional.empty();
        FrogGeometry frog=new FrogGeometry(j,s,p,extent);
        return Optional.of(new Request(j,s,p,extent,frog.toe(0),frog.heel(0),frog.toe(1),frog.heel(1),frog.fixedWings()));
    }

    public static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent){
        build(out,j,s,p,extent,null);
    }
    public static void buildScissors(Mesh out,ScissorsLayout group,PointSettings s,Profile p){
        build(out,group.crossing(),s,p,0,group);
    }
    /** One fixed assembly lets every nearby crossing channel cut every overlapping rail. */
    public static Mesh combine(List<Request> requests){
        return combine(requests,List.of());
    }
    /** The same assembly, but the check rails of the adjoining turnouts are folded into it: a
     * guard that meets a crossing wing exactly becomes one rail instead of two overlapping ones. */
    public static Mesh combine(List<Request> requests,List<GuardRails.Run> externalGuards){
        if(externalGuards==null||externalGuards.isEmpty())return combineLegacy(requests);
        Mesh out=new Mesh();record Style(Profile profile,double height){}
        var groups=new LinkedHashMap<Style,List<Request>>();
        for(var request:requests)groups.computeIfAbsent(new Style(request.profile,request.settings.verticalOffset()),k->new ArrayList<>()).add(request);
        var remaining=new ArrayList<>(externalGuards);
        int styleGroup=0;
        for(var group:groups.values()){
            var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();var checks=new ArrayList<GuardRails.Run>();
            var wings=new ArrayList<FrogGeometry.WingRun>();int lineBase=0;
            for(var request:group){append(rails,channels,checks,wings,request,null,lineBase);lineBase+=1000;}
            Profile p=group.get(0).profile();double height=group.get(0).settings.verticalOffset();
            double width=Math.max(p.headWidth(),p.footWidth());
            // One shared pool holds every check-side run: this crossing's own checks, the
            // adjoining turnouts' guard rails, and the fixed wings that are check steel.
            // Merging the plain runs before anything is converted unifies coincident rails
            // that live on two different Track IDs and drops their interior seams.
            var pool=new ArrayList<GuardRails.Run>(checks);
            for(var it=remaining.iterator();it.hasNext();){
                GuardRails.Run run=it.next();
                if(!run.profile().equals(p)||run.settings().verticalOffset()!=height)continue;
                pool.add(run);it.remove();
            }
            // A wing sitting on the crossing's own running rail is frog steel and keeps its
            // incoming mouth; every other fixed wing is check steel and shares the guard pool.
            var incoming=new ArrayList<FrogGeometry.WingRun>();var checkWings=new ArrayList<FrogGeometry.WingRun>();
            for(var wing:wings)if(Math.abs(Math.abs(wing.offset())-p.centerOffset())<=WING_BASE_TOLERANCE)incoming.add(wing);else checkWings.add(wing);
            var poolWings=new ArrayList<FrogGeometry.WingRun>(checkWings);
            var poolRuns=exposeEnds(GuardRails.merge(pool));
            GeometryProbe.guardSeams(poolRuns);
            for(var run:poolRuns)if(run.end()>run.start()+1e-7)poolWings.add(convert(run));
            var mergedIncoming=FrogGeometry.mergeWings(incoming);
            var mergedPool=joinExactEnds(FrogGeometry.mergeWings(poolWings),p);
            GeometryProbe.wingRuns(styleGroup++,height,poolWings,mergedPool);
            appendWings(rails,mergedIncoming,width,lineBase,2,"wing");
            appendWings(rails,mergedPool,width,lineBase+500,8,"guard");
            if(Boolean.getBoolean("pointProbeBins"))GeometryProbe.spanBins(styleGroup,spanRows(rails,lineBase+500,poolRuns));
            bake(out,rails,channels,p,group.get(0).settings);
        }
        // A guard whose style owns no crossing here is still real steel; render it as before.
        if(!remaining.isEmpty())out.quads.addAll(guards(remaining).quads);
        return out;
    }
    /** Dump the pooled check spans together with the runs they were pooled from. */
    private static List<String> spanRows(List<Span> rails,int lineBase,List<GuardRails.Run> runs){
        var rows=new ArrayList<String>();
        for(var run:runs)rows.add(String.format(Locale.ROOT,"pool road=%s nodes=%s->%s start=%.4f end=%.4f offset=%.5f",
            run.road().id,run.road().startNode,run.road().endNode,run.start(),run.end(),run.offset()));
        for(var rail:rails)if(rail.line>=lineBase)rows.add(String.format(Locale.ROOT,"span %s a=%.4f,%.4f,%.4f b=%.4f,%.4f,%.4f",
            rail.part,rail.a.x(),rail.a.y(),rail.a.z(),rail.b.x(),rail.b.y(),rail.b.z()));
        return rows;
    }
    private static Mesh combineLegacy(List<Request> requests){
        Mesh out=new Mesh();record Style(Profile profile,double height){}
        var groups=new LinkedHashMap<Style,List<Request>>();
        for(var request:requests)groups.computeIfAbsent(new Style(request.profile,request.settings.verticalOffset()),k->new ArrayList<>()).add(request);
        int styleGroup=0;
        for(var group:groups.values()){
            var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();var checks=new ArrayList<GuardRails.Run>();
            var wings=new ArrayList<FrogGeometry.WingRun>();int lineBase=0;
            for(var request:group){append(rails,channels,checks,wings,request,null,lineBase);lineBase+=1000;}
            var mergedWings=FrogGeometry.mergeWings(wings);
            GeometryProbe.wingRuns(styleGroup++,group.get(0).settings.verticalOffset(),wings,mergedWings);
            appendWings(rails,mergedWings,Math.max(group.get(0).profile.headWidth(),group.get(0).profile.footWidth()),lineBase,2,"wing");
            appendChecks(rails,checks,Math.max(group.get(0).profile.headWidth(),group.get(0).profile.footWidth()),lineBase+1000);
            Request first=group.get(0);bake(out,rails,channels,first.profile,first.settings);
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
        var mergedRuns=GuardRails.merge(runs);
        GeometryProbe.guardRuns(runs,mergedRuns);
        var guardGroups=new LinkedHashMap<Style,List<GuardRails.Run>>();
        for(var r:mergedRuns)guardGroups.computeIfAbsent(new Style(r.profile(),r.settings().verticalOffset()),k->new ArrayList<>()).add(r);
        for(var group:guardGroups.values()){
            Profile p=group.get(0).profile();PointSettings s=group.get(0).settings();var rails=new ArrayList<Span>();int id=0;
            for(var original:exposeEnds(group)){
                for(var span:path(original,id,id,Math.max(p.headWidth(),p.footWidth())))rails.add(new Span(span.road,span.line,span.a,span.b,span.start,span.end,span.width,"guard",span.first,span.last));id++;
            }
            bake(out,rails,List.of(),p,s);
        }
        return out;
    }
    /** Re-expose the ends a merge closed: the flare belongs to the extreme end of the union,
     * not to the interior seam where two coincident runs were stitched together. */
    private static List<GuardRails.Run> exposeEnds(List<GuardRails.Run> runs){
        var result=new ArrayList<GuardRails.Run>(runs.size());
        for(var run:runs){
            boolean start=run.flareStart(),end=run.flareEnd();
            for(var other:runs)if(other!=run){
                if(coveredEnd(run,run.start(),other))start=false;
                if(coveredEnd(run,run.end(),other))end=false;
            }
            result.add(start==run.flareStart()&&end==run.flareEnd()?run:new GuardRails.Run(run.road(),run.start(),run.end(),run.offset(),start,end,run.profile(),run.settings()));
        }
        return result;
    }
    /** Fold one check run onto the frog's representation. The native 10 cm terminal flare
     * becomes the blend from the base line out to the parked check offset. */
    private static FrogGeometry.WingRun convert(GuardRails.Run run){
        double base=run.offset(),flared=base-Math.signum(base)*.10;
        return new FrogGeometry.WingRun(run.road(),run.start(),
            run.flareStart()?Math.min(run.end(),run.start()+.4):run.start(),
            run.flareEnd()?Math.max(run.start(),run.end()-.4):run.end(),
            run.end(),run.flareStart()?flared:base,base,run.flareEnd()?flared:base,true,true);
    }
    private static boolean coveredEnd(GuardRails.Run run,double d,GuardRails.Run other){
        // Test the working centre lines, before either exposed mouth is flared inward.
        // Otherwise the 10 cm flare itself makes physically overlapping runs look separate.
        V3 point=run.center(d);double near=other.road().nearest(point);
        if(near<other.start()-.12||near>other.end()+.12)return false;
        if(Math.abs(run.road().tangent(d).dot(other.road().tangent(near)))<.98)return false;
        // Slightly different MTR centre lines may still describe one overlapping rail.
        // Use the full section width, otherwise both internal flared mouths survive.
        return point.distance(other.center(near))<(run.profile().footWidth()+other.profile().footWidth())*.5;
    }
    private static void build(Mesh out,Junction j,PointSettings s,Profile p,double extent,ScissorsLayout group){
        var rails=new ArrayList<Span>();var channels=new ArrayList<Span>();var checks=new ArrayList<GuardRails.Run>();
        var wings=new ArrayList<FrogGeometry.WingRun>();
        append(rails,channels,checks,wings,new Request(j,s,p,extent),group,0);
        appendWings(rails,FrogGeometry.mergeWings(wings),Math.max(p.headWidth(),p.footWidth()),1000,2,"wing");
        appendChecks(rails,checks,Math.max(p.headWidth(),p.footWidth()),2000);bake(out,rails,channels,p,s);
    }
    private static void append(List<Span> rails,List<Span> channels,List<GuardRails.Run> checks,List<FrogGeometry.WingRun> wings,Request request,ScissorsLayout group,int lineBase){
        Junction j=request.junction;PointSettings s=request.settings;Profile p=request.profile;double extent=request.extent;
        if(request.fixedY()){appendFixedY(rails,channels,wings,request,lineBase);return;}
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
                int id=lineBase+road*4+(sign==1?2:0);
                rails.addAll(path(t,start,end,sign*p.centerOffset(),0,road,id,sectionWidth,"frog"));
                // These inside rails become the wings of the acute crossings and the check
                // rails of the obtuse crossings. Only their terminal mouths flare inward.
                double checkOffset=sign*(p.centerOffset()-p.headWidth()-gap-Math.max(0,s.guardGapDelta()));
                if(group==null||t.id.equals(j.a().id)||t.id.equals(j.b().id)){
                    if(guardEnd>guardStart)checks.add(new GuardRails.Run(t,guardStart,guardEnd,checkOffset,true,true,p,s));
                }else{
                    var localChecks=new ArrayList<GuardRails.Run>();
                    for(Junction y:group.turnouts())for(var run:GuardRails.forJunction(y,s,p))if(run.road().id.equals(t.id)){
                        double a=t.nearest(run.road().at(run.start())),b=t.nearest(run.road().at(run.end()));
                        double offset=run.offset()*(run.road().tangent(run.start()).dot(t.tangent(a))<0?-1:1);
                        if(Math.signum(offset)==sign)localChecks.add(new GuardRails.Run(t,Math.min(a,b),Math.max(a,b),offset,
                            a<b?run.flareStart():run.flareEnd(),a<b?run.flareEnd():run.flareStart(),p,s));
                    }
                    // Sample the complete run before the shared-region clip. A clip boundary
                    // is not a physical end and must not acquire another flared mouth.
                    for(var run:GuardRails.merge(localChecks)){
                        // merge() canonicalizes direction; compare distances only after
                        // projecting the merged endpoints back onto this road.
                        double a=t.nearest(run.road().at(run.start())),b=t.nearest(run.road().at(run.end()));
                        if(Math.max(a,b)>start&&Math.min(a,b)<end)checks.add(run);
                    }
                }
                channels.addAll(path(t,start,end,sign*(p.centerOffset()-p.headWidth()/2-gap/2),0,road,id,gap,"channel"));
            }
        }
    }
    /** Add a Y's actual fixed replacement steel; no node-centred envelope. */
    private static void appendFixedY(List<Span> rails,List<Span> channels,List<FrogGeometry.WingRun> wings,Request request,int lineBase){
        Junction j=request.junction;PointSettings s=request.settings;Profile p=request.profile;
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta()),width=sectionWidth(p),side=TurnoutFrame.side(j,request.extent);
        double[] starts={request.aStart,request.bStart},ends={request.aEnd,request.bEnd};
        for(int road=0;road<2;road++){
            double sign=road==0?side:-side;Track track=j.tracks().get(road);int line=lineBase+road*4;
            rails.addAll(path(track,starts[road],ends[road],sign*p.centerOffset(),0,road,line,width,"frog"));
            channels.addAll(path(track,starts[road],ends[road],sign*(p.centerOffset()-p.headWidth()/2-gap/2),0,road,line,gap,"channel"));
        }
        wings.addAll(request.wings);
    }
    private static void appendWings(List<Span> rails,List<FrogGeometry.WingRun> wings,double width,int lineBase,int roadBase,String part){
        int line=lineBase,road=roadBase;
        for(var wing:wings)rails.addAll(wingPath(wing,road++,line++,width,part));
    }
    /** Distance from a fixed wing's running rail at which its base line stops being frog steel. */
    private static final double WING_BASE_TOLERANCE=.0125;
    private static double tangentDot(FrogGeometry.WingRun a,FrogGeometry.WingRun b,double atA,double atB){
        return Math.abs(a.road().tangent(atA).dot(b.road().tangent(atB)));
    }
    /** The physical BASE check-rail end of a run: its centre line before the terminal mouth
     * is flared inward. Stations on unrelated Tracks are not comparable, so only this point is. */
    private static V3 baseEnd(FrogGeometry.WingRun w){
        return w.road().at(w.flareAt()).add(w.road().tangent(w.flareAt()).lateral().mul(w.offset()));
    }
    private static V3 baseStart(FrogGeometry.WingRun w){
        return w.road().at(w.start()).add(w.road().tangent(w.start()).lateral().mul(w.offset()));
    }
    /** Where two fixed wings actually join, a shared mouth is an interior cross-section, not an
     * end: dropping it keeps the steel continuous across two different source paths. */
    private static List<FrogGeometry.WingRun> joinExactEnds(List<FrogGeometry.WingRun> input,Profile p){
        var runs=input.stream().map(FrogGeometry.WingRun::canonical)
            .sorted(Comparator.comparing((FrogGeometry.WingRun w)->w.road().id).thenComparingDouble(FrogGeometry.WingRun::offset)
                .thenComparingDouble(FrogGeometry.WingRun::start)).toList();
        double tolerance=Math.max(.0125,p.headWidth()*.3);
        var result=new ArrayList<>(runs);
        for(int i=0;i<runs.size();i++)for(int k=i+1;k<runs.size();k++){
            FrogGeometry.WingRun a=result.get(i),b=result.get(k);
            // Different Tracks meet physically, not by station number; two runs on one road
            // are the same steel and must never be folded through this path.
            if(a.road().id.equals(b.road().id))continue;
            // One unordered pair gets at most one oriented join, so a valid mutual pair keeps
            // one shared mouth instead of deleting both.
            if(baseEnd(a).distance(baseStart(b))<=tolerance&&tangentDot(a,b,a.flareAt(),b.start())>=.995){
                result.set(i,new FrogGeometry.WingRun(a.road(),a.start(),a.startFlareAt(),a.flareAt(),a.flareAt(),
                    a.startOffset(),a.offset(),a.offset(),a.capStart(),false));
                result.set(k,new FrogGeometry.WingRun(b.road(),b.start(),b.start(),b.endAt(),b.flareAt(),
                    b.offset(),b.offset(),b.endOffset(),false,b.capEnd()));
                continue;
            }
            if(baseEnd(b).distance(baseStart(a))<=tolerance&&tangentDot(b,a,b.flareAt(),a.start())>=.995){
                result.set(k,new FrogGeometry.WingRun(b.road(),b.start(),b.startFlareAt(),b.flareAt(),b.flareAt(),
                    b.startOffset(),b.offset(),b.offset(),b.capStart(),false));
                result.set(i,new FrogGeometry.WingRun(a.road(),a.start(),a.start(),a.endAt(),a.flareAt(),
                    a.offset(),a.offset(),a.endOffset(),false,a.capEnd()));
            }
        }
        return List.copyOf(result);
    }
    private static void appendChecks(List<Span> rails,List<GuardRails.Run> checks,double width,int lineBase){
        int id=lineBase;
        var merged=GuardRails.merge(checks);
        for(var run:merged)if(run.end()>run.start()+1e-7){
            for(var span:path(run,0,id++,width))rails.add(new Span(span.road,span.line,span.a,span.b,span.start,span.end,span.width,"wing",span.first,span.last));
        }
    }
    private static void bake(Mesh out,List<Span> rails,List<Span> channels,Profile p,PointSettings s){
        var railBins=bins(rails);var channelBins=bins(channels);
        // Custom models still describe one whole section; only the built-in profile splits.
        boolean wholeSection=p.detail()!=null&&!p.detail().rails().isEmpty();
        for(Span rail:rails){
            if(wholeSection){bakeLayer(out,rail,List.of(),rail.width,railBins,channelBins,p,s);continue;}
            // The built-in I profile is three independent beams with different widths, so the
            // exact rail seams must be solved per layer. A single whole-section cut uses the
            // widest layer and severs the narrow web's foot/head continuity, leaving a gap
            // the width of the foot along the crossing diagonal.
            double top=p.top()+s.verticalOffset(),base=top-p.railHeight();
            bakeLayer(out,rail,List.of(new BeamLayer(p.footWidth(),base,base+.025)),
                p.footWidth(),railBins,channelBins,p,s);
            bakeLayer(out,rail,List.of(new BeamLayer(.022,base+.025,top-.036)),
                .022,railBins,channelBins,p,s);
            bakeLayer(out,rail,List.of(new BeamLayer(p.headWidth(),top-.036,top)),
                p.headWidth(),railBins,channelBins,p,s);
        }
    }
    /** One beam of the built-in section, with the rail span it is cut against. */
    private record BeamLayer(double width,double bottom,double top){}
    /** Emit one section layer of a rail and cut it against the exact seams of the same layer.
     * {@code target} is empty for a whole custom section, which stays one beam and is trimmed
     * to the same section width it was built with. */
    private static void bakeLayer(Mesh out,Span rail,List<BeamLayer> target,double cutWidth,
                                  Map<Long,List<Span>> railBins,Map<Long,List<Span>> channelBins,
                                  Profile p,PointSettings s){
        Mesh section=new Mesh();
        if(target.isEmpty())section.rail(rail.a,rail.b,1,1,p,s,rail.part);
        else for(BeamLayer layer:target)section.beam(rail.a,rail.b,layer.width(),layer.width(),
            layer.bottom(),layer.top(),p.steel(),rail.part,-1);
        if((rail.part.equals("guard")||rail.part.equals("wing"))&&rail.first)section.railCap(rail.a,rail.b.sub(rail.a),1,p,s,rail.part,true);
        if((rail.part.equals("guard")||rail.part.equals("wing"))&&rail.last)section.railCap(rail.b,rail.b.sub(rail.a),1,p,s,rail.part,false);
        if(!target.isEmpty()){
            // Generic beams have end caps; retain only the exposed ends of each run.
            for(int i=section.quads.size()-1;i>=0;i--)if((i%6==4&&!rail.first)||(i%6==5&&!rail.last))section.quads.remove(i);
        }
        section=miter(section,rail);
        Span cut=rail.withWidth(cutWidth);
        // Voronoi seams join equal native cross sections into a solid V, without
        // double top faces or tapering each incoming rail before the heads merge.
        for(Span other:nearby(rail,railBins))if(rail.line!=other.line&&rail.near(other)){
            if(rail.part.equals("guard")){
                Plane level=rail.level(0);
                if(Math.abs(level.n.dot(other.a)-level.d)>.005||Math.abs(level.n.dot(other.b)-level.d)>.005)continue;
            }
            Span mate=other.withWidth(cut.width);
            V3 a=cut.normal(),b=mate.normal();double da=a.dot(cut.a),db=b.dot(mate.a);
            if(Math.abs(Math.abs(a.dot(b))-1)<1e-10&&Math.abs(da-db*Math.signum(a.dot(b)))<1e-8){
                // Equal-distance seams need one owner; subtracting both deletes steel.
                if(other.line<rail.line)section=cutRailSeam(section,mate.channel(),rail,target,p,s);
                continue;
            }
            section=cutRailSeam(section,List.of(mate.start,mate.end,new Plane(b.sub(a),db-da),new Plane(b.mul(-1).sub(a),-db-da)),rail,target,p,s);
            section=cutRailSeam(section,List.of(mate.start,mate.end,new Plane(b.add(a),db+da),new Plane(a.sub(b),da-db)),rail,target,p,s);
        }
        // Exact oblique cuts, not whole .24 m chunks. The cut walls make the noses
        // solid; rail feet remain below the wheel flange clearance depth.
        for(Span channel:nearby(rail,channelBins))if(rail.near(channel)){
            var planes=new ArrayList<Plane>(channel.channel());
            planes.add(rail.level(p.top()+s.verticalOffset()-.055).reverse());
            if(target.isEmpty())section=subtract(section,planes,true,rail.level(p.top()+s.verticalOffset()));
            else {
                BeamLayer layer=target.get(0);
                section=subtract(section,planes,true,rail.level(layer.top()),layer.top()-layer.bottom());
            }
        }
        out.quads.addAll(section.quads);
    }

    /** Rail-on-rail seams are full-depth cuts. Built-in rails are solved one beam at a time,
     * so each exposed wall occupies exactly the foot, web or head band instead of a single
     * rectangular plate which fills both I-section recesses. */
    private static Mesh cutRailSeam(Mesh section,List<Plane> planes,Span rail,List<BeamLayer> target,Profile p,PointSettings s){
        if(target.isEmpty())return subtract(section,planes,true,rail.level(p.top()+s.verticalOffset()),p.railHeight());
        BeamLayer layer=target.get(0);
        return subtract(section,planes,true,rail.level(layer.top()),layer.top()-layer.bottom());
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
    private static List<Span> wingPath(FrogGeometry.WingRun wing,int road,int line,double width,String part){
        var points=new ArrayList<V3>();
        addWingPoints(points,wing,wing.start(),wing.startFlareAt(),wing.startOffset(),wing.offset(),false);
        addWingPoints(points,wing,wing.startFlareAt(),wing.endAt(),wing.offset(),wing.offset(),!points.isEmpty());
        addWingPoints(points,wing,wing.endAt(),wing.flareAt(),wing.offset(),wing.endOffset(),!points.isEmpty());
        return spans(points,road,line,width,part,wing.capStart(),wing.capEnd());
    }
    private static void addWingPoints(List<V3> points,FrogGeometry.WingRun wing,double start,double end,double firstOffset,double lastOffset,boolean skipFirst){
        if(end<start+1e-10)return;
        int count=12;
        for(int i=skipFirst?1:0;i<=count;i++){
            double t=(double)i/count,d=start+(end-start)*t,offset=firstOffset+(lastOffset-firstOffset)*t;
            points.add(wing.road().at(d).add(wing.road().tangent(d).lateral().mul(offset)));
        }
    }
    private static List<Span> spans(List<V3> points,int road,int line,double width,String part){
        return spans(points,road,line,width,part,true,true);
    }
    private static List<Span> spans(List<V3> points,int road,int line,double width,String part,boolean first,boolean last){
        int count=points.size()-1;var result=new ArrayList<Span>();
        for(int i=0;i<count;i++){
            V3 a=points.get(i),b=points.get(i+1),u=horizontal(b.sub(a));
            V3 before=i==0?u:horizontal(a.sub(points.get(i-1))).add(u).unit();
            V3 after=i==count-1?u:horizontal(points.get(i+2).sub(b)).add(u).unit();
            result.add(new Span(road,line,a,b,new Plane(before.mul(-1),a),new Plane(after,b),width,part,first&&i==0,last&&i==count-1));
        }
        return result;
    }
    private static double sectionWidth(Profile p){
        double width=Math.max(p.footWidth(),p.headWidth());
        if(p.detail()!=null)for(var q:p.detail().rails())for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))
            width=Math.max(width,2*Math.abs(v.x()-p.detail().railCenter())*p.headWidth()/p.detail().headWidth());
        return width;
    }
    private static V3 horizontal(V3 v){return new V3(v.x(),0,v.z()).unit();}
    private static Mesh miter(Mesh source,Span s){
        Mesh out=new Mesh();V3 u=s.b.sub(s.a);double length=u.dot(u);
        for(var q:source.quads){var vertices=new ArrayList<V3>();
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){
                Plane end=v.sub(s.a).dot(u)/length<.5?s.start:s.end;
                vertices.add(v.add(u.mul((end.d-end.n.dot(v))/end.n.dot(u))));
            }
            out.quad(new Mesh.Quad(vertices.get(0),vertices.get(1),vertices.get(2),vertices.get(3),q.surface(),q.part(),q.index(),q.uv(),q.rail()));
        }
        return out;
    }
    /** Subtract a convex prism, keeping disjoint outside pieces and their original UVs. */
    private static Mesh subtract(Mesh source,List<Plane> planes,boolean walls,Plane top){
        return subtract(source,planes,walls,top,.055);
    }
    private static Mesh subtract(Mesh source,List<Plane> planes,boolean walls,Plane top,double wallDepth){
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
                    Mesh cut=new Mesh();Mesh.clip(cut,q,origin,plane.n.mul(-1));cap(cut,plane,top,wallDepth);piece.quads.addAll(cut.quads);
                }else Mesh.clip(piece,q,origin,plane.n.mul(-1));
                Mesh.clip(next,q,origin,plane.n);
            }
            outside.quads.addAll(piece.quads);inside=next;
            if(inside.quads.isEmpty())break;
        }
        return outside;
    }
    private static void cap(Mesh mesh,Plane plane,Plane top,double depth){
        var caps=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            var vertices=List.of(q.a(),q.b(),q.c(),q.d());
            if(vertices.stream().anyMatch(v->Math.abs(top.n.dot(v)-top.d)>.00001))continue;
            for(int k=0;k<4;k++){
                V3 a=vertices.get(k),b=vertices.get((k+1)%4);
                if(a.distance(b)<1e-8||Math.abs(plane.n.dot(a)-plane.d)>1e-8||Math.abs(plane.n.dot(b)-plane.d)>1e-8)continue;
                caps.add(new Mesh.Quad(b,a,a.add(0,-depth,0),b.add(0,-depth,0),q.surface(),"frog_wall",-1,q.uv()));
            }
        }
        mesh.quads.addAll(caps);
    }
}
