package org.mtrpoint.geometry;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

/** Explicit roles in an ordinary rail cell, reused by turnout sweeps and endpoint supports. */
public record ProfileModel(ModelDetail detail,List<Mesh.Quad> attachments,List<Mesh.Quad> fixed,List<Mesh.Quad> supports,boolean alignSleepers) {
    public static ProfileModel read(JsonObject descriptor,String activeModel,String texture,boolean flip,ObjTemplate.Reader reader)throws IOException{
        if(descriptor.has("model")&&!descriptor.get("model").getAsString().equals(activeModel))throw new IllegalArgumentException("Descriptor model differs from active style");
        ObjTemplate obj=ObjTemplate.read(activeModel,texture,flip,reader);JsonObject roles=descriptor.getAsJsonObject("modelGroups");
        var rails=obj.select(names(roles,"rail"));var bearers=obj.select(names(roles,"sleeper"));var fittings=obj.select(names(roles,"fastener"));
        var attachments=obj.select(names(roles,"preserve"));var supportNames=names(roles,"supports");var supports=obj.select(supportNames);
        var fixedNames=new ArrayList<>(obj.groups().keySet());fixedNames.removeAll(supportNames);var fixed=obj.select(fixedNames);
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
        return new ProfileModel(detail,attachments,fixed,supports,descriptor.has("alignEndpointSleepers")&&descriptor.get("alignEndpointSleepers").getAsBoolean());
    }
    private static List<String> names(JsonObject o,String key){var out=new ArrayList<String>();for(var item:o.getAsJsonArray(key))out.add(item.getAsString());return out;}
    private static Stream<V3> points(List<Mesh.Quad> faces){return faces.stream().flatMap(q->Stream.of(q.a(),q.b(),q.c(),q.d()));}
}
