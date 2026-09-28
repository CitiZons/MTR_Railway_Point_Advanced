package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Geometric acceptance checks for custom-model turnout bearers and fittings. */
final class SupportGeometryRegression {
    private static final Set<String> SUPPORT=Set.of("sleeper","fastener","fastener_near","fastener_mid","fastener_far");
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    static void run(ModelDetail high,List<Mesh.Quad> mid,List<Mesh.Quad> far){
        ModelDetail detail=high.withFittingLods(List.of(mid,far));
        Profile profile=new Profile(1.435,.26428,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"support-regression",true,detail);
        PointSettings settings=PointSettings.DEFAULT;
        ordinaryContract(detail,profile,settings);
        Junction y=Detector.find(Regression.y()).get(0);
        animationAndCoverage(y,profile,settings);
        fittings(y,profile,settings);
        for(int mode:new int[]{0,4})wingBrackets(y,profile,settings.with(16,mode));
        edits(y,profile,settings);
        threeWay(profile,settings);
        System.out.println("RESOURCE_SUPPORTS: PASS flat multi-track bearers, stationary slide beds, shared crossing/guard bases, LODs, edits and bounded detail");
    }

    private static void ordinaryContract(ModelDetail detail,Profile profile,PointSettings settings){
        V3 c=new V3(2,67,3),n=new V3(1,0,0);Mesh legacy=new Mesh(),explicit=new Mesh(),turnout=new Mesh();
        detail.bearer(legacy,c,n,-1.25,1.25,settings,profile,7);
        detail.bearer(explicit,c,n,-1.25,1.25,settings,profile,7,false);
        detail.bearer(turnout,c,n,-1.25,1.25,settings,profile,7,true);
        require(legacy.quads.equals(explicit.quads),"Ordinary custom bearer changed while adding turnout tops");
        double top=c.y()+profile.top()-detail.railTop()+detail.bearerTop()+settings.verticalOffset();
        require(Math.abs(minY(legacy)-minY(turnout))<1e-9,"Flattening the turnout bearer moved its underside");
        verifyTurnoutDeformation(detail,turnout,top,settings);
        require(turnout.quads.stream().allMatch(q->q.uv()!=null&&q.uv().size()==8&&q.uv().stream().allMatch(Float::isFinite)),"Flattened bearer has invalid material UVs");
        Mesh fitting=new Mesh();detail.fitting(fitting,c,n,settings,profile,7);
        for(String part:List.of("fastener_near","fastener_mid","fastener_far"))require(fitting.quads.stream().anyMatch(q->q.part().equals(part)),"Missing custom fitting LOD: "+part);
    }

    private static void verifyTurnoutDeformation(ModelDetail detail,Mesh turnout,double top,PointSettings settings){
        double bottom=detail.bearers().stream().flatMap(q->vertices(q).stream()).mapToDouble(V3::y).min().orElseThrow();
        var ringTop=new HashMap<Long,Double>();for(var q:detail.bearers())for(V3 v:vertices(q))ringTop.merge(Math.round(v.x()*10000),v.y(),Math::max);
        require(ringTop.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow()-ringTop.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow()>.03,"Fixture no longer contains the ordinary model's centre sag");
        var shared=new HashMap<V3,Double>();int bottomCount=0,topCount=0;
        require(turnout.quads.size()==detail.bearers().size(),"Turnout bearer changed the source face count");
        for(int i=0;i<detail.bearers().size();i++){
            var source=vertices(detail.bearers().get(i));var emitted=vertices(turnout.quads.get(i));
            // emit reverses winding from a,b,c,d to a,d,c,b.
            for(int j=0;j<4;j++){V3 v=source.get(new int[]{0,3,2,1}[j]);double ring=ringTop.get(Math.round(v.x()*10000));
                double actual=emitted.get(j).y();
                Double previous=shared.putIfAbsent(v,actual);require(previous==null||Math.abs(previous-actual)<1e-9,"Shared bearer vertex mapped to inconsistent heights");
                if(Math.abs(v.y()-bottom)<1e-9){require(Math.abs(actual-(top+(bottom-detail.bearerTop())*settings.sleeperHeight()/.12))<1e-9,"Source bottom vertex was lifted");bottomCount++;}
                if(Math.abs(v.y()-ring)<1e-9){require(Math.abs(actual-top)<1e-9,"X-ring top vertex is not on the flat turnout top");topCount++;}
                require(actual>=top+(bottom-detail.bearerTop())*settings.sleeperHeight()/.12-1e-9&&actual<=top+1e-9,"Turnout bearer bevel escaped the source bottom/top bounds");
            }
        }
        require(bottomCount>0&&topCount>0,"Fixture did not exercise bottom and top bearer vertices");
    }

