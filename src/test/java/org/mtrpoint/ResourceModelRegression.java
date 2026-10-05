package org.mtrpoint;

import com.google.gson.*;
import org.mtrpoint.geometry.*;
import java.nio.file.*;
import java.util.*;

final class ResourceModelRegression {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run()throws Exception{
        banking();
        continuous();
        Path pack=packAssets();
        if(pack==null){System.out.println("RESOURCE_MODELS: SKIPPED external Citizons Railway pack not found; set CITIZONS_RAILWAY_PACK to its directory");return;}
        JsonObject descriptor=JsonParser.parseString(Files.readString(pack.resolve("citizons_railway/rail_profiles/citizons_mainline_1435.json"))).getAsJsonObject();
        ObjTemplate.Reader reader=id->{String[] parts=id.split(":",2);return Files.readString(pack.resolve(parts[0]).resolve(parts[1]));};
        continuousSupports(pack,reader);
        slabSeats(pack,reader);
        String model=descriptor.get("model").getAsString();var loaded=ProfileModel.read(descriptor,model,"",true,reader);var detail=loaded.detail();
        StockFittingRegression.run(detail);
        TurnoutOverlapRegression.run(detail);
        require(detail.rails().size()==28&&!detail.nativeAtlas(),"Custom section did not load its detailed profile");
        require(!loaded.attachments().isEmpty()&&!loaded.supports().isEmpty(),"Ballast/support roles missing");
        require(detail.bearers().size()>100&&detail.fittings().size()>300,"Detailed supports did not load");
        var fittingLods=new ArrayList<List<Mesh.Quad>>();
        for(String name:List.of("mid","far")){
            String lod=descriptor.getAsJsonObject("lod").getAsJsonObject(name).get("model").getAsString();JsonObject variant=descriptor.deepCopy();variant.addProperty("model",lod);
            var lower=ProfileModel.read(variant,lod,"",true,reader);
            require(lower.detail().rails().equals(detail.rails()),"LOD switch changes the steel section");
            require(lower.detail().fittings().size()<detail.fittings().size()*.2,"Distant fittings are still too dense");
            fittingLods.add(lower.detail().fittings());
        }
        SupportGeometryRegression.run(detail,fittingLods.get(0),fittingLods.get(1));
        for(var q:loaded.attachments()){require(q.uv()!=null&&q.uv().size()==8,"Attachment UV discarded");
            for(int i=0;i<8;i+=2)require(q.uv().get(i)>=.67&&q.uv().get(i+1)>=.507,"Ballast sampled outside atlas region");}
        var p=new Profile(1.435,.26428,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"test",true,detail);var mesh=new Mesh();
        detail.rail(mesh,V3.ZERO,new V3(0,0,.6),1,1,p,PointSettings.DEFAULT,"rail");
        require(mesh.quads.get(0).uv().get(2).equals(detail.rails().get(0).uv().get(6)),"Swept UV winding lost");
        mesh.railCap(V3.ZERO,new V3(0,0,1),1,p,PointSettings.DEFAULT,"cap",true);
        require(mesh.quads.stream().filter(q->q.part().equals("cap")).allMatch(q->q.surface().texture().equals(descriptor.get("steelEndTexture").getAsString())),"Custom cut material lost");
        require(Math.abs(p.centerOffset()+p.sleeperOverhang(PointSettings.DEFAULT)-1.25)<1e-8,"Turnout sleeper differs from ordinary width");
        JsonObject another=descriptor.deepCopy();another.addProperty("model",model.replace("citizons_railway:","another_series:"));
        require(ProfileModel.read(another,another.get("model").getAsString(),"",true,id->reader.read(id.replace("another_series:","citizons_railway:"))).detail().rails().size()==28,"New namespace requires hardcoding");
        for(double length:new double[]{.2,.6,1,7,10,17,31.4})for(double phase:new double[]{-.25,-.05,0,.25}){
            var centers=new ArrayList<Double>();for(double d=.3+phase;d<length+.3;d+=.6)centers.add(d);
            double[] positions=SleeperLayout.fit(centers,length,.6);double last=-1;
            for(double d:positions)if(Double.isFinite(d)){double margin=Math.min(.3,length/2);require(d>=margin-1e-8&&d<=length-margin+1e-8,"Endpoint support overrun");
                if(last>=0)require(d-last>=.45-1e-8,"Supports still crowded");last=d;}
        }
        double[] stable=SleeperLayout.fit(List.of(.3,.9,1.5,2.1),2.4,.6);require(Arrays.equals(stable,new double[]{.3,.9,1.5,2.1}),"Regular phase moved unnecessarily");
        System.out.println("RESOURCE_MODELS: PASS model roles, UVs, end material, namespace reuse, endpoint clearance");
    }
    private static Path packAssets(){
        String configured=System.getenv("CITIZONS_RAILWAY_PACK");
        if(configured!=null&&!configured.isBlank()){
            Path assets=Path.of(configured).resolve("assets");
            require(Files.isDirectory(assets),"CITIZONS_RAILWAY_PACK must point to a resource pack directory containing assets: "+configured);
            return assets;
        }
        for(String root:List.of("../MTR_Citizons_Railway/resourcepacks/Citizons_Railway")){
            Path assets=Path.of(root).resolve("assets");if(Files.isDirectory(assets))return assets;
        }
        return null;
    }
    private static void continuousSupports(Path pack,ObjTemplate.Reader reader)throws Exception{
        JsonObject descriptor=JsonParser.parseString(Files.readString(pack.resolve("citizons_railway/rail_profiles/citizons_direct_1435.json"))).getAsJsonObject();
        for(String level:List.of("near","mid","far")){
            String model=descriptor.getAsJsonObject("lod").getAsJsonObject(level).get("model").getAsString();
            JsonObject variant=descriptor.deepCopy();variant.addProperty("model",model);
            var loaded=ProfileModel.read(variant,model,"",true,reader);
            require(!loaded.attachments().isEmpty()&&loaded.attachments().stream().allMatch(q->q.part().equals("track_bed")),"Continuous strips must survive turnout suppression");
            require(loaded.supports().stream().noneMatch(q->q.part().equals("direct_bearer")),"Continuous strips must not move with sleeper layout");
            var detail=loaded.detail();var p=new Profile(1.435,.26428,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"direct",true,detail);
            Mesh rows=new Mesh();detail.bearer(rows,V3.ZERO,new V3(1,0,0),-2,4,PointSettings.DEFAULT,p,0,true);
            require(rows.quads.isEmpty(),"Turnout must not stretch longitudinal strips into transverse ties");
            Mesh guardSupport=new Mesh();TurnoutFittings.guardPair(guardSupport,V3.ZERO,new V3(.22,0,0),new V3(1,0,0),p,PointSettings.DEFAULT,0);
            var concrete=guardSupport.quads.stream().filter(q->q.part().equals("track_bed")).toList();
            require(!concrete.isEmpty(),"Guard support concrete missing");
            double maxY=concrete.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).max().orElseThrow();
            require(Math.abs(maxY-detail.bearerTop())<1e-9,"Guard concrete has a different top height from the strip");
            for(var q:concrete)require(detail.bearers().stream().anyMatch(original->original.surface().equals(q.surface())&&original.uv().equals(q.uv())),"Guard concrete lost the original texture/UV");
            var a=new RailSweep(V3.ZERO,new V3(0,0,.6),new V3(0,0,1),new V3(.1,0,1).unit(),0,0);
            var b=new RailSweep(a.b(),new V3(.1,0,1.2),a.tangentB(),new V3(.2,0,1).unit(),0,0);
            for(double x:new double[]{-.7515-.261,-.7515+.261,.7515-.261,.7515+.261})
                require(a.model(new V3(x,detail.bearerTop(),.3),false,0,-.3,.3).distance(b.model(new V3(x,detail.bearerTop(),-.3),false,0,-.3,.3))<1e-9,"Curved support cells must meet");
        }
        System.out.println("CONTINUOUS_SUPPORTS: PASS all LODs preserve strips, omit transverse ties and join curved cells");
    }
    private static void slabSeats(Path pack,ObjTemplate.Reader reader)throws Exception{
        var profiles=new ArrayList<Profile>();
        for(String name:List.of("mainline","slab","direct")){
            var descriptor=JsonParser.parseString(Files.readString(pack.resolve("citizons_railway/rail_profiles/citizons_"+name+"_1435.json"))).getAsJsonObject();
            var detail=ProfileModel.read(descriptor,descriptor.get("model").getAsString(),"",true,reader).detail();
            profiles.add(new Profile(1.435,.26428,.068,.14,.165,Profile.STEEL,Profile.TIMBER,name,true,detail));
        }
        List<Mesh.Quad> expected=null;
        Track road=new Track("guard-seats","a","b",List.of(V3.ZERO,new V3(0,0,10)));
        for(Profile p:profiles){
            double offset=p.centerOffset()-p.headWidth()-.04;
            var guard=new GuardRails.Run(road,1,9,offset,false,false,p,PointSettings.DEFAULT);
            V3 row=road.at(5),n=road.tangent(5).lateral(),running=row.add(n.mul(p.centerOffset())),check=guard.point(5);
            var seats=new FittingSeats();seats.add(running,n,true);seats.add(check,n,false);
            Mesh actual=new Mesh();TurnoutFittings.guardsAtRow(actual,seats,List.of(guard,guard),row,n,p,PointSettings.DEFAULT,17);
            // A later road arm must not reintroduce a normal fastening on the common plate.
            seats.add(running,n,true);seats.add(check,n,false);seats.emitTurnout(actual,p,PointSettings.DEFAULT,17,"normal");
            Mesh one=new Mesh();TurnoutFittings.guardPair(one,running,check,n,p,PointSettings.DEFAULT,17);
            var hardware=actual.quads.stream().filter(q->q.part().startsWith("fastener")).toList();
            require(hardware.equals(one.quads.stream().filter(q->q.part().startsWith("fastener")).toList()),"Guard rows duplicate ordinary fittings or repeated guard brackets: "+p.source());
            if(expected==null)expected=hardware;else require(expected.equals(hardware),"Slab guard fittings differ from ballast in geometry, material or UV: "+p.source());
        }
        Profile slab=profiles.get(1);ModelDetail detail=slab.detail();
        Junction parityJunction=Detector.find(Regression.y().subList(0,2)).get(0);
        for(int mode=0;mode<=4;mode++){
            Set<Mesh.Quad> reference=null;
            for(Profile p:profiles){
                Mesh full=PointMesh.build(parityJunction,PointSettings.DEFAULT.with(16,mode),p,0);
                Set<Mesh.Quad> hardware=new HashSet<>(full.quads.stream().filter(q->q.part().startsWith("fastener")).toList());
                if(reference==null)reference=hardware;
                else require(reference.equals(hardware),"Full turnout fitting mismatch for "+p.source()+" mode="+mode+" reference="+reference.size()+" actual="+hardware.size());
            }
            System.out.println("TURNOUT_FITTING_PARITY: PASS mode="+mode+" faces="+reference.size()+" mainline/slab/direct");
        }
        Mesh stretched=new Mesh();detail.bearer(stretched,V3.ZERO,new V3(1,0,0),-2,5,PointSettings.DEFAULT,slab,0,true);
        require(stretched.quads.isEmpty(),"Slab turnout still stretches twin blocks as a long bearer");
        var seats=new FittingSeats();V3 n=new V3(.8,0,.6),along=new V3(n.z(),0,-n.x());
        var centers=List.of(n.mul(-1.8),n.mul(-.65),n.mul(.75),n.mul(3.2));
        for(var center:centers)seats.add(center,n,false);
        Mesh placed=new Mesh();seats.emitTurnout(placed,slab,PointSettings.DEFAULT,23,"normal");
        var blocks=placed.quads.stream().filter(q->q.part().equals("sleeper")).toList();
        int faces=(int)detail.bearers().stream().filter(q->q.center().x()>0).count();
        require(blocks.size()==faces*centers.size(),"Each fitting seat must have one complete block");
        for(int i=0;i<centers.size();i++){
            V3 center=centers.get(i);var vertices=blocks.subList(i*faces,(i+1)*faces).stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).toList();
            double lo=vertices.stream().mapToDouble(v->v.sub(center).dot(n)).min().orElseThrow(),hi=vertices.stream().mapToDouble(v->v.sub(center).dot(n)).max().orElseThrow();
            double zlo=vertices.stream().mapToDouble(v->v.sub(center).dot(along)).min().orElseThrow(),zhi=vertices.stream().mapToDouble(v->v.sub(center).dot(along)).max().orElseThrow();
            require(Math.abs(lo+.34)<1e-8&&Math.abs(hi-.34)<1e-8,"Slab shoulders drift from their actual fitting seat");
            require(Math.abs(zlo+.107)<1e-8&&Math.abs(zhi-.107)<1e-8,"Slab shoulder length differs from the fastening");
        }
        System.out.println("SLAB_SEATS: PASS identical guard hardware for all beds, replacement/deduplication, complete blocks aligned to four diverging fitting seats");
    }
    private static void continuous(){
        int points=0;double worst=0;
        for(boolean flip:new boolean[]{false,true})for(double offset:new double[]{0,.3}){
            RailSweep previous=null;
            for(int i=0;i<60;i++){
                double t=i*.57,u=(i+1)*.57;
                V3 a=new V3(.015*t*t,64+.003*t*t,t),b=new V3(.015*u*u,64+.003*u*u,u);
                RailSweep next=new RailSweep(a,b,new V3(.03*t,.006*t,1).unit(),new V3(.03*u,.006*u,1).unit(),Math.toRadians(-18+t),Math.toRadians(-18+u));
                if(previous!=null)for(double x:new double[]{-1.85,-.7515,0,.7515,1.85})for(double y:new double[]{-.304,.026,.085422,.26428}){
                    V3 end=previous.model(new V3(x,y,flip?-.3:.3),flip,offset,-.3,.3),start=next.model(new V3(x,y,flip?.3:-.3),flip,offset,-.3,.3);
                    worst=Math.max(worst,end.distance(start));require(end.distance(start)<1e-9,"Continuous rail / ballast cell opened on changing grade or cant");points++;
                }
                require(next.model(new V3(.7515,.1,0),flip,offset,-.3,.3).distance(next.rigid(new V3(.7515,.1,0),flip,offset))<1e-9,"Rigid support centre separated from sweep");previous=next;
            }
        }
        System.out.println("RESOURCE_CONTINUITY: PASS changing curve, grade, cant, reversed cells and offsets; points="+points+" maxGap="+worst);
    }
    private static void banking(){
        int cases=0;double worst=0;
        for(double cant:new double[]{-25,-8,0,8,25})for(boolean flip:new boolean[]{false,true})
        for(double grade:new double[]{-.2,0,.2})for(double offset:new double[]{-.15,0,.3}){
            V3 a=new V3(3.4,67,-2.1),b=a.add(.36,grade*.6,.48),f=b.sub(a).unit(),right=f.lateral().mul(-1);double sign=flip?-1:1;
            var frame=new RailCellTransform(a,b,flip,offset,Math.toRadians(cant));
            // Independently compose the actual MTR render stack, Optional Rail pivot and OBJ-loader rotation.
            var matrix=new org.joml.Matrix4d().translation(a.lerp(b,.5).x(),a.lerp(b,.5).y()+offset,a.lerp(b,.5).z())
                .rotateY(Math.PI/2-Math.atan2(b.z()-a.z(),b.x()-a.x())+(flip?Math.PI:0))
                .rotateX(Math.PI-Math.atan2(b.y()-a.y(),Math.hypot(b.x()-a.x(),b.z()-a.z()))*sign)
                .rotateZ(Math.toRadians((float)((a.x()*a.z())%10)/100F))
                .translate(0,offset,0).rotateZ(-Math.toRadians(cant)*sign).translate(0,-offset,0).rotateX(Math.PI);
            for(double x:new double[]{-1.85,-.7515,0,.7515,1.85})for(double y:new double[]{-.304,.026,.085422,.26428})for(double z:new double[]{-.24,0,.24}){
                V3 local=new V3(x,y,z),actual=frame.model(local);var expected=matrix.transformPosition(new org.joml.Vector3d(x,y,z));
                double error=actual.distance(new V3(expected.x,expected.y,expected.z));worst=Math.max(worst,error);require(error<1e-10,"Attachment differs from native OBJ matrix");
                V3 unbanked=a.lerp(b,.5).add(right.mul(x*sign)).add(f.mul(z*sign)).add(0,y+offset,0);
                require(frame.world(unbanked).distance(actual)<1e-10,"Turnout and ballast rotate about different axes");cases++;
            }
            // A rigid transform keeps the 350 mm cross-section thickness and all layer clearances.
            require(Math.abs(frame.model(new V3(1.32,.046,0)).distance(frame.model(new V3(1.32,-.304,0)))-.35)<1e-10,"Banking changes ballast thickness");
        }
        System.out.println("RESOURCE_BANKING: PASS native matrix, shared pivot, +/- cant, reversed style, grade, offsets; points="+cases+" maxError="+worst);
    }
}
