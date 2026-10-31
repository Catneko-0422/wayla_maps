"""Turn the MANUKA .blend into a small GLB for the app's character view.

Run with Blender (5.x), never with plain Python:

    blender -b --disable-autoexec /path/to/MANUKA.blend \
        --python tools/characters/optimize_manuka.py -- app/src/local/assets/characters/manuka [--preview DIR]

The model is licensed (VN3, ©ジンゴ) and must not be redistributed: the output directory
is gitignored and only ends up in local builds.

What it does:
- drops the bra and panty meshes (the default outfit assumes them off);
- keeps only the face shape keys the app drives (blink, vowels, expressions) and removes the
  others; decimates every other mesh, which then has no shape keys to preserve;
- poses the arms down from the T-pose and makes that the rest pose;
- cuts the skeleton from 239 bones to the 25 the motions use (body, head, arms, ears, tail),
  moving the skin weights of the others to their nearest kept ancestor: the full skeleton
  exceeds the vertex uniform limit of some GPUs (and the emulator);
- keys short motions (idle, talk, nod, tilt, wave, salute, cheer) as glTF animations;
- shrinks the textures to 1024 px and writes model.glb plus character.json.

With --preview, a few frames of each motion are rendered to PNG for checking poses.
"""

import json
import math
import struct
import sys
from pathlib import Path

import bpy
from mathutils import Matrix, Vector

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
# Morph weights for each mood the app shows; the names are the app's (CharacterMood).
EXPRESSIONS = {
    "happy": {"eye_happy": 0.8, "mouth_smile": 1.0, "brow_joy": 0.6},
    "joy": {"eye_joy": 1.0, "mouth_wa": 0.8, "brow_joy": 0.8},
    "serious": {"brow_tsuri": 0.8, "eye_jito": 0.3},
    "worried": {"brow_trouble": 1.0, "mouth_sad": 0.7, "eye_sad": 0.4},
    "oops": {"eye_> <": 1.0, "brow_trouble": 0.6},
    "surprised": {"brow_surprised": 1.0, "mouth_○": 0.8},
    "sleepy": {"eye_sleep": 1.0, "brow_nagomi": 1.0},
}
TAIL = ["Manuka_tail"] + [f"Manuka_tail.{i:03d}" for i in range(1, 8)]
KEEP_BONES = [
    "Hips", "Spine", "Chest", "Neck", "Head",
    "Shoulder_L", "UpperArm_L", "LowerArm_L", "Hand_L",
    "Shoulder_R", "UpperArm_R", "LowerArm_R", "Hand_R",
    "Manuka_ear_L", "Manuka_ear_L.001", "Manuka_ear_R", "Manuka_ear_R.001",
] + TAIL
FPS = 30
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


def rotate(armature, bone, axis, degrees):
    """Turns a pose bone about its head, around an armature-space axis: X is the character's
    left, Y her back and Z up, so +X nods forward, +Y tilts the head to her left and +Z turns it
    to her left."""
    pose_bone = armature.pose.bones[bone]
    head = pose_bone.matrix.translation.copy()
    rotation = Matrix.Rotation(math.radians(degrees), 4, axis)
    pose_bone.matrix = Matrix.Translation(head) @ rotation @ Matrix.Translation(-head) @ pose_bone.matrix
    bpy.context.view_layer.update()


def twist(armature, bone, degrees):
    """Turns a pose bone about its own length, e.g. a forearm turning the palm over."""
    pose_bone = armature.pose.bones[bone]
    head = pose_bone.matrix.translation.copy()
    axis = (pose_bone.matrix.to_3x3() @ Vector((0, 1, 0))).normalized()
    rotation = Matrix.Rotation(math.radians(degrees), 4, axis)
    pose_bone.matrix = Matrix.Translation(head) @ rotation @ Matrix.Translation(-head) @ pose_bone.matrix
    bpy.context.view_layer.update()


def move(armature, bone, offset):
    pose_bone = armature.pose.bones[bone]
    pose_bone.matrix = Matrix.Translation(Vector(offset)) @ pose_bone.matrix
    bpy.context.view_layer.update()


