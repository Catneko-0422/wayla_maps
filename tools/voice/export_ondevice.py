#!/usr/bin/env python3
"""Export a Genie-TTS voice and the Chinese G2P tables for speaking road names on the phone.

Genie-TTS (GPT-SoVITS v2ProPlus as ONNX) is what the home speech server runs. This prepares the
same models for ONNX Runtime on Android, which cannot inject fp16 weights the way Genie does, and
works out everything that depends only on the fixed reference clip ahead of time, so the phone
needs neither HuBERT nor the speaker encoder. Chinese runs without RoBERTa features (zeros), as
Genie itself does when that model is missing.

Run in a Python 3.11 environment with genie-tts==2.0.2 and GENIE_DATA_DIR set (HuBERT, speaker
encoder, G2P/ChineseG2P):

    python3 tools/voice/export_ondevice.py voice --model-dir DIR --ref-audio WAV --ref-text TEXT \\
        --out app/src/local/assets/characters/manuka/ondevice
    python3 tools/voice/export_ondevice.py g2p      # tables in app/src/main/assets/g2p_zh,
                                                   # expected phones in app/src/test/resources

Voice output: the ONNX graphs, their fp16 weight files (the app widens them to fp32 once, keeping
the offsets the graphs refer to), the reference tensors, and ondevice.json. A voice trained from
someone else's recordings stays personal: write it only into a gitignored directory.
"""

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
G2P_ASSETS = REPO_ROOT / "app/src/main/assets/g2p_zh"
G2P_GOLDEN = REPO_ROOT / "app/src/test/resources/g2p_zh_golden.tsv"
CAMERAS = REPO_ROOT / "app/src/main/assets/speed_cameras.json"

# Graph file -> the fp16 file its external fp32 weights come from (None: already fp32).
GRAPHS = {
    "t2s_encoder_fp32.onnx": None,
    "t2s_first_stage_decoder_fp32.onnx": "t2s_shared_fp16.bin",
    "t2s_stage_decoder_fp32.onnx": "t2s_shared_fp16.bin",
    "vits_fp32.onnx": "vits_fp16.bin",
}


def external_files(onnx_path):
    import onnx

    model = onnx.load(str(onnx_path), load_external_data=False)
    return {e.value for t in model.graph.initializer for e in t.external_data if e.key == "location"}


def write_tensor(path, array):
    """Raw little-endian data; shape and type go in ondevice.json."""
    import numpy as np

    np.ascontiguousarray(array).astype(array.dtype.newbyteorder("<")).tofile(path)
    return {"file": path.name, "shape": list(array.shape), "type": str(array.dtype)}


