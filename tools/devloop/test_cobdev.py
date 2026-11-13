"""Game-free tests for cobdev's pure helpers. Run: python3 -I tools/devloop/test_cobdev.py (or cobdev selftest)."""
import importlib.machinery
import importlib.util
import os
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.realpath(__file__))
_loader = importlib.machinery.SourceFileLoader("cobdev", os.path.join(HERE, "cobdev"))
_spec = importlib.util.spec_from_loader("cobdev", _loader)
cobdev = importlib.util.module_from_spec(_spec)
_loader.exec_module(cobdev)

SKY, FOG, GRASS = (120, 166, 255), (192, 216, 255), (90, 140, 60)


def scene(w, h, horizon_at):
    """Sky above horizon_at(x), ground below: a stand-in for a flat-world frame."""
    rows = []
    for y in range(h):
        row = bytearray()
        for x in range(w):
            hy = horizon_at(x)
            row += bytes(SKY if y < hy - 4 else FOG if y < hy else GRASS)
        rows.append(row)
    return w, h, [bytes(r) for r in rows]


class Png(unittest.TestCase):
    def test_round_trip_through_every_filter_free_path(self):
        img = scene(40, 30, lambda x: 15)
        w, h, rows = cobdev.png_read(cobdev.png_write(*img))
        self.assertEqual((w, h), (40, 30))
        self.assertEqual(rows, img[2])

    def test_rejects_non_png(self):
        with self.assertRaises(ValueError):
            cobdev.png_read(b"GIF89a....")


