package org.mtrpoint.geometry;

import java.util.*;

public final class Detector {
    private record Segment(Track t,int i) {}
    public static List<Junction> find(List<Track> tracks) {
        var result=new ArrayList<Junction>(); var nodes=new TreeMap<String,List<Track>>();
        for(Track t:tracks){nodes.computeIfAbsent(t.startNode,k->new ArrayList<>()).add(t);nodes.computeIfAbsent(t.endNode,k->new ArrayList<>()).add(t.reverse());}
        for(var entry:nodes.entrySet()) {
            var ts=entry.getValue(); ts.sort(Comparator.comparing(t->t.id));
            if(ts.size()<2||ts.size()>3)continue;
            for(int i=0;i<ts.size();i++)for(int j=i+1;j<ts.size();j++) {
                Track a=ts.get(i),b=ts.get(j);
                if(a.length<5||b.length<5||a.tangent(0).dot(b.tangent(0))<.85)continue;
                double limit=Math.min(80,Math.min(a.length,b.length)),extent=0;
                for(double s=1;s<limit;s+=.25)if(a.at(s).distance(b.at(s))>=2.5){extent=Math.min(limit,s+2);break;}
                if(extent==0)continue;
                // Same direction diverging branches only. Four-way junctions are not Y switches.
                result.add(new Junction("y:"+entry.getKey()+":"+a.id+":"+b.id,Junction.Kind.Y,a,b,a.at(0),0,0,extent));
            }
        }
        // Spatial bins keep crossing discovery local instead of comparing every rail pair.
        var bins=new HashMap<String,List<Segment>>(); var seen=new HashSet<String>();
        for(Track t:tracks) for(int i=1;i<t.points.size();i++) {
            V3 a=t.points.get(i-1),b=t.points.get(i);
            for(int x=(int)Math.floor(Math.min(a.x(),b.x())/8);x<=(int)Math.floor(Math.max(a.x(),b.x())/8);x++)
                for(int z=(int)Math.floor(Math.min(a.z(),b.z())/8);z<=(int)Math.floor(Math.max(a.z(),b.z())/8);z++) {
                    String cell=x+":"+z; var entries=bins.computeIfAbsent(cell,k->new ArrayList<>());
                    for(Segment other:entries) {
                        if(t.id.equals(other.t.id)||t.sharesNode(other.t))continue;
                        String pair=t.id.compareTo(other.t.id)<0?t.id+"/"+i+"|"+other.t.id+"/"+other.i:other.t.id+"/"+other.i+"|"+t.id+"/"+i;
                        if(!seen.add(pair))continue;
                        double[] uv=intersection(a,b,other.t.points.get(other.i-1),other.t.points.get(other.i)); if(uv==null)continue;
                        V3 p=a.lerp(b,uv[0]),q=other.t.points.get(other.i-1).lerp(other.t.points.get(other.i),uv[1]);
                        if(Math.abs(p.y()-q.y())>.08)continue;
                        double sine=Math.abs(V3.crossXZ(b.sub(a).unit(),other.t.points.get(other.i).sub(other.t.points.get(other.i-1)).unit()));
                        if(sine<.12)continue;
                        double sa=t.distance[i-1]+uv[0]*(t.distance[i]-t.distance[i-1]),sb=other.t.distance[other.i-1]+uv[1]*(other.t.distance[other.i]-other.t.distance[other.i-1]);
                        double extent=Math.min(16,2/sine+1);
                        if(Math.min(Math.min(sa,t.length-sa),Math.min(sb,other.t.length-sb))<extent)continue;
                        Track first=t.id.compareTo(other.t.id)<0?t:other.t,second=first==t?other.t:t;
                        String id="x:"+first.id+":"+second.id+":"+Math.round(p.x()*4)+":"+Math.round(p.z()*4);
                        if(result.stream().noneMatch(j->j.kind()==Junction.Kind.DIAMOND&&j.a().id.equals(first.id)&&j.b().id.equals(second.id)&&j.center().distance(p)<1))
                            result.add(new Junction(id,Junction.Kind.DIAMOND,first,second,p,first==t?sa:sb,first==t?sb:sa,extent));
                    }
                    entries.add(new Segment(t,i));
                }
        }
        return List.copyOf(result);
    }
    public static double[] intersection(V3 a,V3 b,V3 c,V3 d) {
        V3 r=b.sub(a),s=d.sub(c);double den=V3.crossXZ(r,s);if(Math.abs(den)<1e-9)return null;
        double u=V3.crossXZ(c.sub(a),s)/den,v=V3.crossXZ(c.sub(a),r)/den;
        return u>=0&&u<=1&&v>=0&&v<=1?new double[]{u,v}:null;
    }
}
