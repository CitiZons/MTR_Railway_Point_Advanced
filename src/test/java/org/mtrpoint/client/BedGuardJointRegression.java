package org.mtrpoint.client;

import com.google.gson.*;
import org.mtrpoint.geometry.*;
import java.nio.file.*;
import java.util.*;

/** Actual resource-cell seams, including ballast caps and descriptor-selected check steel. */
public final class BedGuardJointRegression {
    public static void run(Path assets,ObjTemplate.Reader reader)throws Exception{
        int probes=0;
        for(String style:List.of("mainline","center_guard","outer_guard","slab","direct")){
            JsonObject base=JsonParser.parseString(Files.readString(assets.resolve("citizons_railway/rail_profiles/citizons_"+style+"_1435.json"))).getAsJsonObject();
            for(String lod:List.of("near","mid","far")){
                JsonObject spec=base.deepCopy();String model=base.getAsJsonObject("lod").getAsJsonObject(lod).get("model").getAsString();spec.addProperty("model",model);
                ProfileModel loaded=ProfileModel.read(spec,model,"",true,reader);
                probes+=check(loaded.attachments(),style.equals("slab")||style.equals("direct")?"track_bed":"ballast",style+"/"+lod);
                if(loaded.continuousGuard()!=null)probes+=check(loaded.continuousGuard().rails(),"rail_native_guard",style+"/"+lod);
            }
        }
        System.out.println("BED_GUARD_JOINTS: PASS actual ballast, slab/direct concrete beds and inner/outer guard cells, three LODs; narrow seam probes="+probes+"; 40 mm gaps retained");
    }
    private static Mesh cells(List<Mesh.Quad> faces,double gap){
        double lo=faces.stream().flatMap(q->vertices(q).stream()).mapToDouble(V3::z).min().orElseThrow();
        double hi=faces.stream().flatMap(q->vertices(q).stream()).mapToDouble(V3::z).max().orElseThrow();
        Mesh mesh=new Mesh();
        for(double offset:new double[]{-hi,gap-lo})for(var q:faces)
            mesh.quad(new Mesh.Quad(q.a().add(0,0,offset),q.b().add(0,0,offset),q.c().add(0,0,offset),q.d().add(0,0,offset),q.surface(),RailCellCache.worldPart(q),-1,q.uv()));
        return mesh;
    }
    private static List<V3> vertices(Mesh.Quad q){return List.of(q.a(),q.b(),q.c(),q.d());}
    private static int check(List<Mesh.Quad> faces,String part,String label){
        faces=faces.stream().filter(q->q.part().equals(part)).toList();
        Mesh source=cells(faces,.019),joined=RailJoints.bridges(source,true);
        int probes=0;
        for(var q:source.quads)if(q.part().equals(part)){
            var vs=vertices(q);
            for(int i=0;i<4;i++){
                V3 a=vs.get(i),b=vs.get((i+1)%4);
                if(Math.abs(a.z())>1e-8||Math.abs(b.z())>1e-8||a.distance(b)<1e-7)continue;
                V3 probe=a.lerp(b,.5).add(0,0,.0095);
                if(joined.quads.stream().noneMatch(face->hit(face.a(),face.b(),face.c(),probe)))throw new AssertionError(label+" "+part+" seam remains open at "+probe);
                probes++;
            }
        }
        if(probes==0)throw new AssertionError("No seam probes: "+label+" "+part);
        if(!RailJoints.bridges(cells(faces,.04),true).quads.isEmpty())throw new AssertionError("Real gap filled: "+label);
        return probes;
    }
    private static boolean hit(V3 a,V3 b,V3 c,V3 p){
        V3 u=b.sub(a),v=c.sub(a),d=p.sub(a);
        double uu=u.dot(u),uv=u.dot(v),vv=v.dot(v),du=d.dot(u),dv=d.dot(v),den=uu*vv-uv*uv;
        if(den<1e-20)return false;
        double x=(du*vv-dv*uv)/den,y=(dv*uu-du*uv)/den;
        return x>=-1e-7&&y>=-1e-7&&x+y<=1+1e-7&&a.add(u.mul(x)).add(v.mul(y)).distance(p)<1e-7;
    }
}