    private static void animationAndCoverage(Junction y,Profile profile,PointSettings settings){
        for(int mode:new int[]{0,4}){
            PointSettings pattern=settings.with(16,mode);var meshes=new ArrayList<Mesh>();
            for(double pose:new double[]{0,.5,1})meshes.add(PointMesh.build(y,pattern,profile,pose));
            List<Mesh.Quad> fixed=support(meshes.get(0));
            require(fixed.equals(support(meshes.get(1)))&&fixed.equals(support(meshes.get(2))),"Support geometry moved with the switch blades (mode "+mode+")");
            long rows=fixed.stream().filter(q->q.part().equals("sleeper")).map(Mesh.Quad::index).distinct().count();
            int sourcePerRow=profile.detail().bearers().size()+6*(profile.detail().fittings().size()+profile.detail().fittingLods().stream().mapToInt(List::size).sum())+96;
            require(fixed.size()>=rows*profile.detail().bearers().size()&&fixed.size()<=rows*sourcePerRow,"Custom turnout support complexity escaped its source-relative bound: faces="+fixed.size()+" rows="+rows);
            coverEverySeat(y,meshes.get(1),profile,pattern);
        }
        var mirrored=Regression.y().stream().map(t->new Track("mirror-"+t.id,t.startNode,t.endNode,t.points.stream().map(v->new V3(-v.x(),v.y(),v.z())).toList())).toList();
        Mesh mirror=PointMesh.build(Detector.find(mirrored).get(0),settings,profile,.5);
        require(!support(mirror).isEmpty(),"Mirrored turnout lost custom supports");
        var graded=Regression.y().stream().map(t->new Track("grade-"+t.id,t.startNode,t.endNode,t.points.stream().map(v->new V3(v.x(),67+v.z()*.025,v.z())).toList())).toList();
        Mesh grade=PointMesh.build(Detector.find(graded).get(0),settings,profile,.5);
        require(support(grade).stream().allMatch(SupportGeometryRegression::finite),"Graded turnout emitted invalid support geometry");
        require(support(grade).stream().mapToDouble(SupportGeometryRegression::minY).min().orElseThrow()>66,"Graded supports dropped toward world zero");
    }

    private static void coverEverySeat(Junction y,Mesh mesh,Profile profile,PointSettings settings){
        double top=profile.top()-profile.detail().railTop()+profile.detail().bearerTop()+settings.verticalOffset();var coveredRoads=new HashSet<Track>();
        var rows=mesh.quads.stream().filter(q->q.part().equals("sleeper")).map(Mesh.Quad::index).filter(i->i>=0).distinct().toList();
        require(!rows.isEmpty()&&rows.size()==Collections.max(rows)+1,"Unedited turnout has a missing bearer row index: "+rows);
        for(int index:rows){var rowFaces=mesh.quads.stream().filter(q->q.part().equals("sleeper")&&q.index()==index).toList();var tops=topFaces(rowFaces,top);if(tops.isEmpty())continue;
            for(var face:tops)for(Track road:y.tracks())for(int sign:new int[]{-1,1}){
                double at=road.nearest(face.center());V3 axis=bearerAxis(face,road.tangent(at)),forward=axis.lateral();
                for(int k=0;k<6;k++){V3 tangent=road.tangent(at),seat=road.at(at).add(tangent.lateral().mul(sign*profile.centerOffset()));double denominator=tangent.dot(forward);if(Math.abs(denominator)<.1)break;at=Math.max(0,Math.min(road.length,at-seat.sub(face.center()).dot(forward)/denominator));}
                V3 tangent=road.tangent(at),seat=road.at(at).add(tangent.lateral().mul(sign*profile.centerOffset()));
                double along=seat.sub(face.center()).dot(axis),across=seat.sub(face.center()).dot(forward);
                double alongLimit=vertices(face).stream().mapToDouble(v->Math.abs(v.sub(face.center()).dot(axis))).max().orElseThrow(),acrossLimit=vertices(face).stream().mapToDouble(v->Math.abs(v.sub(face.center()).dot(forward))).max().orElseThrow();
                if(Math.abs(across)>acrossLimit+1e-5||Math.abs(along)>alongLimit+1e-5)continue;
                require(tops.stream().anyMatch(q->coversXZ(q,seat)),"Bearer row "+index+" misses an actual running rail seat at "+seat);coveredRoads.add(road);
            }
        }
        require(coveredRoads.containsAll(y.tracks()),"Turnout bearer coverage did not exercise every junction arm: covered="+coveredRoads.size()+" roads="+y.tracks().size());
    }