def pose_arms_down(armature):
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode="POSE")
    rotate(armature, "UpperArm_L", "Y", ARM_DOWN_DEG)
    rotate(armature, "UpperArm_R", "Y", -ARM_DOWN_DEG)
    bpy.ops.object.mode_set(mode="OBJECT")


def apply_rest_pose(armature):
    """Bakes the current pose into the meshes and makes it the skeleton's rest pose. The face
    mesh keeps its shape keys, which block applying modifiers; it only follows the head, which
    the arm pose leaves where it was, so its skinning stays as it is."""
    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        modifiers = [m for m in obj.modifiers if m.type == "ARMATURE"]
        if not modifiers or obj.data.shape_keys:
            continue
        bpy.context.view_layer.objects.active = obj
        name = modifiers[0].name
        bpy.ops.object.modifier_copy(modifier=name)
        bpy.ops.object.modifier_apply(modifier=name)
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode="POSE")
    bpy.ops.pose.select_all(action="SELECT")
    bpy.ops.pose.armature_apply(selected=False)
    bpy.ops.object.mode_set(mode="OBJECT")


def reduce_skeleton(armature, keep):
    """Removes every bone not in keep, adding its skin weights to the nearest kept ancestor."""
    target = {}
    for bone in armature.data.bones:
        kept = bone
        while kept is not None and kept.name not in keep:
            kept = kept.parent
        target[bone.name] = kept.name if kept else "Hips"

    for obj in [o for o in bpy.data.objects if o.type == "MESH" and o.vertex_groups]:
        names = {group.index: group.name for group in obj.vertex_groups}
        moved = {}
        for vertex in obj.data.vertices:
            for element in vertex.groups:
                name = names[element.group]
                to = target.get(name)
                if to is not None and to != name:
                    moved[(vertex.index, to)] = moved.get((vertex.index, to), 0.0) + element.weight
        for (index, to), weight in moved.items():
            group = obj.vertex_groups.get(to) or obj.vertex_groups.new(name=to)
            group.add([index], weight, "ADD")
        for name, to in target.items():
            if to != name and obj.vertex_groups.get(name):
                obj.vertex_groups.remove(obj.vertex_groups[name])

    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode="EDIT")
    edit_bones = armature.data.edit_bones
    for name, to in target.items():
        if to == name:
            continue
        bone = edit_bones[name]
        for child in bone.children:
            child.use_connect = False
            child.parent = bone.parent
        edit_bones.remove(bone)
    bpy.ops.object.mode_set(mode="OBJECT")


def breathing(armature, phase):
    """The idle base: a slow breath, the head drifting and the tail swaying."""
    wave = math.sin(2 * math.pi * phase)
    rotate(armature, "Chest", "X", -1.2 * wave)
    rotate(armature, "UpperArm_L", "Y", 1.5 * wave)
    rotate(armature, "UpperArm_R", "Y", -1.5 * wave)
    rotate(armature, "Head", "Z", 2.5 * math.sin(2 * math.pi * phase))
    rotate(armature, "Head", "Y", 1.5 * math.cos(2 * math.pi * phase))
    for i, bone in enumerate(TAIL[:4]):
        rotate(armature, bone, "Z", (8 - i) * math.sin(2 * math.pi * phase - i * 0.4))


def ears(armature, degrees):
    """Ears outward (positive, perked) or drooping (negative)."""
    rotate(armature, "Manuka_ear_L", "Y", -degrees)
    rotate(armature, "Manuka_ear_R", "Y", degrees)


def raise_right_arm(armature, side, forward, elbow):
    rotate(armature, "UpperArm_R", "X", -forward)
    rotate(armature, "UpperArm_R", "Y", side)
    rotate(armature, "LowerArm_R", "Y", elbow)


def hands_up(armature, amount):
    """Both hands up beside the shoulders, elbows bent: a small "yay"."""
    for bone, sign in (("L", -1), ("R", 1)):
        rotate(armature, f"UpperArm_{bone}", "X", -20 * amount)
        rotate(armature, f"UpperArm_{bone}", "Y", sign * 35 * amount)
        rotate(armature, f"LowerArm_{bone}", "Y", sign * 125 * amount)


