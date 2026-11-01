# tools/voice

Generates the fixed voice prompts (CLAUDE.md voice pipeline rule 1) of a
character with a GPT-SoVITS `api_v2.py` server, or an OpenAI-style
`/v1/audio/speech` server (`backend = "openai"`, key from an environment variable).

```bash
cp tools/voice/config.example.toml tools/voice/config.toml   # then fill it in (gitignored)
python3 tools/voice/generate_voice.py --dry-run   # check the requests first
python3 tools/voice/generate_voice.py             # generate new or changed fragments
cd tools/voice && python3 -m unittest             # tests
```

Options: `--force` regenerates even unchanged fragments, `--only ID [ID ...]`
limits the run, `--prune` deletes audio for fragments no longer in the table.

## Voice packs

Each character is a pack: `character.json` plus `voice/<language>/<fragment_id>.wav`
(and optionally a 3D model). Without options the bundled Wayla pack in
`app/src/main/assets/characters/wayla/` is written; the repo is public, so only use
a voice you recorded or may publish there.

Any other voice, such as one trained from a game character for personal use, goes
into an importable pack that never enters git:

```bash
python3 tools/voice/generate_voice.py --character stylet --name Stylet
# -> tools/voice/out/stylet.zip; copy it to the phone and import it in Settings
```

Phrases a pack lacks are spoken by the phone's TTS, so a partial pack works.

A pack can speak another language than the phone: set `"voiceLanguage": "ja"` in its
`character.json` and generate that language (it needs texts in `phrases.json`). A licensed
character kept in the gitignored `app/src/local/` gets its voice in place with
`--pack-dir app/src/local/assets/characters/<id>`.

## How it works

- Input: the `fragments` of `app/src/main/assets/voice/phrases.json`, the phrase
  table the app also reads. A fragment is one short clip (前方三百公尺, 測速照相,
  限速五十); the app concatenates fragments into sentences at playback time.
- Output: `<pack>/voice/<language>/<fragment_id>.wav`, with leading and
  trailing silence trimmed (see `[trim]`) so joined fragments flow.
- `<language>/manifest.json` stores a hash of each fragment's text, voice and
  parameters. A fragment is skipped when its file exists and the hash matches, so
  changing the reference audio or a parameter regenerates everything it affects.
- Fragment text uses Chinese numerals (三百, not 300) for consistent pronunciation.
- Only languages listed in `languages` are generated; the app speaks the others
  with system TTS.

## Adding a phrase

1. Add fragments and the phrase to `phrases.json` (`PhraseCatalogTest` checks that
   every phrase resolves for every slot value).
2. Run the script; only the new fragments are sent to the server.

## Voices

Only use a voice the author recorded or has permission to use. Do not train or ship
voices cloned from real people or existing characters without permission.

## Road names on the phone

Road names cannot be recorded ahead, so a pack may name a server voice for them
(`"roadNames": {"voice": "miapla", "language": "zh"}` in `character.json`). The app asks the
speech server (`TTS_SERVER_URL` and `TTS_API_KEY` in `local.properties`) for the whole route's
roads when guidance starts, and keeps the clips on the phone.

For riding without a connection, a pack can also carry the voice model itself in an `ondevice/`
folder, made from the server's Genie-TTS model with `export_ondevice.py` (Python 3.11 with
`genie-tts==2.0.2`; see its docstring). The phone then makes the names with ONNX Runtime.
`export_ondevice.py g2p` regenerates the Chinese pronunciation tables in
`app/src/main/assets/g2p_zh` and the expected readings the unit test compares against.
Like the voice itself, an exported model of someone else's voice stays out of git.