    private static void fittings(Junction y,Profile profile,PointSettings settings){
        Mesh mesh=PointMesh.build(y,settings,profile,.5);double bearer=profile.top()-profile.detail().railTop()+profile.detail().bearerTop()+settings.verticalOffset();
        var near=mesh.quads.stream().filter(q->q.part().equals("fastener_near")).toList();
        require(!near.isEmpty(),"Turnout has no near fitting geometry");
        long wide=near.stream().filter(q->horizontal(q)&&planarSpan(q)>.35).count();require(wide>=8,"No shared slide/crossing bases bridge adjacent rail seats");
        double railFoot=profile.top()-profile.detail().railTop()+profile.detail().rails().stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElseThrow()+settings.verticalOffset();
        require(near.stream().filter(q->horizontal(q)&&planarSpan(q)>.35).allMatch(q->Math.abs(q.center().y()-bearer)<1e-6||Math.abs(q.center().y()-railFoot)<1e-5),"A shared plate floats above its bearer or fails to reach the model rail foot");
        long ribs=near.stream().filter(q->maxY(q)>bearer+.08&&planarSpan(q)<.14).count();require(ribs>=12,"Guard brackets have no exposed narrow ribs");
        bladeBeds(y,profile,settings,bearer,railFoot);
        require(mesh.quads.stream().anyMatch(q->q.part().equals("fastener_mid"))&&mesh.quads.stream().anyMatch(q->q.part().equals("fastener_far")),"Turnout dropped distant fitting LODs");
    }

    private static void bladeBeds(Junction y,Profile profile,PointSettings settings,double bearer,double railFoot){
        double extent=PointMesh.extent(y,settings),start=TurnoutFrame.start(y,extent);FrogGeometry frog=new FrogGeometry(y,settings,profile,extent);
        double crossing=(frog.sa+frog.sb)/2,length=settings.bladeLength()>0?settings.bladeLength():Math.max(2,Math.min((crossing-start)*.65,9));int rows=0,probes=0;
        for(double pose:new double[]{0,.5,1}){Mesh mesh=PointMesh.build(y,settings,profile,pose);var plates=mesh.quads.stream().filter(q->q.part().equals("fastener_near")&&horizontal(q)&&Math.abs(q.center().y()-railFoot)<1e-5&&planarSpan(q)>.35)
                .filter(q->{double at=y.a().nearest(q.center());return at>=start-.2&&at<=start+length+.2;}).toList();require(!plates.isEmpty(),"Blade zone has no stationary slide-bed tops");if(pose==0)rows=plates.size();
            var blades=mesh.quads.stream().filter(q->q.part().equals("blade")).toList();for(var plate:plates){
                V3 nearest=blades.stream().flatMap(q->bottomSegments(q,railFoot).stream()).map(s->closestXZ(plate.center(),s[0],s[1])).min(Comparator.comparingDouble(v->planarDistance(v,plate.center()))).orElseThrow();
                if(planarDistance(nearest,plate.center())>.15)continue;require(plates.stream().anyMatch(q->coversXZ(q,nearest)),"Slide bed does not support the blade foot at pose "+pose+" plate="+plate.index()+" distance="+planarDistance(nearest,plate.center()));
                boolean clipped=mesh.quads.stream().filter(q->SUPPORT.contains(q.part())&&horizontal(q)&&q.center().y()>railFoot+.005).anyMatch(q->coversXZ(q,nearest));
                require(!clipped,"A raised clip occupies the swept blade footprint at pose "+pose);probes++;
            }}
        require(rows>=3&&probes>=6,"Too few slide-bed rows exercised: rows="+rows+" probes="+probes);
    }

