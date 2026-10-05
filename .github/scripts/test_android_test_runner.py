"""Check CI selection and failure propagation without an Android installation."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


RUNNER = Path(__file__).with_name("run-android-tests.sh")


class AndroidTestRunnerTest(unittest.TestCase):
    def run_runner(self, selector="", gradle_status=0, adb_status=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradlew").write_text(
                '#!/bin/bash\nprintf "%s\\n" "$@" > gradle-args\n'
                f"exit {gradle_status}\n"
            )
            (root / "gradlew").chmod(0o755)
            (root / "adb").write_text(
                '#!/bin/bash\nprintf "%s\\n" "$*"\n' + f"exit {adb_status}\n"
            )
            (root / "adb").chmod(0o755)
            result = subprocess.run(
                ["bash", str(RUNNER)], cwd=root, capture_output=True, text=True,
                env={**os.environ, "PATH": f"{root}:{os.environ['PATH']}",
                     "ANDROID_TEST_CLASS": selector}, check=False,
            )
            args = (root / "gradle-args").read_text().splitlines() \
                if (root / "gradle-args").exists() else []
            artifacts = [path.name for path in (root / "android-test-diagnostics").glob("*")]
            return result, args, artifacts

    def test_default_keeps_full_suite(self):
        result, args, artifacts = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["connectedCheck", "--stacktrace"], args)
        self.assertIn("crash.txt", artifacts)

    def test_selection_is_one_literal_argument(self):
        selector = "org.example.PlaybackTest#first,org.example.PlaybackTest#second"
        result, args, _ = self.run_runner(selector)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([":app:connectedDebugAndroidTest", "--stacktrace",
                          f"-Pandroid.testInstrumentationRunnerArguments.class={selector}"], args)

    def test_failed_test_stays_failed_after_diagnostic_collection(self):
        result, _, artifacts = self.run_runner(gradle_status=42)
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertIn("activities.txt", artifacts)
        self.assertIn("windows.txt", artifacts)

    def test_invalid_selection_never_runs_gradle(self):
        result, args, _ = self.run_runner("Example#test;echo unexpected")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], args)

    def test_failed_diagnostics_preserve_gradle_failure(self):
        result, _, _ = self.run_runner(gradle_status=42, adb_status=1)
        self.assertEqual(42, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
