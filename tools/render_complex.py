"""Inspect procedural three-way and scissors assemblies with Blender."""
import bpy
from pathlib import Path
from mathutils import Vector
root=Path(__file__).resolve().parents[1]/'build/previews'
for name,center,scale in [('three-center',(0,10,0),24),('scissors',(0,0,0),44),('scissors-curved',(2,0,0),44)]:
    bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
    vertices=[];groups={};group='rail'
    for line in (root/(name+'.obj')).read_text(encoding='utf-8').splitlines():
        t=line.split()
        if not t:continue
        if t[0]=='g':group=t[1]
        if t[0]=='v':vertices.append((float(t[1]),float(t[3]),float(t[2])))
        if t[0]=='f':groups.setdefault(group,[]).append(tuple(int(i)-1 for i in t[1:]))
    colors={'sleeper':(.3,.24,.17,1),'rail':(.5,.54,.58,1),'blade':(.8,.82,.85,1),'frog':(.8,.82,.85,1),'wing':(.5,.65,.68,1),'guard':(.4,.5,.5,1)}
    for group,faces in groups.items():
        mesh=bpy.data.meshes.new(group);mesh.from_pydata(vertices,[],faces);mesh.update()
        obj=bpy.data.objects.new(group,mesh);bpy.context.collection.objects.link(obj)
        material=bpy.data.materials.new(group);material.diffuse_color=colors.get(group,colors['rail']);obj.data.materials.append(material)
    bpy.ops.object.camera_add(location=(center[0]+8,center[1]-10,40));camera=bpy.context.object
    camera.rotation_euler=(Vector(center)-camera.location).to_track_quat('-Z','Y').to_euler();camera.data.type='ORTHO';camera.data.ortho_scale=scale
    scene=bpy.context.scene;scene.camera=camera;scene.render.engine='BLENDER_WORKBENCH'
    scene.display.shading.light='STUDIO';scene.display.shading.color_type='MATERIAL';scene.display.shading.show_shadows=True;scene.display.shading.show_cavity=True
    scene.display.shading.background_type='WORLD';scene.world.color=(.035,.05,.065)
    scene.render.resolution_x=1200;scene.render.resolution_y=1600;scene.render.resolution_percentage=100
    scene.render.filepath=str(root/(name+'.png'));bpy.ops.wm.save_as_mainfile(filepath=str(root/(name+'.blend')));bpy.ops.render.render(write_still=True)