    private static void edits(Junction y,Profile profile,PointSettings settings){
        Mesh base=PointMesh.build(y,settings,profile,0),removed=PointMesh.build(y,settings.removeSleeper(3),profile,0),shifted=PointMesh.build(y,settings.sleeper(3,.17),profile,0);
        require(support(removed).stream().noneMatch(q->q.index()==3),"Deleted bearer retained fitting or plate geometry");
        require(!support(base).stream().filter(q->q.index()==3).toList().equals(support(shifted).stream().filter(q->q.index()==3).toList()),"Shifted bearer left its fittings behind");
        require(support(base).stream().filter(q->q.index()==4).toList().equals(support(shifted).stream().filter(q->q.index()==4).toList()),"Shifting one bearer changed the next support row");
    }

    private static void threeWay(Profile profile,PointSettings settings){
        var roads=new ArrayList<>(Regression.y().subList(0,2));roads.add(Regression.line("support-middle","0,0,0","0,0,30",new V3(0,0,0),new V3(0,0,30)));
        Junction three=Detector.find(roads).get(0);var supports=new ArrayList<List<Mesh.Quad>>();
        for(double pose:new double[]{0,.5,1})supports.add(support(PointMesh.build(three,settings,profile,pose)));
        require(supports.get(0).equals(supports.get(1))&&supports.get(1).equals(supports.get(2)),"Three-way supports move between blade poses");
        require(supports.get(0).stream().filter(q->q.part().equals("fastener_near")&&horizontal(q)&&planarSpan(q)>.35).count()>12,"Three-way lacks shared slide/frog plates");
        wingBrackets(three,profile,settings);
    }

    private static void wingBrackets(Junction junction,Profile profile,PointSettings settings){
        Mesh mesh=PointMesh.build(junction,settings,profile,.5);
        var wings=GuardRails.assembled(junction,settings,profile,null,null).stream().filter(r->r.part().equals("wing")).toList();
        require(!wings.isEmpty(),"Wing fixture has no check wings");
        double foot=profile.top()-profile.detail().railTop()+settings.verticalOffset()+profile.detail().rails().stream().flatMap(q->vertices(q).stream()).mapToDouble(V3::y).min().orElseThrow();
        for(var wing:wings)for(String lod:List.of("fastener_near","fastener_mid","fastener_far")){
            var rows=new HashSet<Integer>();
            for(var face:mesh.quads){
                if(!face.part().equals(lod)||Math.abs(maxY(face)-minY(face)-.064)>1e-6)continue;
                double at=wing.road().nearest(face.center());
                if(at<wing.start()+.05||at>wing.end()-.05)continue;
                V3 check=wing.point(at),running=wing.road().at(at).add(wing.road().tangent(at).lateral().mul(Math.signum(wing.offset())*profile.centerOffset()));
                V3 outward=check.sub(running).unit();double offset=face.center().sub(check).dot(outward);
                if(offset<.012||offset>.025||Math.abs(maxY(face)-check.y()-foot-.10)>1e-5)continue;
                require(mesh.quads.stream().filter(q->q.index()==face.index()&&q.part().equals(lod)&&horizontal(q)&&Math.abs(q.center().y()-check.y()-foot)<1e-5).anyMatch(q->coversXZ(q,check)),"Wing cheek has no plate beneath its foot: "+junction.kind()+" row="+face.index()+" "+lod);
                rows.add(face.index());
            }
            require(rows.size()>=2,"Wing lacks ribbed support rows: "+junction.kind()+" mode="+settings.sleeperMode()+" road="+wing.road().id+" "+lod+" rows="+rows);
        }
        System.out.println("WING_SUPPORTS: PASS "+junction.kind()+" mode="+settings.sleeperMode()+" wings="+wings.size()+" all three LODs");
    }

