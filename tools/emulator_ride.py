#!/usr/bin/env python3
"""Drive the Android emulator's GPS along a straight line past a point, one fix per second.

Useful for testing speed camera alerts without leaving the desk:

    python3 tools/emulator_ride.py 25.054731 121.52277              # 中山北路2段長春路口, northbound
    python3 tools/emulator_ride.py LAT LNG --kmh 50 --before 800 --after 200

Uses `adb emu geo fix`, which carries speed but not course: the emulator always reports a
bearing of 0, so only northbound rides (--bearing 0) produce the heading the app expects.
Set ANDROID_SERIAL when more than one device is connected.
"""

import argparse
import math
import os
import shutil
import subprocess
import time

EARTH_RADIUS_M = 6_371_000.0
KNOTS_PER_MPS = 1.943844


def offset(lat, lng, meters, bearing_deg):
    """Point `meters` away from (lat, lng) along `bearing_deg`."""
    d = meters / EARTH_RADIUS_M
    b = math.radians(bearing_deg)
    p1, l1 = math.radians(lat), math.radians(lng)
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(b))
    l2 = l1 + math.atan2(math.sin(b) * math.sin(d) * math.cos(p1), math.cos(d) - math.sin(p1) * math.sin(p2))
    return math.degrees(p2), math.degrees(l2)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("lat", type=float, help="target latitude (e.g. a speed camera)")
    parser.add_argument("lng", type=float, help="target longitude")
    parser.add_argument("--bearing", type=float, default=0.0, help="travel direction in degrees (default north)")
    parser.add_argument("--kmh", type=float, default=40.0)
    parser.add_argument("--before", type=float, default=1200.0, help="start this many meters before the target")
    parser.add_argument("--after", type=float, default=300.0, help="stop this many meters past the target")
    args = parser.parse_args()

    adb = shutil.which("adb") or os.path.expanduser("~/Android/Sdk/platform-tools/adb")
    mps = args.kmh / 3.6
    distance = -args.before
    while distance <= args.after:
        lat, lng = offset(args.lat, args.lng, distance, args.bearing)
        subprocess.run(
            [adb, "emu", "geo", "fix", f"{lng:.6f}", f"{lat:.6f}", "10", "8", f"{mps * KNOTS_PER_MPS:.1f}"],
            check=True,
            capture_output=True,
        )
        print(f"{distance:+7.0f} m  {lat:.6f},{lng:.6f}", flush=True)
        distance += mps
        time.sleep(1)


if __name__ == "__main__":
    main()