def export_voice(args):
    import numpy as np
    import genie_tts.ModelManager as mm
    from genie_tts.Audio.ReferenceAudio import ReferenceAudio

    model_dir, out = Path(args.model_dir), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    widen = {}
    for graph, fp16 in GRAPHS.items():
        shutil.copy2(model_dir / graph, out / graph)
        for location in external_files(model_dir / graph):
            if fp16 is None:
                shutil.copy2(model_dir / location, out / location)
            else:
                shutil.copy2(model_dir / fp16, out / fp16)
                widen[location] = fp16

    reference = ReferenceAudio(args.ref_audio, args.ref_text, language=args.ref_language)
    prompt_encoder = mm.load_session_with_fp16_conversion(
        str(model_dir / "prompt_encoder_fp32.onnx"), str(model_dir / "prompt_encoder_fp16.bin"), ["CPUExecutionProvider"],
    )
    reference.update_global_emb(prompt_encoder=prompt_encoder)
    tensors = {
        "ref_seq": write_tensor(out / "ref_seq.bin", reference.phonemes_seq.astype(np.int64)),
        "ssl_content": write_tensor(out / "ssl_content.bin", reference.ssl_content.astype(np.float32)),
        "ge": write_tensor(out / "ge.bin", reference.global_emb.astype(np.float32)),
        "ge_advanced": write_tensor(out / "ge_advanced.bin", reference.global_emb_advanced.astype(np.float32)),
    }
    manifest = {
        "format": 1,
        "sampleRate": 32000,
        "bertDim": 1024,
        "graphs": {
            "encoder": "t2s_encoder_fp32.onnx",
            "firstStageDecoder": "t2s_first_stage_decoder_fp32.onnx",
            "stageDecoder": "t2s_stage_decoder_fp32.onnx",
            "vocoder": "vits_fp32.onnx",
        },
        # fp32 file the graphs read -> fp16 file shipped instead (same element order).
        "widen": widen,
        "tensors": tensors,
    }
    (out / "ondevice.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    size = sum(f.stat().st_size for f in out.iterdir())
    print(f"voice: {out} ({size / 1e6:.0f} MB)")


# ---- G2P ----

def road_names():
    cameras = json.loads(CAMERAS.read_text(encoding="utf-8"))["cameras"]
    found = []
    for camera in cameras:
        for name in re.findall(r"[一-鿿0-9]{1,8}?(?:路|街|大道|公路)(?:[0-9一二三四五六七八九十]+段)?", camera["address"]):
            # Drop the township in front ("金城鎮西海路" -> "西海路").
            name = re.sub(r"^.*?[鄉鎮市區]", "", name) or name
            if name not in found:
                found.append(name)
    return found


def three_tone_sandhi(syllables):
    """A third tone before another third tone is said as a second tone (你好 ni2 hao3)."""
    out = list(syllables)
    for i in range(len(out) - 1):
        if out[i][-1] == "3" and out[i + 1][-1] == "3":
            out[i] = out[i][:-1] + "2"
    return out


def export_g2p(_args):
    import os
    import jieba_fast
    from g2pM import G2pM
    from pypinyin import Style, lazy_pinyin
    from pypinyin.constants import PHRASES_DICT, PINYIN_DICT
    from genie_tts.G2P.SymbolsV2 import symbols_v2
    from genie_tts.G2P.Chinese.ChineseG2P import Chinese_G2P_DIR, chinese_to_phones
    from genie_tts.G2P.Chinese.Normalization.char_convert import simplified_charcters, traditional_characters

    G2P_ASSETS.mkdir(parents=True, exist_ok=True)
    (G2P_ASSETS / "symbols.txt").write_text("\n".join(symbols_v2) + "\n", encoding="utf-8")
    shutil.copy2(Path(Chinese_G2P_DIR) / "opencpop-strict.txt", G2P_ASSETS / "opencpop.txt")

    # Genie reads traditional characters as simplified (PaddleSpeech's table, Apache-2.0).
    t2s = {t: s for t, s in zip(traditional_characters, simplified_charcters) if t != s}
    (G2P_ASSETS / "t2s.txt").write_text("".join(f"{t}\t{s}\n" for t, s in t2s.items()), encoding="utf-8")

    def tone3(text):
        return lazy_pinyin(text, style=Style.TONE3, neutral_tone_with_five=True)

    # A character's usual reading: g2pM's, which Genie uses, where it knows the character.
    g2pm = G2pM()
    chars = {}
    for code in sorted(PINYIN_DICT):
        char = chr(code)
        if not "\u4e00" <= char <= "\u9fff" or char in t2s:
            continue
        reading = g2pm(char, char_split=True)[0].replace("u:", "v")
        if not reading[:1].isalpha():
            reading = tone3(char)[0]
        if reading[:1].isalpha():
            chars[char] = reading if reading[-1].isdigit() else reading + "5"
    (G2P_ASSETS / "chars.txt").write_text("".join(f"{c}\t{p}\n" for c, p in chars.items()), encoding="utf-8")

    # Words read otherwise than character by character: other readings in context (多音字), and
    # third tones that change inside a word. Genie applies that sandhi within jieba's words only.
    words = {}
    def consider(word, reading):
        if 2 <= len(word) <= 4 and all(c in chars for c in word):
            spoken = three_tone_sandhi(reading)
            if spoken != [chars[c] for c in word]:
                words[word] = " ".join(spoken)
    for word in PHRASES_DICT:
        if all(c in chars for c in word):
            consider(word, tone3(word))
    with open(os.path.join(os.path.dirname(jieba_fast.__file__), "dict.txt"), encoding="utf-8") as f:
        for line in f:
            word = line.split(" ", 1)[0]
            if word not in words and all(c in chars for c in word):
                reading = [chars[c] for c in word]
                if any(a[-1] == "3" and b[-1] == "3" for a, b in zip(reading, reading[1:])):
                    consider(word, reading)
    (G2P_ASSETS / "words.txt").write_text("".join(f"{w}\t{p}\n" for w, p in sorted(words.items())), encoding="utf-8")
    print(f"g2p: {len(chars)} characters, {len(words)} words, {len(t2s)} traditional -> {G2P_ASSETS}")

    G2P_GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for name in road_names():
        try:
            _, phones, _, _ = chinese_to_phones(name)
        except IndexError:
            continue  # Genie's own tone sandhi fails on a few names; they make no reference.
        rows.append(f"{name}\t{' '.join(phones)}\n")
    G2P_GOLDEN.write_text("".join(rows), encoding="utf-8")
    print(f"golden: {len(rows)} road names -> {G2P_GOLDEN}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    voice = sub.add_parser("voice", help="export one voice")
    voice.add_argument("--model-dir", required=True)
    voice.add_argument("--ref-audio", required=True)
    voice.add_argument("--ref-text", required=True)
    voice.add_argument("--ref-language", default="Japanese")
    voice.add_argument("--out", required=True)
    sub.add_parser("g2p", help="write the Chinese G2P tables and expected phones")
    args = parser.parse_args()
    {"voice": export_voice, "g2p": export_g2p}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
