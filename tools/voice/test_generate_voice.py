import array
import io
import json
import tempfile
import unittest
import wave
from pathlib import Path

from generate_voice import build_request, fragment_hash, generate, load_manifest, trim_silence, write_pack

CONFIG = {
    "server_url": "http://tts.local:9880",
    "languages": ["zh-TW"],
    "params": {"top_k": 5, "speed_factor": 1.0},
    "trim": {"threshold": 0.02, "keep_ms": 10},
    "voice": {"zh-TW": {"text_lang": "zh", "ref_audio_path": "/ref.wav",
                        "prompt_text": "你好", "prompt_lang": "zh"}},
}
FRAGMENTS = {
    "speed_camera": {"zh-TW": "測速照相", "en": "Speed camera"},
    "limit_50": {"zh-TW": "限速五十", "en": "limit 50"},
}


def make_wav(silence_ms=200, sound_ms=100, rate=1000):
    silence = [0] * (rate * silence_ms // 1000)
    sound = [10000] * (rate * sound_ms // 1000)
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(array.array("h", silence + sound + silence).tobytes())
    return out.getvalue()


def frame_count(wav_bytes):
    with wave.open(io.BytesIO(wav_bytes)) as w:
        return w.getnframes()


class FakeServer:
    def __init__(self, fail=()):
        self.requests = []
        self.fail = set(fail)

    def __call__(self, url, request, timeout):
        self.requests.append(request)
        if request["text"] in self.fail:
            raise RuntimeError("HTTP 400")
        return make_wav()


class BuildRequestTest(unittest.TestCase):
    def test_merges_params_and_voice(self):
        request = build_request(CONFIG, "zh-TW", "測速照相")

        self.assertEqual(request["text"], "測速照相")
        self.assertEqual(request["text_lang"], "zh")
        self.assertEqual(request["ref_audio_path"], "/ref.wav")
        self.assertEqual(request["top_k"], 5)
        self.assertEqual(request["media_type"], "wav")

    def test_hash_follows_text_voice_and_trim(self):
        base = fragment_hash(build_request(CONFIG, "zh-TW", "a"), CONFIG["trim"])
        other_voice = json.loads(json.dumps(CONFIG))
        other_voice["voice"]["zh-TW"]["ref_audio_path"] = "/other.wav"

        self.assertEqual(base, fragment_hash(build_request(CONFIG, "zh-TW", "a"), CONFIG["trim"]))
        self.assertNotEqual(base, fragment_hash(build_request(CONFIG, "zh-TW", "b"), CONFIG["trim"]))
        self.assertNotEqual(base, fragment_hash(build_request(other_voice, "zh-TW", "a"), CONFIG["trim"]))
        self.assertNotEqual(base, fragment_hash(build_request(CONFIG, "zh-TW", "a"), {"keep_ms": 1}))


class TrimSilenceTest(unittest.TestCase):
    def test_cuts_silence_but_keeps_padding(self):
        trimmed = trim_silence(make_wav(silence_ms=200, sound_ms=100, rate=1000), 0.02, 10)

        self.assertEqual(frame_count(trimmed), 100 + 2 * 10)

    def test_all_silent_audio_is_left_alone(self):
        wav = make_wav(silence_ms=100, sound_ms=0)

        self.assertEqual(trim_silence(wav, 0.02, 10), wav)


class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.voice_dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_generate(self, server, **kwargs):
        return generate(CONFIG, FRAGMENTS, self.voice_dir, server, log=lambda *_: None, **kwargs)

    def test_generates_trimmed_files_and_manifest(self):
        stats = self.run_generate(FakeServer())

        self.assertEqual(sorted(stats["generated"]), ["zh-TW/limit_50", "zh-TW/speed_camera"])
        wav = (self.voice_dir / "zh-TW" / "speed_camera.wav").read_bytes()
        self.assertLess(frame_count(wav), frame_count(make_wav()))
        self.assertEqual(load_manifest(self.voice_dir / "zh-TW")["limit_50"]["text"], "限速五十")

    def test_skips_unchanged_fragments(self):
        self.run_generate(FakeServer())
        server = FakeServer()

        stats = self.run_generate(server)

        self.assertEqual(server.requests, [])
        self.assertEqual(len(stats["skipped"]), 2)

    def test_regenerates_changed_text_missing_file_and_force(self):
        self.run_generate(FakeServer())
        (self.voice_dir / "zh-TW" / "limit_50.wav").unlink()
        changed = {**FRAGMENTS, "speed_camera": {"zh-TW": "測速照相機"}}
        server = FakeServer()

        generate(CONFIG, changed, self.voice_dir, server, log=lambda *_: None)

        self.assertEqual(sorted(r["text"] for r in server.requests), ["測速照相機", "限速五十"])
        server = FakeServer()
        self.run_generate(server, force=True, only=["limit_50"])
        self.assertEqual([r["text"] for r in server.requests], ["限速五十"])

    def test_failures_are_reported_and_not_recorded(self):
        stats = self.run_generate(FakeServer(fail={"限速五十"}))

        self.assertIn("zh-TW/limit_50", stats["failed"])
        self.assertNotIn("limit_50", load_manifest(self.voice_dir / "zh-TW"))
        self.assertFalse((self.voice_dir / "zh-TW" / "limit_50.wav").exists())

    def test_dry_run_writes_nothing(self):
        server = FakeServer()

        stats = self.run_generate(server, dry_run=True)

        self.assertEqual(server.requests, [])
        self.assertEqual(len(stats["generated"]), 2)
        self.assertFalse((self.voice_dir / "zh-TW").exists())

    def test_prune_removes_unused_audio(self):
        self.run_generate(FakeServer())
        (self.voice_dir / "zh-TW" / "old_phrase.wav").write_bytes(make_wav())

        stats = self.run_generate(FakeServer(), prune=True)

        self.assertEqual(stats["pruned"], ["zh-TW/old_phrase"])
        self.assertFalse((self.voice_dir / "zh-TW" / "old_phrase.wav").exists())


class OpenAIBackendTest(unittest.TestCase):
    def test_request_is_text_language_and_voice(self):
        config = {"backend": "openai", "voice": {"zh-TW": {"voice": "stylet", "language": "zh"}}}

        self.assertEqual(
            build_request(config, "zh-TW", "前方三百公尺"),
            {"input": "前方三百公尺", "language": "zh", "voice": "stylet"},
        )


class WritePackTest(unittest.TestCase):
    def test_writes_manifest_and_zip(self):
        import zipfile
        with tempfile.TemporaryDirectory() as tmp:
            pack = Path(tmp) / "stylet"
            (pack / "voice" / "zh-TW").mkdir(parents=True)
            (pack / "voice" / "zh-TW" / "passed.wav").write_bytes(b"RIFF")

            archive = write_pack(pack, "stylet", "Stylet", credit="voice: personal use")

            manifest = json.loads((pack / "character.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest, {"id": "stylet", "name": "Stylet", "credit": "voice: personal use"})
            with zipfile.ZipFile(archive) as z:
                self.assertIn("character.json", z.namelist())
                self.assertIn("voice/zh-TW/passed.wav", z.namelist())


if __name__ == "__main__":
    unittest.main()
