package org.mtrpoint.client;

import net.minecraft.world.phys.AABB;
import org.mtrpoint.geometry.Mesh;
import org.mtrpoint.geometry.Profile;
import org.mtrpoint.geometry.V3;

/** Pure checks for GPU upload budgeting and mesh-bound calculation. */
public final class PointGpuStaticRegression {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}

    public static void run(){
        var budget=new PointGpu.UploadBudget();
        budget.beginFrame();
        try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        for(int i=0;i<8;i++)require(budget.reserve(1),"Upload budget rejected a batch before its batch cap");
        require(!budget.reserve(1),"Upload budget exceeded its per-frame batch cap");
        budget.beginFrame();
        require(budget.reserve(1),"Idle frame time incorrectly exhausted the upload budget");
        budget.recordTime(3_000_000L);
        require(!budget.reserve(1),"Upload budget exceeded its GPU upload time cap");
        budget.beginFrame();
        require(budget.bytes()==0&&budget.batches()==0,"beginFrame did not reset upload counters");
        require(budget.reserve(2L*1024*1024),"Upload budget rejected its exact byte limit");
        require(!budget.reserve(1),"Upload budget exceeded its per-frame byte cap");

        Mesh mesh=new Mesh();
        var steel=Profile.STANDARD.steel();
        mesh.quad(new Mesh.Quad(new V3(2,3,4),new V3(5,3,4),new V3(5,7,8),new V3(2,7,8),steel,"rail",-1));
        AABB bounds=PointGpu.computeBounds(mesh,false);
        require(bounds!=null&&bounds.minX==1.5&&bounds.maxX==5.5&&bounds.minY==2.5&&bounds.maxY==7.5&&bounds.minZ==3.5&&bounds.maxZ==8.5,"Mesh bounds do not enclose geometry with the render margin");
        AABB filteredBounds=PointGpu.computeBounds(mesh,true);
        require(filteredBounds!=null&&filteredBounds.minX==bounds.minX&&filteredBounds.maxZ==bounds.maxZ,"Filtering unaffected geometry changed the bounds");
        Mesh hidden=new Mesh();hidden.quad(new Mesh.Quad(new V3(0,0,0),new V3(1,0,0),new V3(1,0,1),new V3(0,0,1),steel,"sleeper",-1));
        require(PointGpu.computeBounds(hidden,true)==null,"Fully hidden mesh should have no GPU bounds");
    }
}
