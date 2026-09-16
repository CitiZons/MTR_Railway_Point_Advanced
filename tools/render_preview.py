"""Run with Blender --background --python tools/render_preview.py (original procedural assets)."""
import bpy, math
from pathlib import Path
from mathutils import Vector
root=Path(__file__).resolve().parents[1]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
materials={}
for name,color in {'rail':(.44,.49,.52,1),'blade':(.65,.7,.73,1),'guard':(.38,.42,.44,1),'stretcher':(.26,.29,.30,1),'sleeper':(.23,.17,.11,1),'frog':(.58,.62,.64,1)}.items():
    m=bpy.data.materials.new(name);m.diffuse_color=color;materials[name]=m
for name,offset in [('y-left',(-8,0,0)),('diamond',(10,6,0))]:
    vertices=[];groups={};group='rail'
    for line in (root/'build/previews'/f'{name}.obj').read_text().splitlines():
        t=line.split()
        if not t:continue
        if t[0]=='g':group=t[1]
        if t[0]=='v':vertices.append((float(t[1])+offset[0],float(t[3])+offset[1],float(t[2])+offset[2]))
        if t[0]=='f':groups.setdefault(group,[]).append(tuple(int(i)-1 for i in t[1:]))
    for group,faces in groups.items():
        mesh=bpy.data.meshes.new(name+'-'+group);mesh.from_pydata(vertices,[],faces);mesh.update()
        obj=bpy.data.objects.new(name+'-'+group,mesh);bpy.context.collection.objects.link(obj);obj.data.materials.append(materials.get(group,materials['rail']))
bpy.ops.object.camera_add(location=(22,-21,32));camera=bpy.context.object
camera.rotation_euler=(Vector((0,5,0))-camera.location).to_track_quat('-Z','Y').to_euler();camera.data.type='ORTHO';camera.data.ortho_scale=36
scene=bpy.context.scene;scene.camera=camera;scene.render.engine='BLENDER_WORKBENCH'
scene.display.shading.light='STUDIO';scene.display.shading.color_type='MATERIAL';scene.display.shading.show_shadows=True;scene.display.shading.show_cavity=True
scene.display.shading.background_type='WORLD';scene.world.color=(.035,.05,.065)
scene.render.resolution_x=1600;scene.render.resolution_y=1200;scene.render.resolution_percentage=100
scene.render.filepath=str(root/'build/previews/geometry.png')
bpy.ops.wm.save_as_mainfile(filepath=str(root/'build/previews/point-workshop.blend'))
bpy.ops.render.render(write_still=True)
