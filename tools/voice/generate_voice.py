#!/usr/bin/env python3
"""Generate a character's voice fragments with a GPT-SoVITS server.

Reads fragments from app/src/main/assets/voice/phrases.json and writes
<pack>/voice/<language>/<fragment_id>.wav. A fragment is skipped when its file
exists and its text and voice settings are unchanged since the last run
(tracked in <language>/manifest.json).

Two server types (config `backend`): "api_v2" for GPT-SoVITS api_v2.py, and
"openai" for an OpenAI-style POST /v1/audio/speech (such as Genie-TTS behind
gpu-switch), whose key is read from the environment variable named in the config.

Without --character the bundled Wayla pack in app/src/main/assets/characters/wayla
is written: only use voices you recorded or may publish there, the repo is public.
With --character the pack goes to tools/voice/out/<id>/ (gitignored) and is zipped
for importing on the phone.

Usage:
    python3 tools/voice/generate_voice.py                 # generate what changed
    python3 tools/voice/generate_voice.py --dry-run       # show what would be sent
    python3 tools/voice/generate_voice.py --only limit_50 --force
    python3 tools/voice/generate_voice.py --prune         # also delete unused files
    python3 tools/voice/generate_voice.py --character stylet --name Stylet   # importable zip
"""

import argparse
import array
import hashlib
import io
import json
import os
import shutil
import sys
import tomllib
import urllib.error
import urllib.request
import wave
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PHRASES = REPO_ROOT / "app" / "src" / "main" / "assets" / "voice" / "phrases.json"
BUNDLED_PACK = REPO_ROOT / "app" / "src" / "main" / "assets" / "characters" / "wayla"
OUT_DIR = Path(__file__).resolve().parent / "out"
DEFAULT_CONFIG = Path(__file__).resolve().parent / "config.toml"
MANIFEST = "manifest.json"


def build_request(config, language, text):
    voice = config["voice"][language]
    if config.get("backend") == "openai":
        return {"input": text, "language": voice["language"], "voice": voice["voice"]}
    request = dict(config.get("params", {}))
    request.update({
        "text": text,
        "text_lang": voice["text_lang"],
        "ref_audio_path": voice["ref_audio_path"],
        "prompt_text": voice["prompt_text"],
        "prompt_lang": voice["prompt_lang"],
        "media_type": "wav",
        "streaming_mode": False,
    })
    return request


