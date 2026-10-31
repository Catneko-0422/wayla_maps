"""Turn the MANUKA .blend into a small GLB for the app's character view.

Run with Blender (5.x), never with plain Python:

    blender -b --disable-autoexec /path/to/MANUKA.blend \
        --python tools/characters/optimize_manuka.py -- app/src/local/assets/characters/manuka

The model is licensed (VN3, ©ジンゴ) and must not be redistributed: the output directory
is gitignored and only ends up in local builds.

What it does:
- drops the bra and panty meshes (the default outfit assumes them off);
- keeps only the face shape keys the app drives (blink, vowels, joy) and removes the others;
- decimates every other mesh, which then has no shape keys to preserve;
- poses the arms down from the T-pose and bakes that pose into the meshes, dropping the
  skeleton: 239 bones exceed the vertex uniform limit of some GPUs (and the emulator), and the
  view only needs a static pose plus face shape keys;
- shrinks the textures to 1024 px and writes model.glb plus character.json.
"""

import json
import math
import struct
import sys
from pathlib import Path

import bpy
from mathutils import Matrix

FACE_MESH = "Body"
DROP_MESHES = {"Manuka_underwear_bra", "Manuka_underwear_panty"}
FACE_KEYS = {
    "blink": "eye_close",
    "a": "mouth_a",
    "i": "mouth_i",
    "u": "mouth_u",
    "e": "mouth_e",
    "o": "mouth_o",
    "joy": "eye_joy",
}
DECIMATE_RATIO = 0.35
TEXTURE_SIZE = 1024
ARM_DOWN_DEG = 70


def remove_shape_keys(obj, keep):
    keys = obj.data.shape_keys
    if not keys:
        return
    for block in list(keys.key_blocks)[1:]:
        if block.name not in keep:
            obj.shape_key_remove(block)
    if len(obj.data.shape_keys.key_blocks) == 1:
        obj.shape_key_clear()


def decimate(obj):
    bpy.context.view_layer.objects.active = obj
    modifier = obj.modifiers.new("decimate", "DECIMATE")
    modifier.ratio = DECIMATE_RATIO
    # Before the armature modifier, so skin weights are decimated with the mesh.
    while obj.modifiers.find(modifier.name) > 0:
        bpy.ops.object.modifier_move_up(modifier=modifier.name)
    bpy.ops.object.modifier_apply(modifier=modifier.name)


def pose_arms_down(armature):
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode="POSE")
    for side, sign in (("L", 1), ("R", -1)):
        bone = armature.pose.bones[f"UpperArm_{side}"]
        head = bone.head.copy()
        rotation = Matrix.Rotation(math.radians(sign * ARM_DOWN_DEG), 4, "Y")
        bone.matrix = Matrix.Translation(head) @ rotation @ Matrix.Translation(-head) @ bone.matrix
        bpy.context.view_layer.update()
    bpy.ops.object.mode_set(mode="OBJECT")


def bake_pose(armature):
    """Applies the posed armature to every mesh and removes the skeleton. The face mesh keeps
    its shape keys, which block applying modifiers; it only follows the head, which the pose
    leaves where it was, so its armature modifier is simply dropped."""
    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        for modifier in [m for m in obj.modifiers if m.type == "ARMATURE"]:
            bpy.context.view_layer.objects.active = obj
            if obj.data.shape_keys:
                obj.modifiers.remove(modifier)
            else:
                bpy.ops.object.modifier_apply(modifier=modifier.name)
        if obj.parent == armature:
            matrix = obj.matrix_world.copy()
            obj.parent = None
            obj.matrix_world = matrix
        obj.vertex_groups.clear()
    bpy.data.objects.remove(armature, do_unlink=True)


def make_unlit(glb_path):
    """The .blend shades with emission (flat toon look), which glTF exports as black base color
    plus an emissive texture and no transparency. Rewrite every material as unlit with the
    texture as base color and alpha masking, which Filament renders as intended."""
    data = glb_path.read_bytes()
    json_length = struct.unpack_from("<I", data, 12)[0]
    gltf = json.loads(data[20:20 + json_length])
    for material in gltf["materials"]:
        texture = material.pop("emissiveTexture", None)
        material.pop("emissiveFactor", None)
        pbr = material.setdefault("pbrMetallicRoughness", {})
        pbr["baseColorFactor"] = [1, 1, 1, 1]
        if texture:
            pbr["baseColorTexture"] = texture
        material["alphaMode"] = "MASK"
        material["alphaCutoff"] = 0.5
        material.setdefault("extensions", {})["KHR_materials_unlit"] = {}
    gltf["extensionsUsed"] = sorted(set(gltf.get("extensionsUsed", [])) | {"KHR_materials_unlit"})

    chunk = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    chunk += b" " * (-len(chunk) % 4)
    rest = data[20 + json_length:]
    header = struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(chunk) + len(rest))
    glb_path.write_bytes(header + struct.pack("<I4s", len(chunk), b"JSON") + chunk + rest)


def main():
    out_dir = Path(sys.argv[sys.argv.index("--") + 1]).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    for obj in list(bpy.data.objects):
        if obj.type == "MESH" and obj.name in DROP_MESHES:
            bpy.data.objects.remove(obj, do_unlink=True)

    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        if obj.name == FACE_MESH:
            remove_shape_keys(obj, set(FACE_KEYS.values()))
        else:
            remove_shape_keys(obj, set())
            decimate(obj)

    armature = bpy.data.objects["Armature"]
    pose_arms_down(armature)
    bake_pose(armature)

    for image in bpy.data.images:
        if image.size[0] > TEXTURE_SIZE:
            image.scale(TEXTURE_SIZE, TEXTURE_SIZE)

    bpy.ops.export_scene.gltf(
        filepath=str(out_dir / "model.glb"),
        export_format="GLB",
        export_yup=True,
        export_apply=False,
        export_morph=True,
        export_skins=False,
        export_animations=False,
        export_image_format="AUTO",
        export_texcoords=True,
        export_normals=True,
    )

    make_unlit(out_dir / "model.glb")

    tris = sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in bpy.data.objects if o.type == "MESH")
    manifest = {
        "id": "manuka",
        "name": "MANUKA",
        "credit": "MANUKA ©ジンゴ (VN3 license, personal use)",
        "model": {"file": "model.glb", "morphs": FACE_KEYS, "focusHeight": 1.08, "cameraDistance": 0.6},
    }
    (out_dir / "character.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OPTIMIZED triangles={tris} glb={(out_dir / 'model.glb').stat().st_size} bytes")


main()
