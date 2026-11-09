"""Guard SEQ's shared updater dependency and host integration against regression."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class SharedUpdaterDependencyTest(unittest.TestCase):
    def test_latest_pinned_updater_version(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        versions = re.findall(r'implementation\("dev\.n2k02:updater:([^"]+)"\)', gradle)
        self.assertEqual(["0.1.32"], versions,
                         "SEQ must pin the shared updater 0.1.32 (no stale/dynamic dependency)")

    def test_updates_page_uses_shared_controller_and_install_guard(self):
        updater = (ROOT / "app/src/main/java/com/n2k02/seq/SeqUpdater.kt").read_text(
            encoding="utf-8")
        activity = (ROOT / "app/src/main/java/com/n2k02/seq/MainActivity.kt").read_text(
            encoding="utf-8")
        self.assertIn('repository="N2K02/SEQ"', updater)
        self.assertIn("UpdateInstallGuard", updater)
        self.assertIn("!SeqRuntime.status.value.running", updater)
        self.assertIn("PublishedUpdateSource", updater)
        self.assertIn("N2K02UpdateScreen(", activity)
        self.assertIn("installGuard = SeqUpdater.installGuard", activity)


if __name__ == "__main__":
    unittest.main()
