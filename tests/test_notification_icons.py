"""Prevent opaque launcher icons and inconsistent notification small icons."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"
RESOURCE = "app/src/main/res/drawable/ic_stat_app.xml"
EXPECTED = "com.n2k02.seq.R.drawable.ic_stat_app"

class NotificationIconTest(unittest.TestCase):
    def test_small_icons_use_dedicated_resource(self):
        calls = []
        for source in ROOT.rglob("*.kt"):
            if "/src/main/" in source.as_posix():
                calls.extend(re.findall(r"setSmallIcon\(\s*([^)]*?)\s*\)", source.read_text()))
        self.assertTrue(calls, "No notification icon calls found")
        self.assertEqual({EXPECTED}, set(calls))

    def test_resource_is_monochrome_vector(self):
        vector = ET.parse(ROOT / RESOURCE).getroot()
        self.assertEqual("vector", vector.tag)
        for key in ("width", "height"):
            self.assertEqual("24dp", vector.get(A + key))
        for key in ("viewportWidth", "viewportHeight"):
            self.assertEqual("24", vector.get(A + key))
        paths = list(vector)
        self.assertTrue(paths)
        for path in paths:
            self.assertEqual("path", path.tag)
            self.assertEqual("#FFFFFFFF", path.get(A + "fillColor"))
            self.assertTrue(path.get(A + "pathData"))
        # No adaptive icon, bitmap, group background or launcher dependency.
        self.assertNotIn("@", ET.tostring(vector, encoding="unicode"))

if __name__ == "__main__":
    unittest.main()
