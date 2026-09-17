"""Compare committed geometry with working geometry, without changing the checkout."""
from pathlib import Path
import subprocess, shutil
root=Path(__file__).resolve().parents[1]
java=Path(r"C:/Users/Lenovo/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20+8/bin")
work=root/'build/benchmark'
harness=r"""
import org.mtrpoint.geometry.*;
import java.util.*;
public class GeometryBench {
 static volatile double sink;
 public static void main(String[] args) {
  var points=new ArrayList<V3>();for(int i=0;i<=5000;i++)points.add(new V3(Math.sin(i*.001)*15,0,i*.2));
  Track track=new Track("a","a","b",points);var random=new Random(403);var queries=new ArrayList<V3>();for(int i=0;i<10000;i++)queries.add(new V3(random.nextDouble()*30-15,0,random.nextDouble()*1000));
  for(int i=0;i<1000;i++)sink+=track.nearest(queries.get(i));
  long begin=System.nanoTime();for(var q:queries)sink+=track.nearest(q);double nearest=(System.nanoTime()-begin)/1e6;
  var a=new ArrayList<V3>();var b=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.25;a.add(new V3(-.008*z*z,0,z));b.add(new V3(.008*z*z,0,z));}
  var j=Detector.find(List.of(new Track("a","0","1",a),new Track("b","0","2",b))).get(0);
  for(int i=0;i<20;i++)sink+=PointMesh.build(j,PointSettings.DEFAULT,Profile.STANDARD,0).quads.size();
  begin=System.nanoTime();for(int i=0;i<100;i++)sink+=PointMesh.build(j,PointSettings.DEFAULT,Profile.STANDARD,0).quads.size();
  System.out.printf(java.util.Locale.ROOT,"nearest_10000_ms=%.3f mesh_100_ms=%.3f quads=%d%n",nearest,(System.nanoTime()-begin)/1e6,PointMesh.build(j,PointSettings.DEFAULT,Profile.STANDARD,0).quads.size());
 }
}
"""
for variant in ['committed','working']:
 folder=work/variant;folder.mkdir(parents=True,exist_ok=True)
 files=[]
 if variant=='committed':
  paths=subprocess.check_output(['git','ls-tree','-r','--name-only','HEAD','src/main/java/org/mtrpoint/geometry'],cwd=root,text=True).splitlines()
  for path in paths:
   dest=folder/Path(path).name;dest.write_bytes(subprocess.check_output(['git','show','HEAD:'+path],cwd=root));files.append(str(dest))
 else:
  for path in (root/'src/main/java/org/mtrpoint/geometry').glob('*.java'):
   dest=folder/path.name;shutil.copyfile(path,dest);files.append(str(dest))
 (folder/'GeometryBench.java').write_text(harness,encoding='utf-8');files.append(str(folder/'GeometryBench.java'))
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(folder)]+files,check=True)
 result=subprocess.check_output([str(java/'java.exe'),'-Xmx1G','-cp',str(folder),'GeometryBench'],text=True)
 print(variant,result.strip())
 (work/(variant+'.txt')).write_text(result)
