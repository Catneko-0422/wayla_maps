#!/usr/bin/env python3
"""Download the traffic enforcement (科技執法) datasets bundled with the app.

Reads the source list from app/src/main/assets/enforcement/sources.json, finds the current
CSV of each data.gov.tw dataset, saves it unchanged as assets/enforcement/<key>.csv and
writes manifest.json with the version date and checksums. The app parses the raw files
itself (EnforcementCsvParser), so there is no conversion step here.
"""

import argparse
import datetime
import hashlib
import json
import re
import ssl
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "enforcement"
# Some county servers omit their TWCA intermediate certificate; the app trusts the same files.
EXTRA_CA_FILES = sorted((REPO_ROOT / "app" / "src" / "main" / "res" / "raw").glob("twca_*.pem"))

DATASET_API = "https://data.gov.tw/api/v2/rest/dataset/{}"
ANNOUNCEMENT = re.compile(r"第(\d+)次")
COORDINATE = re.compile(r"緯|Latitude", re.IGNORECASE)
TIMEOUT_S = 60


def pick_csv_url(distributions):
    """The CSV resource to use: among CSVs with coordinate columns (if any have them), the
    latest numbered announcement (第N次公告), else the first.

    Mirrors EnforcementSourceResolver.pickCsvUrl in the app.
    """
    csvs = [d for d in distributions if d.get("resourceFormat", "").upper() == "CSV"]
    with_coordinates = [d for d in csvs if any(COORDINATE.search(f.get("name", "")) for f in d.get("resourceField", []))]
    csvs = with_coordinates or csvs
    if not csvs:
        return None

    def announcement(d):
        m = ANNOUNCEMENT.search(d.get("resourceDescription", ""))
        return int(m.group(1)) if m else -1

    best = max(csvs, key=announcement)
    return (best if announcement(best) >= 0 else csvs[0])["resourceDownloadUrl"]


def ssl_context():
    context = ssl.create_default_context()
    for path in EXTRA_CA_FILES:
        context.load_verify_locations(cafile=str(path))
    return context


def fetch(url, context):
    request = urllib.request.Request(url, headers={"User-Agent": "WaylaMaps-tools"})
    with urllib.request.urlopen(request, timeout=TIMEOUT_S, context=context) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", nargs="*", help="source keys to refresh (default: all)")
    args = parser.parse_args()

    sources = json.loads((ASSET_DIR / "sources.json").read_text(encoding="utf-8"))["sources"]
    manifest_path = ASSET_DIR / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"files": {}}
    context = ssl_context()
    failed = []

    for source in sources:
        key = source["key"]
        if args.only and key not in args.only:
            continue
        try:
            meta = json.loads(fetch(DATASET_API.format(source["datasetId"]), context))
            url = pick_csv_url(meta["result"]["distribution"])
            if url is None:
                raise ValueError("no CSV resource")
            data = fetch(url, context)
        except Exception as e:  # noqa: BLE001 - report every source, keep going
            print(f"{key}: FAILED {e}", file=sys.stderr)
            failed.append(key)
            continue
        (ASSET_DIR / f"{key}.csv").write_bytes(data)
        manifest["files"][key] = {"url": url, "sha256": hashlib.sha256(data).hexdigest()}
        print(f"{key}: {len(data)} bytes from {url}")

    manifest["version"] = datetime.date.today().isoformat()
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if failed:
        print(f"Kept the previous files for: {', '.join(failed)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
