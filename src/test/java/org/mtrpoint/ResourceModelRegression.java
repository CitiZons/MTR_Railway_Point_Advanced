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
        String model=descriptor.get("model").getAsString();var loaded=ProfileModel.read(descriptor,model,"",true,reader);var detail=loaded.detail();
        StockFittingRegression.run(detail);
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
