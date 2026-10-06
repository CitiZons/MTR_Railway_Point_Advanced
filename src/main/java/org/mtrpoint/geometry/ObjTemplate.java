package org.mtrpoint.geometry;

import java.io.IOException;
import java.util.*;

/** Resource-manager supplied OBJ geometry. Group roles are data, never a pack whitelist. */
public record ObjTemplate(Map<String,List<Mesh.Quad>> groups) {
    @FunctionalInterface public interface Reader { String read(String id)throws IOException; }
    public List<Mesh.Quad> select(Collection<String> names){
        var out=new ArrayList<Mesh.Quad>();for(String name:names){var faces=groups.get(name);
            if(faces==null)throw new IllegalArgumentException("Missing OBJ group: "+name);out.addAll(faces);}
        return List.copyOf(out);
    }
    public static ObjTemplate read(String model,String fallback,boolean flip,Reader reader)throws IOException{
        var vertices=new ArrayList<V3>();var normals=new ArrayList<V3>();var uv=new ArrayList<float[]>();var groups=new LinkedHashMap<String,List<Mesh.Quad>>();
        var textures=new HashMap<String,String>();String group="default",material="";
        for(String line:reader.read(model).split("\\R")){
            String[] t=line.trim().split("\\s+");if(t.length<2)continue;
            switch(t[0]){
                case "v" -> {V3 v=new V3(Double.parseDouble(t[1]),Double.parseDouble(t[2]),Double.parseDouble(t[3]));if(!Double.isFinite(v.x()+v.y()+v.z()))throw new IllegalArgumentException("Nonfinite vertex");vertices.add(v);}
                case "vn" -> normals.add(new V3(Double.parseDouble(t[1]),Double.parseDouble(t[2]),Double.parseDouble(t[3])).unit());
                case "vt" -> uv.add(new float[]{Float.parseFloat(t[1]),Float.parseFloat(t[2])});
                case "g", "o" -> group=t[1];
                case "usemtl" -> material=t[1];
                case "mtllib" -> {String current="";for(String ml:reader.read(relative(model,t[1])).split("\\R")){
                    String[] mt=ml.trim().split("\\s+",2);if(mt.length<2)continue;
                    if(mt[0].equals("newmtl"))current=mt[1];else if(mt[0].equals("map_Kd"))textures.put(current,relative(model,mt[1]));}}
                case "f" -> {
                    if(t.length<4||t.length>5)throw new IllegalArgumentException("Triangulate OBJ n-gons before export");
                    var points=new ArrayList<V3>();var tex=new ArrayList<Float>();var faceNormals=new ArrayList<V3>();
                    for(int i=1;i<t.length;i++){String[] indices=t[i].split("/");
                        points.add(vertices.get(index(indices[0],vertices.size())));
                        if(indices.length>2&&!indices[2].isBlank())faceNormals.add(normals.get(index(indices[2],normals.size())));
                        if(indices.length<2||indices[1].isBlank())throw new IllegalArgumentException("OBJ UV required");
                        float[] u=uv.get(index(indices[1],uv.size()));if(!Float.isFinite(u[0]+u[1]))throw new IllegalArgumentException("Nonfinite UV");
                        tex.add(u[0]);tex.add(flip?1-u[1]:u[1]);}
                    if(points.size()==3){if(faceNormals.size()==3)faceNormals.add(faceNormals.get(2));points.add(points.get(2));tex.add(tex.get(4));tex.add(tex.get(5));}
                    String texture=textures.getOrDefault(material,fallback);if(texture==null||texture.isBlank())throw new IllegalArgumentException("OBJ texture required");
                    var surface=new Profile.Surface(texture,0,0,1,1,-1);
                    groups.computeIfAbsent(group,k->new ArrayList<>()).add(new Mesh.Quad(points.get(0),points.get(1),points.get(2),points.get(3),surface,group,-1,List.copyOf(tex),faceNormals.size()==4?List.copyOf(faceNormals):null));
                }
            }
        }
        var frozen=new LinkedHashMap<String,List<Mesh.Quad>>();groups.forEach((k,v)->frozen.put(k,List.copyOf(v)));return new ObjTemplate(Collections.unmodifiableMap(frozen));
    }
    private static int index(String s,int size){int n=Integer.parseInt(s);return n<0?size+n:n-1;}
    private static String relative(String model,String path){return path.contains(":")?path:model.substring(0,model.lastIndexOf('/')+1)+path.replace('\\','/');}
}
