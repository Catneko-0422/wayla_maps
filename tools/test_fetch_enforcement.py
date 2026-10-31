import unittest

from fetch_enforcement import pick_csv_url


def resource(fmt, description, url, fields=("地點", "座標緯度", "座標經度")):
    return {
        "resourceFormat": fmt,
        "resourceDescription": description,
        "resourceDownloadUrl": url,
        "resourceField": [{"name": name} for name in fields],
    }


class PickCsvUrlTest(unittest.TestCase):
    def test_latest_announcement_wins(self):
        distributions = [
            resource("JSON", "第4次公告", "json4"),
            resource("CSV", "設置地點第3次公告", "csv3"),
            resource("CSV", "設置地點第4次公告", "csv4"),
            resource("CSV", "設置地點第1次公告", "csv1"),
        ]
        self.assertEqual(pick_csv_url(distributions), "csv4")

    def test_first_csv_without_announcements(self):
        distributions = [
            resource("XML", "地點", "xml"),
            resource("CSV", "科技執法取締地點", "first"),
            resource("CSV", "區間平均速率執法", "second"),
        ]
        self.assertEqual(pick_csv_url(distributions), "first")

    def test_prefers_csv_with_coordinates(self):
        distributions = [
            resource("CSV", "設置地點", "titles_only", fields=("Seq", "Column1")),
            resource("CSV", "設置地點", "with_coordinates"),
        ]
        self.assertEqual(pick_csv_url(distributions), "with_coordinates")

    def test_no_csv(self):
        self.assertIsNone(pick_csv_url([resource("PDF", "一覽表", "pdf")]))


if __name__ == "__main__":
    unittest.main()