def salute(armature):
    """Fingertips to the brow, hand flat with the palm facing out, as in a military salute."""
    raise_right_arm(armature, side=95, forward=25, elbow=110)
    twist(armature, "LowerArm_R", -90)
    rotate(armature, "Hand_R", "Y", 50)
    rotate(armature, "Head", "X", -3)


def motions():
    """Each motion: frame count, whether it loops, and the pose at its key frames. Every key
    frame sets all kept bones, so a motion never inherits a pose from the one before."""
    idle_frames = 120
    return {
        "idle": (idle_frames, True, [(f, (lambda p: lambda a: breathing(a, p))(f / idle_frames)) for f in range(0, idle_frames + 1, 10)]
                 + [(96, lambda a: (breathing(a, 0.8), ears(a, 12))), (100, lambda a: breathing(a, 100 / idle_frames))]),
        "talk": (30, True, [
            (0, lambda a: None),
            (8, lambda a: rotate(a, "Head", "X", 3)),
            (15, lambda a: rotate(a, "Head", "X", 0.5)),
            (23, lambda a: rotate(a, "Head", "X", 2)),
            (30, lambda a: None),
        ]),
        "nod": (24, False, [
            (0, lambda a: None),
            (8, lambda a: (rotate(a, "Neck", "X", 5), rotate(a, "Head", "X", 12))),
            (20, lambda a: None),
            (24, lambda a: None),
        ]),
        "tilt": (40, False, [
            (0, lambda a: None),
            (10, lambda a: (rotate(a, "Head", "Y", -10), ears(a, -15))),
            (30, lambda a: (rotate(a, "Head", "Y", -9), ears(a, -15))),
            (40, lambda a: None),
        ]),
        "wave": (64, False, [
            (0, lambda a: None),
            (12, lambda a: raise_right_arm(a, side=100, forward=15, elbow=75)),
        ] + [
            (f, (lambda d: lambda a: (raise_right_arm(a, side=100, forward=15, elbow=75 + d), rotate(a, "Head", "Y", 3)))(d))
            for f, d in ((20, 20), (28, -15), (36, 20), (44, -15))
        ] + [
            (52, lambda a: raise_right_arm(a, side=100, forward=15, elbow=75)),
            (64, lambda a: None),
        ]),
        "salute": (72, False, [
            (0, lambda a: None),
            (14, salute),
            (54, salute),
            (72, lambda a: None),
        ]),
        "cheer": (36, False, [
            (0, lambda a: None),
            (8, lambda a: (move(a, "Hips", (0, 0, 0.025)), hands_up(a, 1.0), ears(a, 12))),
            (16, lambda a: (hands_up(a, 0.8), ears(a, 8))),
            (24, lambda a: (move(a, "Hips", (0, 0, 0.015)), hands_up(a, 1.0), ears(a, 10))),
            (36, lambda a: None),
        ]),
    }


def reset_pose(armature):
    for pose_bone in armature.pose.bones:
        pose_bone.rotation_mode = "QUATERNION"
        pose_bone.rotation_quaternion = (1, 0, 0, 0)
        pose_bone.location = (0, 0, 0)
        pose_bone.scale = (1, 1, 1)
    bpy.context.view_layer.update()


def key_motions(armature):
    """One action per motion, named as the app looks them up."""
    bpy.context.scene.render.fps = FPS
    armature.animation_data_create()
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode="POSE")
    actions = {}
    for name, (frames, _loops, keys) in motions().items():
        action = bpy.data.actions.new(name)
        action.use_fake_user = True
        armature.animation_data.action = action
        for frame, pose in keys:
            reset_pose(armature)
            pose(armature)
            for pose_bone in armature.pose.bones:
                pose_bone.keyframe_insert("rotation_quaternion", frame=frame)
                pose_bone.keyframe_insert("location", frame=frame)
        action.frame_range = (0, frames)
        actions[name] = action
    reset_pose(armature)
    armature.animation_data.action = None
    bpy.ops.object.mode_set(mode="OBJECT")
    return actions


