#!/usr/bin/env python3
"""Run the shipped macOS installer against disposable profiles, never the real HOME."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

INSTALLER = Path(__file__).resolve().parents[1] / "lunar/dist/Install BedwarsQOL (Lunar).command"
MOD = "Cobblify-Lunar-0.18.2.jar"
AGENT = "Weave-Loader-Agent-1.3.3.jar"


class LunarInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cobblify-installer-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle with space"
        self.profile = self.root / "profile with space"
        self.bundle.mkdir()
        (self.profile / "Desktop").mkdir(parents=True)
        self.mods = self.profile / ".weave/mods"
        self.mods.mkdir(parents=True)
        (self.bundle / MOD).write_bytes(b"new mod")
        (self.bundle / AGENT).write_bytes(b"agent")
        # Redirect references in a fixture copy. Never assign HOME itself.
        self.script = self.bundle / "installer.command"
        self.script.write_text(INSTALLER.read_text().replace("$HOME", "$COBBLIFY_INSTALLER_TEST_PROFILE"))

    def run_installer(self, extra_env=None):
        env = dict(os.environ, COBBLIFY_INSTALLER_TEST_PROFILE=str(self.profile))
        env.update(extra_env or {})
        return subprocess.run(["/bin/bash", str(self.script)], input="\n", text=True,
                              capture_output=True, env=env, timeout=15)

    def assert_failed(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("  Done.", result.stdout)

    def test_clean_install_and_rerun(self):
        for _ in range(2):
            result = self.run_installer()
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((self.mods / MOD).read_bytes(), b"new mod")
            self.assertEqual((self.profile / ".weave" / AGENT).read_bytes(), b"agent")
            self.assertEqual(len(list(self.mods.glob("*.jar"))), 1)
            self.assertTrue((self.bundle / "Launch Lunar (Cobblify).command").is_file())
            self.assertTrue((self.profile / "Desktop/Launch Lunar (Cobblify).command").is_file())

    def test_upgrade_backs_up_old_release_and_preserves_other_mod(self):
        (self.mods / "Cobblify-Lunar-0.18.1.jar").write_bytes(b"old mod")
        (self.mods / "Other-Mod.jar").write_bytes(b"unrelated")
        result = self.run_installer()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(sorted(p.name for p in self.mods.glob("Cobblify-Lunar-*.jar")), [MOD])
        self.assertEqual((self.mods / "Other-Mod.jar").read_bytes(), b"unrelated")
        backups = self.profile / ".weave/cobblify-backups"
        self.assertTrue(any(p.is_file() and p.read_bytes() == b"old mod" for p in backups.rglob("*")))

    def test_conflicting_release_is_unchanged(self):
        for name in ("Cobblify-Lunar-0.19.0.jar", "Cobblify-Lunar-0.18.1-dev.4.jar",
                     "cobblify-lunar-custom.jar"):
            with self.subTest(name=name):
                path = self.mods / name
                path.write_bytes(b"keep this")
                result = self.run_installer()
                self.assert_failed(result)
                self.assertEqual(path.read_bytes(), b"keep this")
                self.assertFalse((self.mods / MOD).exists())
                path.unlink()

    def test_symlink_peer_and_destination_are_unchanged(self):
        target = self.root / "user-owned.jar"
        target.write_bytes(b"never change")
        for name in ("Cobblify-Lunar-0.18.1.jar", MOD):
            with self.subTest(name=name):
                path = self.mods / name
                path.symlink_to(target)
                try:
                    self.assert_failed(self.run_installer())
                    self.assertTrue(path.is_symlink())
                    self.assertEqual(target.read_bytes(), b"never change")
                finally:
                    path.unlink()
                    other = self.mods / MOD
                    if other.exists():
                        other.unlink()

    def test_installing_from_desktop(self):
        desktop = self.profile / "Desktop"
        for name in (MOD, AGENT):
            (desktop / name).write_bytes((self.bundle / name).read_bytes())
        self.script = desktop / "installer.command"
        self.script.write_text(INSTALLER.read_text().replace("$HOME", "$COBBLIFY_INSTALLER_TEST_PROFILE"))
        result = self.run_installer()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.mods / MOD).read_bytes(), b"new mod")

    def test_commit_failure_restores_agent_mod_and_launchers(self):
        old_mod = self.mods / "Cobblify-Lunar-0.18.1.jar"
        old_agent = self.profile / ".weave" / AGENT
        old_bundle = self.bundle / "Launch Lunar (Cobblify).command"
        old_desktop = self.profile / "Desktop/Launch Lunar (Cobblify).command"
        originals = {old_mod: b"old mod", old_agent: b"old agent",
                     old_bundle: b"old bundle launcher", old_desktop: b"old Desktop launcher"}
        for path, value in originals.items():
            path.write_bytes(value)
        wrappers = self.root / "bin"
        wrappers.mkdir()
        mv = wrappers / "mv"
        mv.write_text('#!/bin/bash\ncase "$1" in */.cobblify-install.*/desktop-launcher) exit 1;; esac\n'
                      'exec /bin/mv "$@"\n')
        mv.chmod(0o755)
        self.assert_failed(self.run_installer({"PATH": str(wrappers) + os.pathsep + os.environ["PATH"]}))
        for path, value in originals.items():
            self.assertEqual(path.read_bytes(), value)
        self.assertFalse((self.mods / MOD).exists())

    def test_symlink_mods_directory_is_unchanged(self):
        self.mods.rmdir()
        target = self.root / "user-mods"
        target.mkdir()
        self.mods.symlink_to(target, target_is_directory=True)
        self.assert_failed(self.run_installer())
        self.assertEqual(list(target.iterdir()), [])

    def test_failed_copy_does_not_report_success(self):
        old = self.mods / "Cobblify-Lunar-0.18.1.jar"
        old.write_bytes(b"old mod")
        self.mods.chmod(0o555)
        try:
            self.assert_failed(self.run_installer())
            self.assertEqual(old.read_bytes(), b"old mod")
        finally:
            self.mods.chmod(0o755)

    def test_corrupt_staging_leaves_old_release_untouched(self):
        old = self.mods / "Cobblify-Lunar-0.18.1.jar"
        old.write_bytes(b"old mod")
        wrappers = self.root / "bin"
        wrappers.mkdir()
        cp = wrappers / "cp"
        cp.write_text('#!/bin/bash\n/bin/cp "$@" || exit $?\nfor dest; do :; done\n'
                      'case "$dest" in *.jar) printf corrupt > "$dest";; esac\n')
        cp.chmod(0o755)
        self.assert_failed(self.run_installer({"PATH": str(wrappers) + os.pathsep + os.environ["PATH"]}))
        self.assertEqual(old.read_bytes(), b"old mod")
        self.assertFalse((self.mods / MOD).exists())

    def test_partial_backup_failure_restores_earlier_release(self):
        for version in ("0.18.0", "0.18.1"):
            (self.mods / ("Cobblify-Lunar-" + version + ".jar")).write_text(version)
        wrappers = self.root / "bin"
        wrappers.mkdir()
        mv = wrappers / "mv"
        mv.write_text('#!/bin/bash\ncase "$1" in */mods/Cobblify-Lunar-0.18.1.jar) exit 1;; esac\n'
                      'exec /bin/mv "$@"\n')
        mv.chmod(0o755)
        self.assert_failed(self.run_installer({"PATH": str(wrappers) + os.pathsep + os.environ["PATH"]}))
        for version in ("0.18.0", "0.18.1"):
            self.assertEqual((self.mods / ("Cobblify-Lunar-" + version + ".jar")).read_text(), version)
        self.assertFalse((self.mods / MOD).exists())


if __name__ == "__main__":
    unittest.main()