def fragment_hash(request, trim):
    """Changes whenever the text, the reference voice, a parameter or the trimming changes."""
    payload = json.dumps({"request": request, "trim": trim}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def trim_silence(wav_bytes, threshold, keep_ms):
    """Cut leading and trailing silence, keeping keep_ms on each side. 16-bit PCM only."""
    with wave.open(io.BytesIO(wav_bytes)) as src:
        params = src.getparams()
        frames = src.readframes(params.nframes)
    if params.sampwidth != 2:
        return wav_bytes

    samples = array.array("h", frames)
    if sys.byteorder != "little":
        samples.byteswap()
    channels = params.nchannels
    limit = threshold * 32767
    loud = [i for i in range(0, len(samples), channels)
            if max(abs(s) for s in samples[i:i + channels]) > limit]
    if not loud:
        return wav_bytes

    keep = int(params.framerate * keep_ms / 1000) * channels
    start = max(0, loud[0] - keep)
    end = min(len(samples), loud[-1] + channels + keep)
    trimmed = samples[start:end]
    if sys.byteorder != "little":
        trimmed.byteswap()

    out = io.BytesIO()
    with wave.open(out, "wb") as dst:
        dst.setnchannels(channels)
        dst.setsampwidth(2)
        dst.setframerate(params.framerate)
        dst.writeframes(trimmed.tobytes())
    return out.getvalue()


def post_tts(server_url, request, timeout):
    body = json.dumps(request, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        server_url.rstrip("/") + "/tts",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            data = response.read()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}") from e
    if not data.startswith(b"RIFF"):
        raise RuntimeError(f"server did not return a WAV file: {data[:300]!r}")
    return data


def post_speech(server_url, request, timeout, api_key):
    """OpenAI-style text to speech; returns WAV bytes."""
    body = json.dumps(request, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = urllib.request.Request(server_url.rstrip("/") + "/v1/audio/speech", data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            data = response.read()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}") from e
    if not data.startswith(b"RIFF"):
        raise RuntimeError(f"server did not return a WAV file: {data[:300]!r}")
    return data


def fetcher(config):
    """The request function for the configured backend."""
    if config.get("backend") == "openai":
        api_key = os.environ.get(config.get("api_key_env", "WAYLA_TTS_API_KEY"), "")
        return lambda url, request, timeout: post_speech(url, request, timeout, api_key)
    return post_tts


def write_pack(pack_dir, character_id, name, credit=None):
    """Writes character.json next to voice/ and zips the pack; returns the zip path."""
    manifest = {"id": character_id, "name": name}
    if credit:
        manifest["credit"] = credit
    (pack_dir / "character.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    archive = shutil.make_archive(str(pack_dir), "zip", root_dir=pack_dir)
    return Path(archive)


def load_manifest(directory):
    path = directory / MANIFEST
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def save_manifest(directory, manifest):
    text = json.dumps(dict(sorted(manifest.items())), ensure_ascii=False, indent=2) + "\n"
    (directory / MANIFEST).write_text(text, encoding="utf-8")


def generate(config, fragments, voice_dir, fetch, *, force=False, only=None, dry_run=False, prune=False,
             log=print):
    """Returns {"generated": [...], "skipped": [...], "failed": {id: reason}, "pruned": [...]}."""
    stats = {"generated": [], "skipped": [], "failed": {}, "pruned": []}
    trim = config.get("trim", {})
    timeout = config.get("timeout_seconds", 120)

    for language in config["languages"]:
        directory = voice_dir / language
        manifest = load_manifest(directory)

        for fragment_id, texts in fragments.items():
            if only and fragment_id not in only:
                continue
            key = f"{language}/{fragment_id}"
            text = texts.get(language)
            if text is None:
                stats["failed"][key] = f"no {language} text"
                continue

            request = build_request(config, language, text)
            digest = fragment_hash(request, trim)
            target = directory / f"{fragment_id}.wav"
            if not force and target.exists() and manifest.get(fragment_id, {}).get("sha256") == digest:
                stats["skipped"].append(key)
                continue

            if dry_run:
                log(f"would generate {key}: {json.dumps(request, ensure_ascii=False)}")
                stats["generated"].append(key)
                continue

            try:
                audio = fetch(config["server_url"], request, timeout)
                if trim:
                    audio = trim_silence(audio, trim.get("threshold", 0.02), trim.get("keep_ms", 60))
            except Exception as e:  # keep going; report every failure at the end
                stats["failed"][key] = str(e)
                log(f"FAILED {key}: {e}")
                continue

            directory.mkdir(parents=True, exist_ok=True)
            target.write_bytes(audio)
            manifest[fragment_id] = {"sha256": digest, "text": text}
            save_manifest(directory, manifest)  # after each file, so an interrupted run resumes
            stats["generated"].append(key)
            log(f"generated {key}: {text}")

        if prune and not dry_run and directory.exists():
            for wav in sorted(directory.glob("*.wav")):
                if wav.stem not in fragments:
                    wav.unlink()
                    manifest.pop(wav.stem, None)
                    stats["pruned"].append(f"{language}/{wav.stem}")
                    log(f"pruned {language}/{wav.stem}")
            save_manifest(directory, manifest)

    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--force", action="store_true", help="regenerate even if unchanged")
    parser.add_argument("--only", nargs="+", metavar="ID", help="only these fragment ids")
    parser.add_argument("--dry-run", action="store_true", help="print requests without calling the server")
    parser.add_argument("--prune", action="store_true", help="delete audio for fragments no longer in the table")
    parser.add_argument("--character", metavar="ID", help="write an importable pack to tools/voice/out/ID")
    parser.add_argument("--name", help="character name shown in the app (with --character)")
    parser.add_argument("--credit", help="credit line for the about screen (with --character)")
    args = parser.parse_args()

    if not args.config.exists():
        sys.exit(f"{args.config} not found; copy config.example.toml and fill it in")
    config = tomllib.loads(args.config.read_text(encoding="utf-8"))
    fragments = json.loads(PHRASES.read_text(encoding="utf-8"))["fragments"]

    unknown = set(args.only or []) - fragments.keys()
    if unknown:
        sys.exit(f"unknown fragment ids: {', '.join(sorted(unknown))}")

    pack_dir = OUT_DIR / args.character if args.character else BUNDLED_PACK
    stats = generate(config, fragments, pack_dir / "voice", fetcher(config), force=args.force, only=args.only,
                     dry_run=args.dry_run, prune=args.prune)
    if args.character and not args.dry_run:
        archive = write_pack(pack_dir, args.character, args.name or args.character, args.credit)
        print(f"pack: {archive}")

    print(f"\ngenerated: {len(stats['generated'])}  skipped (unchanged): {len(stats['skipped'])}  "
          f"failed: {len(stats['failed'])}  pruned: {len(stats['pruned'])}")
    for key, reason in stats["failed"].items():
        print(f"  failed {key}: {reason}")
    sys.exit(1 if stats["failed"] else 0)


if __name__ == "__main__":
    main()
