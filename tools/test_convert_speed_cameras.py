import unittest

from convert_speed_cameras import convert, expand_row, parse_bearing


def row(direct, limit="50", lat="25.0", lng="121.5"):
    return {
        "CityName": "臺北市", "RegionName": "中正區", "Address": "測試路",
        "Longitude": lng, "Latitude": lat, "direct": direct, "limit": limit,
    }


class ParseBearingTest(unittest.TestCase):
    def test_from_to_uses_destination(self):
        self.assertEqual(parse_bearing("南向北"), 0)
        self.assertEqual(parse_bearing("北往南"), 180)
        self.assertEqual(parse_bearing("西南向東北"), 45)
        self.assertEqual(parse_bearing("東北向西南"), 225)
        self.assertEqual(parse_bearing("東往西向"), 270)

    def test_towards(self):
        self.assertEqual(parse_bearing("往南"), 180)
        self.assertEqual(parse_bearing("往北方向"), 0)
        self.assertEqual(parse_bearing("介壽路往北"), 0)

    def test_heading_only(self):
        self.assertEqual(parse_bearing("南向"), 180)
        self.assertEqual(parse_bearing("北向(區間測速)"), 0)

    def test_northbound_southbound(self):
        self.assertEqual(parse_bearing("北上車道"), 0)
        self.assertEqual(parse_bearing("南下方向"), 180)
        self.assertEqual(parse_bearing("往北上方向"), 0)

    def test_notes_are_ignored(self):
        self.assertEqual(parse_bearing("南向北(超速闖紅燈)"), 0)
        self.assertEqual(parse_bearing("東往西(區間測速)"), 270)

    def test_both_directions(self):
        for text in ("雙向", "南北雙向", "東西向", "南北", "多向", "往東西向",
                     "東往西(雙向)", "南北雙向兼南下闖紅燈", "南向北(區間測速) 北向南(區間測速)"):
            self.assertIsNone(parse_bearing(text), text)

    def test_place_names_are_unresolved(self):
        for text in ("往大溪方向", "往南崁方向", "往南桃園交流道方向", "往下山"):
            self.assertIsNone(parse_bearing(text), text)


class ConvertTest(unittest.TestCase):
    def test_split_limits_become_two_cameras(self):
        cameras = list(expand_row(row("南向60北向70", limit="")))
        self.assertEqual([(c["bearing"], c["limit"]) for c in cameras], [(180, 60), (0, 70)])

    def test_rejects_points_outside_taiwan(self):
        cameras, rejected = convert([row("雙向", lat="0", lng="0")])
        self.assertEqual(cameras, [])
        self.assertEqual(rejected, 1)

    def test_drops_exact_duplicates(self):
        cameras, _ = convert([row("南向北"), row("南向北")])
        self.assertEqual(len(cameras), 1)

    def test_missing_limit_is_none(self):
        cameras, _ = convert([row("雙向", limit="")])
        self.assertIsNone(cameras[0]["limit"])


if __name__ == "__main__":
    unittest.main()
