package org.mtrpoint.geometry;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

/** Explicit roles in an ordinary rail cell, reused by turnout sweeps and endpoint supports. */
public record ProfileModel(ModelDetail detail,List<Mesh.Quad> attachments,List<Mesh.Quad> fixed,List<Mesh.Quad> supports,boolean alignSleepers,ContinuousGuard continuousGuard) {
    public record ContinuousGuard(List<Mesh.Quad> rails,List<Mesh.Quad> supports,List<Mesh.Quad> endpoint,double length,double zMin,double zMax,double supportInset,double noseLength,boolean sharedSupports,double guardCenter) {}
    public static ProfileModel read(JsonObject descriptor,String activeModel,String texture,boolean flip,ObjTemplate.Reader reader)throws IOException{
        if(descriptor.has("model")&&!descriptor.get("model").getAsString().equals(activeModel))throw new IllegalArgumentException("Descriptor model differs from active style");
        ObjTemplate obj=ObjTemplate.read(activeModel,texture,flip,reader);JsonObject roles=descriptor.getAsJsonObject("modelGroups");
        var rails=obj.select(names(roles,"rail"));var bearers=obj.select(names(roles,"sleeper"));var fittings=obj.select(names(roles,"fastener"));
        var attachments=obj.select(names(roles,"preserve"));var supportNames=names(roles,"supports");var supports=obj.select(supportNames);
        ContinuousGuard guard=null;var guardNames=new ArrayList<String>();var guardSupportNames=new ArrayList<String>();
        if(descriptor.has("continuousGuard")){
            JsonObject c=descriptor.getAsJsonObject("continuousGuard");guardNames.addAll(names(c,"groups"));
            if(c.has("supportGroups"))guardSupportNames.addAll(names(c,"supportGroups"));
            String endpointModel=c.get("endpointModel").getAsString();double length=c.get("endpointLength").getAsDouble();
            if(!(length>0)||!Double.isFinite(length))throw new IllegalArgumentException("continuousGuard endpointLength must be positive");
            ObjTemplate endpointObj=ObjTemplate.read(endpointModel,texture,flip,reader);var endpointNames=new ArrayList<>(endpointObj.groups().keySet());
            var endpoint=endpointObj.select(endpointNames);double endpointMin=points(endpoint).mapToDouble(V3::z).min().orElseThrow(),endpointMax=points(endpoint).mapToDouble(V3::z).max().orElseThrow();
            if(Math.abs(endpointMin)>.001||Math.abs(endpointMax-length)>.001)throw new IllegalArgumentException("continuousGuard endpoint OBJ z range must be 0..endpointLength");
            var guardRails=obj.select(guardNames);double guardCenter=points(guardRails).mapToDouble(v->Math.abs(v.x())).average().orElseThrow();
            // Explicit descriptor roles identify native check steel independently of OBJ names.
            guardRails=guardRails.stream().map(q->new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),"rail_native_guard",q.index(),q.uv())).toList();
            double inset=c.has("supportInset")?c.get("supportInset").getAsDouble():0,nose=c.has("noseLength")?c.get("noseLength").getAsDouble():0;
            String mode=c.has("supportMode")?c.get("supportMode").getAsString():"independent";
            if(inset<0||nose<0||nose>length||!Set.of("shared","independent").contains(mode))throw new IllegalArgumentException("Invalid continuousGuard support configuration");
            guard=new ContinuousGuard(guardRails,obj.select(guardSupportNames),endpoint,length,endpointMin,endpointMax,inset,nose,mode.equals("shared"),guardCenter);
        }
        var fixedNames=new ArrayList<>(obj.groups().keySet());fixedNames.removeAll(supportNames);fixedNames.removeAll(guardNames);var fixed=obj.select(fixedNames);
        // Slab branches share coplanar bed surfaces at a junction. Mark only the
        // descriptor's preserved bed for union in the cell renderer (any namespace).
        boolean continuousSupports=descriptor.has("continuousSupports")&&descriptor.get("continuousSupports").getAsBoolean();
        if(continuousSupports||descriptor.has("trackBed")&&descriptor.get("trackBed").getAsString().equals("slab")){
            var bed=new HashSet<>(attachments);
            fixed=fixed.stream().map(q->bed.contains(q)?bedFace(q):q).toList();
            attachments=attachments.stream().map(ProfileModel::bedFace).toList();
        }
        if(continuousSupports)bearers=bearers.stream().map(ProfileModel::bedFace).toList();
        else if(descriptor.has("shapedSleepers")&&descriptor.get("shapedSleepers").getAsBoolean())
            bearers=bearers.stream().map(q->new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),"shaped_sleeper",q.index(),q.uv())).toList();
        if(!guardSupportNames.isEmpty()){var ordinarySupportNames=new ArrayList<>(supportNames);ordinarySupportNames.removeAll(guardSupportNames);supports=obj.select(ordinarySupportNames);}
        if(rails.isEmpty()||bearers.isEmpty()||fittings.isEmpty())throw new IllegalArgumentException("Incomplete rail model roles");
        double zMin=points(rails).mapToDouble(V3::z).min().orElseThrow(),zMax=points(rails).mapToDouble(V3::z).max().orElseThrow();
        if(zMax-zMin<.01)throw new IllegalArgumentException("Rail template has no length");
        for(var face:rails){double lo=points(List.of(face)).mapToDouble(V3::z).min().orElseThrow(),hi=points(List.of(face)).mapToDouble(V3::z).max().orElseThrow();
            if(hi-lo<.001)throw new IllegalArgumentException("Exclude rail end caps from sweep group");}
        double top=points(rails).mapToDouble(V3::y).max().orElseThrow();
        double headLo=points(rails).filter(v->v.y()>top-.035).mapToDouble(V3::x).min().orElseThrow(),headHi=points(rails).filter(v->v.y()>top-.035).mapToDouble(V3::x).max().orElseThrow();
        double half=points(bearers).mapToDouble(v->Math.abs(v.x())).max().orElseThrow(),seat=points(bearers).mapToDouble(V3::y).max().orElseThrow();
        var end=descriptor.has("steelEndTexture")?new Profile.Surface(descriptor.get("steelEndTexture").getAsString(),0,0,1,1,-1):Profile.END_STEEL;
        var detail=new ModelDetail(rails,bearers,fittings,(headHi+headLo)/2,top,headHi-headLo,zMin,zMax,half,seat,false,false,end);
        if(descriptor.has("continuousGuard")&&descriptor.getAsJsonObject("continuousGuard").has("turnout")){
            JsonObject turn=descriptor.deepCopy(),spec=descriptor.getAsJsonObject("continuousGuard").getAsJsonObject("turnout");
            turn.addProperty("model",spec.get("model").getAsString());turn.add("modelGroups",spec.getAsJsonObject("modelGroups").deepCopy());turn.remove("continuousGuard");
            detail=read(turn,spec.get("model").getAsString(),texture,flip,reader).detail();
        }
        return new ProfileModel(detail,attachments,fixed,supports,descriptor.has("alignEndpointSleepers")&&descriptor.get("alignEndpointSleepers").getAsBoolean(),guard);
    }
    private static List<String> names(JsonObject o,String key){var out=new ArrayList<String>();for(var item:o.getAsJsonArray(key))out.add(item.getAsString());return out;}
    private static Mesh.Quad bedFace(Mesh.Quad q){return new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),"track_bed",q.index(),q.uv());}
    private static Stream<V3> points(List<Mesh.Quad> faces){return faces.stream().flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d()));}
}