    private static List<Mesh.Quad> support(Mesh mesh){return mesh.quads.stream().filter(q->SUPPORT.contains(q.part())).toList();}
    private static List<Mesh.Quad> topFaces(Mesh mesh,double y){return topFaces(mesh.quads,y);}
    private static List<Mesh.Quad> topFaces(List<Mesh.Quad> quads,double y){return quads.stream().filter(SupportGeometryRegression::horizontal).filter(q->Math.abs(q.center().y()-y)<1e-6).toList();}
    private static boolean horizontal(Mesh.Quad q){return Math.abs(maxY(q)-minY(q))<1e-7;}
    private static double minY(Mesh.Quad q){return List.of(q.a(),q.b(),q.c(),q.d()).stream().mapToDouble(V3::y).min().orElseThrow();}
    private static double maxY(Mesh.Quad q){return List.of(q.a(),q.b(),q.c(),q.d()).stream().mapToDouble(V3::y).max().orElseThrow();}
    private static double minY(Mesh mesh){return mesh.quads.stream().mapToDouble(SupportGeometryRegression::minY).min().orElseThrow();}
    private static List<V3> vertices(Mesh.Quad q){return List.of(q.a(),q.b(),q.c(),q.d());}
    private static double planarSpan(Mesh.Quad q){double best=0;for(V3 a:List.of(q.a(),q.b(),q.c(),q.d()))for(V3 b:List.of(q.a(),q.b(),q.c(),q.d()))best=Math.max(best,Math.hypot(a.x()-b.x(),a.z()-b.z()));return best;}
    private static V3 bearerAxis(Mesh.Quad face,V3 railTangent){V3 best=V3.ZERO;double score=-1;var v=vertices(face);for(int i=0;i<4;i++){V3 edge=v.get((i+1)%4).sub(v.get(i)),flat=new V3(edge.x(),0,edge.z());if(flat.length()<1e-9)continue;V3 unit=flat.unit();double candidate=Math.abs(unit.dot(railTangent.lateral()));if(candidate>score){score=candidate;best=unit;}}return best;}
    private static boolean coversXZ(Mesh.Quad q,V3 p){var v=List.of(q.a(),q.b(),q.c(),q.d());double sign=0;for(int i=0;i<4;i++){V3 a=v.get(i),b=v.get((i+1)%4);double cross=(b.x()-a.x())*(p.z()-a.z())-(b.z()-a.z())*(p.x()-a.x());if(Math.abs(cross)<1e-7)continue;if(sign==0)sign=Math.signum(cross);else if(sign*cross<0)return false;}return true;}
    private static double planarDistance(V3 a,V3 b){return Math.hypot(a.x()-b.x(),a.z()-b.z());}
    private static List<V3[]> bottomSegments(Mesh.Quad q,double y){var v=List.of(q.a(),q.b(),q.c(),q.d());var out=new ArrayList<V3[]>();for(int i=0;i<4;i++){V3 a=v.get(i),b=v.get((i+1)%4);if(Math.abs(a.y()-y)<1e-4&&Math.abs(b.y()-y)<1e-4&&planarDistance(a,b)>1e-6)out.add(new V3[]{a,b});}return out;}
    private static V3 closestXZ(V3 p,V3 a,V3 b){V3 d=b.sub(a);double length=d.x()*d.x()+d.z()*d.z(),t=length<1e-12?0:Math.max(0,Math.min(1,((p.x()-a.x())*d.x()+(p.z()-a.z())*d.z())/length));return a.lerp(b,t);}
    private static boolean finite(Mesh.Quad q){return List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Double.isFinite(v.x()+v.y()+v.z()));}
}