def render_previews(armature, actions, directory):
    """A few frames of each motion, front on, for checking poses by eye."""
    directory.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "FLAT"
    scene.display.shading.color_type = "TEXTURE"
    scene.render.resolution_x = 400
    scene.render.resolution_y = 500
    camera_data = bpy.data.cameras.new("preview")
    camera = bpy.data.objects.new("preview", camera_data)
    scene.collection.objects.link(camera)
    scene.camera = camera
    camera.location = (0.25, -1.3, 1.05)
    camera.rotation_euler = (math.radians(90), 0, math.radians(11))
    camera_data.lens = 45
    for name, action in actions.items():
        armature.animation_data.action = action
        end = int(action.frame_range[1])
        for frame in sorted({0, end // 4, end // 2, 3 * end // 4}):
            scene.frame_set(frame)
            scene.render.filepath = str(directory / f"{name}_{frame:03d}.png")
            bpy.ops.render.render(write_still=True)
    armature.animation_data.action = None
    bpy.data.objects.remove(camera, do_unlink=True)


def make_lit(glb_path):
    """The .blend shades with emission (flat toon look), which glTF exports as black base color
    plus an emissive texture and no transparency. Rewrite every material as a matte, non-metal
    surface with the texture as base color and alpha masking, so the app's soft key light and
    ambient light give it gentle shading instead of a flat, pasted-on look."""
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
        # glTF defaults to fully metallic, which renders skin and cloth dark.
        pbr["metallicFactor"] = 0.0
        pbr["roughnessFactor"] = 0.9
        material["alphaMode"] = "MASK"
        material["alphaCutoff"] = 0.5
        material.get("extensions", {}).pop("KHR_materials_unlit", None)
        if not material.get("extensions"):
            material.pop("extensions", None)
    used = set(gltf.get("extensionsUsed", [])) - {"KHR_materials_unlit"}
    if used:
        gltf["extensionsUsed"] = sorted(used)
    else:
        gltf.pop("extensionsUsed", None)

    chunk = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    chunk += b" " * (-len(chunk) % 4)
    rest = data[20 + json_length:]
    header = struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(chunk) + len(rest))
    glb_path.write_bytes(header + struct.pack("<I4s", len(chunk), b"JSON") + chunk + rest)


def main():
    args = sys.argv[sys.argv.index("--") + 1:]
    out_dir = Path(args[0]).resolve()
    preview = Path(args[args.index("--preview") + 1]).resolve() if "--preview" in args else None
    out_dir.mkdir(parents=True, exist_ok=True)

    for obj in list(bpy.data.objects):
        if obj.type == "MESH" and obj.name in DROP_MESHES:
            bpy.data.objects.remove(obj, do_unlink=True)

    face_keys = set(FACE_KEYS.values()) | {key for weights in EXPRESSIONS.values() for key in weights}
    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        if obj.name == FACE_MESH:
            remove_shape_keys(obj, face_keys)
        else:
            remove_shape_keys(obj, set())
            decimate(obj)

    armature = bpy.data.objects["Armature"]
    pose_arms_down(armature)
    apply_rest_pose(armature)
    reduce_skeleton(armature, set(KEEP_BONES))
    actions = key_motions(armature)
    if preview:
        render_previews(armature, actions, preview)

    for image in bpy.data.images:
        if image.size[0] > TEXTURE_SIZE:
            image.scale(TEXTURE_SIZE, TEXTURE_SIZE)

    bpy.ops.export_scene.gltf(
        filepath=str(out_dir / "model.glb"),
        export_format="GLB",
        export_yup=True,
        export_apply=False,
        export_morph=True,
        export_skins=True,
        export_animations=True,
        export_animation_mode="ACTIONS",
        export_image_format="AUTO",
        export_texcoords=True,
        export_normals=True,
    )

    make_lit(out_dir / "model.glb")

    tris = sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in bpy.data.objects if o.type == "MESH")
    manifest_path = out_dir / "character.json"
    # Keep what was set by hand, such as the voice language.
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    manifest.update({
        "id": "manuka",
        "name": "MANUKA",
        "credit": "MANUKA ©ジンゴ (VN3 license, personal use)",
        "model": {
            "file": "model.glb",
            "morphs": FACE_KEYS,
            "expressions": EXPRESSIONS,
            "motions": {name: name for name in actions},
            "focusHeight": 1.08,
            "cameraDistance": 0.6,
        },
    })
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OPTIMIZED triangles={tris} bones={len(armature.data.bones)} glb={(out_dir / 'model.glb').stat().st_size} bytes")


main()
