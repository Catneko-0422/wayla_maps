#!/usr/bin/env python3
"""Convert the NPA speed camera open dataset into the app's asset format.

Source: data.gov.tw dataset 7320 "測速執法設置點" (National Police Agency),
licensed under the Open Government Data License, version 1.0.

Usage:
    python3 tools/convert_speed_cameras.py              # download and convert
    python3 tools/convert_speed_cameras.py --input raw.csv
"""

import argparse
import csv
import datetime
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

SOURCE_URL = (
    "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/"
    "EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/"
    "6165E37C-8F52-4808-8D9B-E0D484416240/download"
)
SOURCE = "內政部警政署「測速執法設置點」(data.gov.tw/dataset/7320)"
LICENSE = "政府資料開放授權條款－第1版"

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RAW = REPO_ROOT / "tools" / "data" / "speed_cameras_raw.csv"
DEFAULT_OUTPUT = REPO_ROOT / "app" / "src" / "main" / "assets" / "speed_cameras.json"

# Taiwan including Penghu, Kinmen and Matsu.
LAT_RANGE = (21.8, 26.5)
LNG_RANGE = (118.0, 122.1)

COMPASS = {
    "北": 0, "東北": 45, "東": 90, "東南": 135,
    "南": 180, "西南": 225, "西": 270, "西北": 315,
}
# Two-character directions first so 東北 is not read as 東.
DIR = "(東北|東南|西北|西南|北|東|南|西)"

BOTH_WAYS = re.compile(r"雙向|相向|多向|南北|北南|東西|西東")
FROM_TO = re.compile(DIR + r"[向往]" + DIR)
TOWARDS = re.compile(r"往" + DIR + r"(?=$|方向|向|[(（])")
HEADING_ONLY = re.compile(r"^" + DIR + r"向(?=$|[(（])")
SPLIT_LIMITS = re.compile(r"^" + DIR + r"向(\d+)" + DIR + r"向(\d+)$")


def parse_bearing(direct):
    """Return the travel direction (degrees, 0 = north) the camera enforces.

    None means both directions, or a direction that cannot be resolved
    (for example a place name such as 往大溪方向); the app treats both the
    same way and only relies on the camera being ahead of the rider.
    """
    text = direct.strip()
    if BOTH_WAYS.search(text):
        return None
    destinations = {m.group(2) for m in FROM_TO.finditer(text)}
    if len(destinations) > 1:
        return None
    if destinations:
        return COMPASS[destinations.pop()]
    if "北上" in text:
        return 0
    if "南下" in text:
        return 180
    if m := TOWARDS.search(text):
        return COMPASS[m.group(1)]
    if m := HEADING_ONLY.search(text):
        return COMPASS[m.group(1)]
    return None


def parse_limit(value):
    value = value.strip()
    return int(value) if value.isdigit() else None


def expand_row(row):
    """Yield one camera per enforced direction.

    A few rows give a separate limit per direction (南向60北向70); those are
    split into two cameras.
    """
    direct = row["direct"].strip()
    base = {
        "lat": round(float(row["Latitude"]), 6),
        "lng": round(float(row["Longitude"]), 6),
        "city": row["CityName"].strip(),
        "district": row["RegionName"].strip(),
        "address": row["Address"].strip(),
        "direction": direct,
    }
    if m := SPLIT_LIMITS.match(direct):
        for d, limit in ((m.group(1), m.group(2)), (m.group(3), m.group(4))):
            yield {**base, "bearing": COMPASS[d], "limit": int(limit)}
        return
    yield {**base, "bearing": parse_bearing(direct), "limit": parse_limit(row["limit"])}


def in_taiwan(camera):
    return (LAT_RANGE[0] <= camera["lat"] <= LAT_RANGE[1]
            and LNG_RANGE[0] <= camera["lng"] <= LNG_RANGE[1])


def convert(rows):
    cameras = []
    seen = set()
    rejected = 0
    for row in rows:
        try:
            expanded = list(expand_row(row))
        except (KeyError, ValueError):
            rejected += 1
            continue
        for camera in expanded:
            if not in_taiwan(camera):
                rejected += 1
                continue
            key = (camera["lat"], camera["lng"], camera["bearing"], camera["limit"])
            if key in seen:
                continue
            seen.add(key)
            cameras.append(camera)
    return cameras, rejected


def read_rows(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    # The second line repeats the header in Chinese.
    if rows and rows[0].get("CityName") == "設置縣市":
        rows = rows[1:]
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", type=Path, help="local CSV instead of downloading")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--version", default=datetime.date.today().isoformat(),
                        help="dataset version stored in the asset (default: today)")
    args = parser.parse_args()

    raw = args.input
    if raw is None:
        raw = DEFAULT_RAW
        raw.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {SOURCE_URL}", file=sys.stderr)
        urllib.request.urlretrieve(SOURCE_URL, raw)

    rows = read_rows(raw)
    cameras, rejected = convert(rows)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    asset = {
        "version": args.version,
        "source": SOURCE,
        "license": LICENSE,
        # Lets the app skip re-importing when its weekly download matches this CSV.
        "source_sha256": hashlib.sha256(raw.read_bytes()).hexdigest(),
        "cameras": cameras,
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(asset, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")

    unresolved = [c["direction"] for c in cameras if c["bearing"] is None
                  and not BOTH_WAYS.search(c["direction"])]
    print(f"rows read:            {len(rows)}")
    print(f"cameras written:      {len(cameras)}")
    print(f"rejected rows:        {rejected}")
    print(f"both directions:      {sum(c['bearing'] is None for c in cameras) - len(unresolved)}")
    print(f"unresolved direction: {len(unresolved)}")
    print(f"no speed limit:       {sum(c['limit'] is None for c in cameras)}")
    print(f"output:               {args.output}")


if __name__ == "__main__":
    main()
