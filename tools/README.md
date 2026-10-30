# tools

## Speed camera data

`convert_speed_cameras.py` turns the government speed camera dataset into
`app/src/main/assets/speed_cameras.json`, which the app imports into Room on
first launch (and again whenever the asset `version` changes).

```bash
python3 tools/convert_speed_cameras.py                  # download the latest CSV and convert
python3 tools/convert_speed_cameras.py --input raw.csv  # convert a local CSV
cd tools && python3 -m unittest test_convert_speed_cameras
```

The raw CSV is saved to `tools/data/` (gitignored). Bump nothing by hand: the
asset `version` defaults to today's date, so a regenerated asset is re-imported.

### Source and license

- 內政部警政署「測速執法設置點」, <https://data.gov.tw/dataset/7320>
- Licensed under 政府資料開放授權條款－第1版 (Open Government Data License, version 1.0).
  Attribution is required; the app shows the source on screen.

Do not mix in data from commercial apps (see CLAUDE.md).

### Fields

| CSV column | Meaning | Asset field |
|---|---|---|
| `CityName` | 設置縣市 | `city` |
| `RegionName` | 設置市區鄉鎮 | `district` |
| `Address` | 設置地址 | `address` |
| `DeptNm`, `BranchNm` | 管轄警局／分局 | (dropped) |
| `Latitude`, `Longitude` | WGS84 | `lat`, `lng` |
| `direct` | 拍攝方向 (free text) | `direction` (raw) and `bearing` |
| `limit` | 速限 km/h | `limit` |

The dataset counts one row per enforced direction, and also includes red-light
cameras.

### How `direct` becomes `bearing`

`bearing` is the travel direction the camera enforces (0 = north, 90 = east).
`null` means both directions or a direction that could not be resolved; the app
then only checks that the camera is ahead of the rider.

| Text | Bearing |
|---|---|
| 雙向, 南北雙向, 東西向, 南北, 多向 … | `null` (both ways) |
| 南向北, 北往南, 西南向東北 (from → to) | destination |
| 北上…, 南下… | 0, 180 |
| 往北, 往南方向, 介壽路往北 | that direction |
| 南向, 北向 (alone) | that direction |
| 往大溪方向, 往南崁方向 (place names) | `null` (unresolved) |
| 南向60北向70 | split into two cameras with their own limits |

Notes such as (區間測速) or 兼闖紅燈 are ignored for the bearing; the original text
stays in `direction`. Rows with coordinates outside Taiwan are dropped (one row in
the 2026-10-31 data: 苗栗 台1線104.9公里, latitude 26.645).
