# tools/voice

Generates the fixed voice prompts (CLAUDE.md voice pipeline rule 1) with a
GPT-SoVITS `api_v2.py` server.

```bash
cp tools/voice/config.example.toml tools/voice/config.toml   # then fill it in (gitignored)
python3 tools/voice/generate_voice.py --dry-run   # check the requests first
python3 tools/voice/generate_voice.py             # generate new or changed fragments
cd tools/voice && python3 -m unittest             # tests
```

Options: `--force` regenerates even unchanged fragments, `--only ID [ID ...]`
limits the run, `--prune` deletes audio for fragments no longer in the table.

## How it works

- Input: the `fragments` of `app/src/main/assets/voice/phrases.json`, the phrase
  table the app also reads. A fragment is one short clip (前方三百公尺, 測速照相,
  限速五十); the app concatenates fragments into sentences at playback time.
- Output: `app/src/main/assets/voice/<language>/<fragment_id>.wav`, with leading and
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