class Analysis(unittest.TestCase):
    def test_level_horizon_has_no_tilt(self):
        self.assertEqual(cobdev.tilt(scene(200, 120, lambda x: 60)), 0)

    def test_rolled_horizon_tilts(self):
        t = cobdev.tilt(scene(200, 120, lambda x: 40 + x // 5))
        self.assertLess(t, -20)  # left side higher than the right

    def test_no_sky_means_no_answer(self):
        img = (10, 10, [bytes(GRASS * 10)] * 10)
        self.assertIsNone(cobdev.tilt(img))

    def test_blank_frame_has_no_spread(self):
        self.assertLess(cobdev.spread((32, 32, [bytes(SKY * 32)] * 32), step=4), 1)
        self.assertGreater(cobdev.spread(scene(64, 64, lambda x: 32), step=4), 8)

    def test_diff_reports_fraction_and_box(self):
        a = scene(64, 64, lambda x: 32)
        rows = [bytearray(r) for r in a[2]]
        for y in range(8, 16):
            for x in range(40, 48):
                rows[y][3 * x:3 * x + 3] = b"\xff\x00\x00"
        b = (64, 64, [bytes(r) for r in rows])
        frac, box = cobdev.diff(a, b, step=1)
        self.assertAlmostEqual(frac, 64 / 4096)
        self.assertEqual(box, (40, 8, 47, 15))
        self.assertEqual(cobdev.diff(a, a, step=1), (0.0, None))


class ModList(unittest.TestCase):
    def test_top_level_and_nested_entries(self):
        log = ["[20:00:00] [main/INFO] (FabricLoader) Loading 4 mods:",
               "\t- bedwarsqol 0.18.2",
               "\t- osl 0.23.1",
               "\t   |-- osl-core 0.12.0+mc14w27a-mc1.14.4",
               "\t   \\-- osl-entrypoints 0.6.1",
               "\t- pylon 0.2.8",
               "[20:00:01] [main/INFO] (FabricLoader) next"]
        header, ids = cobdev.parse_mod_list(log)
        self.assertIn("Loading 4 mods", header)
        self.assertEqual(ids, ["bedwarsqol", "osl", "osl-core", "osl-entrypoints", "pylon"])

    def test_missing_block(self):
        self.assertEqual(cobdev.parse_mod_list(["nothing"]), (None, []))


class LaunchCfg(unittest.TestCase):
    CFG = ("commonProperties\n"
           "\tfabric.development=true\n"
           "\tlog4j.configurationFile=/Users/agent/wt/ornithe/.gradle/loom-cache/log4j.xml,/Users/agent/wt/x.xml\n"
           "clientArgs\n"
           "\t--assetIndex\n\t1.8.9-1.8\n\t--assetsDir\n\t/Users/agent/.gradle/caches/fabric-loom/assets\n"
           "clientProperties\n"
           "\tjava.library.path=/Users/agent/wt/ornithe/.gradle/loom-cache/natives/1.8.9\n"
           "\torg.lwjgl.librarypath=/Users/agent/wt/ornithe/.gradle/loom-cache/natives/1.8.9\n")

    def mapper(self):
        m = cobdev.Mapper("/Users/agent/wt", "/Users/Shared/cobblify-dev/runtimes/r1")
        return m

    def test_rewrites_paths_adds_client_args_and_drops_lwjgl2_natives(self):
        old = cobdev.GRADLE_CACHES
        cobdev.GRADLE_CACHES = "/Users/agent/.gradle/caches"
        try:
            out = cobdev.rewrite_launch_cfg(self.CFG, self.mapper(), drop_natives=True)
        finally:
            cobdev.GRADLE_CACHES = old
        self.assertNotIn("/Users/agent/", out)
        self.assertIn("/Users/Shared/cobblify-dev/runtimes/r1/wt/ornithe/.gradle/loom-cache/log4j.xml,"
                      "/Users/Shared/cobblify-dev/runtimes/r1/wt/x.xml", out)
        self.assertIn("/Users/Shared/cobblify-dev/cache/gradle/fabric-loom/assets", out)
        self.assertIn("clientArgs\n\t--username\n\tCobblifyDev\n\t--width\n\t1280\n\t--height\n\t720\n\t--assetIndex", out)
        self.assertNotIn("library.path", out)
        self.assertNotIn("librarypath", out)

    def test_path_regex_stops_at_commas(self):
        self.assertEqual(cobdev.CFG_PATH.findall("a=/Users/x/a.xml,/Users/x/b.xml"), ["/Users/x/a.xml", "/Users/x/b.xml"])

    def test_cfg_value_helpers(self):
        self.assertEqual(cobdev.cfg_client_arg(self.CFG, "--assetIndex"), "1.8.9-1.8")
        self.assertEqual(cobdev.cfg_values(self.CFG, "java.library.path"),
                         ["/Users/agent/wt/ornithe/.gradle/loom-cache/natives/1.8.9"])

    def test_lwjgl2_is_recognised_by_maven_group(self):
        self.assertTrue(cobdev.is_lwjgl2("/c/modules-2/files-2.1/org.lwjgl.lwjgl/lwjgl/2.9.4/x/lwjgl.jar"))
        self.assertFalse(cobdev.is_lwjgl2("/wt/.gradle/loom-cache/remapped_mods/pylon-0.2.8.jar"))


class ReleaseJar(unittest.TestCase):
    def jar(self, entries):
        fd, path = tempfile.mkstemp(suffix=".jar")
        os.close(fd)
        with zipfile.ZipFile(path, "w") as z:
            for name, body in entries.items():
                z.writestr(name, body)
        self.addCleanup(os.remove, path)
        return path

    def test_clean_jar_passes(self):
        path = self.jar({"fabric.mod.json": '{"id": "bedwarsqol", "version": "1.0"}', "com/bedwarsqol/A.class": "x"})
        self.assertEqual(cobdev.check_release_jar(path, "1.0"), [])

    def test_bridge_classes_fail(self):
        path = self.jar({"fabric.mod.json": '{"id": "bedwarsqol"}', "cobdev/bridge/Bridge.class": "x"})
        self.assertTrue(any("cobdev" in p for p in cobdev.check_release_jar(path)))

    def test_nested_runtime_mod_fails(self):
        path = self.jar({"fabric.mod.json": '{"id": "bedwarsqol"}', "META-INF/jars/pylon-0.2.8.jar": "x"})
        self.assertTrue(any("nested" in p for p in cobdev.check_release_jar(path)))

    def test_wrong_mod_id_fails(self):
        path = self.jar({"fabric.mod.json": '{"id": "cobdev-bridge"}'})
        self.assertTrue(cobdev.check_release_jar(path))


class Pins(unittest.TestCase):
    def test_every_pin_is_complete(self):
        for p in cobdev.PINS:
            self.assertEqual(len(p["sha512"]), 128, p["name"])
            self.assertTrue(p["url"].startswith("https://"), p["name"])
            self.assertGreater(p["size"], 0)

    def test_osl_only_in_prod_and_argentum_not_in_dev(self):
        dev = {p["name"] for p in cobdev.pins_for("dev")}
        prod = {p["name"] for p in cobdev.pins_for("prod")}
        self.assertEqual(dev, {"pylon", "sarcio", "slipstream"})
        self.assertEqual(prod, {"pylon", "argentum", "sarcio", "slipstream", "osl"})


if __name__ == "__main__":
    unittest.main(verbosity=1)
