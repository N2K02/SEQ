import importlib.util
import pathlib
import unittest

module_path = pathlib.Path(__file__).with_name("check_updater_manifest.py")
spec = importlib.util.spec_from_file_location("check_updater_manifest", module_path)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ManifestValidationTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "tag": "v0.3.0-exp-53",
            "apkUrl": "https://github.com/N2K02/SEQ/releases/download/v0.3.0-exp-53/app-debug.apk",
            "sha256": "a" * 64,
            "apkSize": 92240268
        }

    def test_existing_asset(self):
        checker.validate(self.manifest, ["app-debug.apk"])

    def test_catches_asset_label_mismatch_causing_404(self):
        self.manifest["apkUrl"] = self.manifest["apkUrl"].replace(
            "app-debug.apk", "SEQ-0.3.0-exp-53.apk"
        )
        with self.assertRaisesRegex(ValueError, "missing asset"):
            checker.validate(self.manifest, ["app-debug.apk"])

    def test_tag_mismatch(self):
        self.manifest["tag"] = "v0.3.0-exp-999"
        with self.assertRaisesRegex(ValueError, "disagree"):
            checker.validate(self.manifest, ["app-debug.apk"])


if __name__ == "__main__":
    unittest.main()
