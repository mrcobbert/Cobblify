"""Game-free tests for cobdev's pure helpers. Run: python3 -I tools/devloop/test_cobdev.py (or cobdev selftest)."""
import contextlib
import importlib.machinery
import importlib.util
import io
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
        self.assertLess(t, -15)  # left side higher; medians sit 45% of the width apart

    def test_hud_box_over_one_column_does_not_fake_a_tilt(self):
        # Cobblify's HUD (Map / Height Limit box) sits at the left edge just above the horizon.
        w, h, rows = scene(200, 120, lambda x: 60)
        rows = [bytearray(r) for r in rows]
        for y in range(48, 64):
            for x in range(0, 24):
                rows[y][3 * x:3 * x + 3] = b"\x30\x30\x30"
        self.assertEqual(cobdev.tilt((w, h, [bytes(r) for r in rows])), 0)

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
               "\t   |    |-- net_ornithemc_conditional-mixin 1.0.0",
               "\t   |    \\-- org_quiltmc_parsers_json 0.2.1",
               "\t   \\-- osl-entrypoints 0.6.1",
               "\t- pylon 0.2.8",
               "[20:00:01] [main/INFO] (FabricLoader) next"]
        header, ids = cobdev.parse_mod_list(log)
        self.assertIn("Loading 4 mods", header)
        self.assertEqual(ids, ["bedwarsqol", "osl", "osl-core", "net_ornithemc_conditional-mixin",
                               "org_quiltmc_parsers_json", "osl-entrypoints", "pylon"])

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


class SmokeSave(unittest.TestCase):
    """The smoke clears entities and terrain, so it must only ever touch its own save."""

    def test_only_the_smoke_save_is_accepted(self):
        cobdev.require_smoke_save({"ok": True, "save": "cobdev"})
        for result in ({"ok": True, "save": "My Build World"}, {"ok": True}, None):
            with self.assertRaises(cobdev.ForeignSave):
                cobdev.require_smoke_save(result)

    def test_world_step_sends_nothing_into_another_save(self):
        sent = []

        class Fake(cobdev.Smoke):
            def call(self, path, params=None, post=None, timeout=60):
                if path == "/command":
                    sent.append(post["command"])
                return {"ok": True, "save": "My Build World"} if path == "/world" else {"ok": True}

            def status(self):
                return {"inWorld": True, "onServer": False, "responsive": True}

        real = cobdev.api_quiet
        cobdev.api_quiet = lambda *a, **k: {"inWorld": True, "responsive": True}
        try:
            with self.assertRaises(cobdev.ForeignSave):
                cobdev.smoke_world(Fake("dev", "test"))
        finally:
            cobdev.api_quiet = real
        self.assertEqual(sent, [])


def flat(w, h, rgb):
    return w, h, [bytes(rgb) * w for _ in range(h)]


def paint(img, box, rgb):
    """A copy of img with box (x0, y0, x1, y1, inclusive) filled with rgb."""
    w, h, rows = img
    rows = [bytearray(r) for r in rows]
    for y in range(box[1], box[3] + 1):
        for x in range(box[0], box[2] + 1):
            rows[y][3 * x:3 * x + 3] = bytes(rgb)
    return w, h, [bytes(r) for r in rows]


def see_through(rgb):
    return tuple(round(c * (1 - 0x6F / 255.0)) for c in rgb)


class HudLookHelpers(unittest.TestCase):
    """The HL step's pure helpers, on synthetic frames."""

    def test_ring_visits_each_edge_pixel_once(self):
        pts = cobdev.ring((0, 0, 4, 3), 0)
        self.assertEqual(len(pts), len(set(pts)))
        self.assertEqual(len(pts), 2 * 5 + 2 * 2)
        self.assertEqual(set(cobdev.ring((0, 0, 4, 3), 1)), {(1, 1), (2, 1), (3, 1), (1, 2), (2, 2), (3, 2)})
        self.assertEqual(cobdev.ring((0, 0, 1, 1), 1), [])
        self.assertEqual(cobdev.ring((2, 2, 2, 2), 0), [(2, 2)])

    def test_see_through_ratio(self):
        self.assertTrue(cobdev.see_through_ok(see_through(SKY), SKY))
        self.assertTrue(cobdev.see_through_ok((69, 94, 145), SKY))  # within 2
        self.assertFalse(cobdev.see_through_ok((26, 26, 26), SKY))  # 0xD0121212 over the sky
        self.assertFalse(cobdev.see_through_ok(SKY, SKY))           # no panel

    def test_counts(self):
        img = paint(flat(10, 8, SKY), (2, 2, 4, 3), (63, 63, 63))
        self.assertEqual(cobdev.count_exact(img, (0, 0, 9, 7), (63, 63, 63)), 6)
        self.assertEqual(cobdev.count_exact(img, (3, 0, 9, 7), (63, 63, 63)), 4)
        self.assertEqual(cobdev.count_near(img, (0, 0, 9, 7), (60, 66, 63), 3), 6)
        self.assertEqual(cobdev.count_near(img, (0, 0, 9, 7), (60, 66, 63), 2), 0)
        self.assertEqual(cobdev.xs_of(img, (0, 0, 9, 7), (63, 63, 63)), (2, 4))
        self.assertEqual(cobdev.ys_of(img, (0, 0, 9, 7), (63, 63, 63)), (2, 3))
        self.assertIsNone(cobdev.xs_of(img, (0, 0, 9, 7), (1, 2, 3)))

    def test_bbox_diff_is_exact(self):
        a = flat(20, 12, SKY)
        b = paint(paint(a, (3, 4, 5, 4), GRASS), (11, 9, 11, 10), GRASS)
        self.assertEqual(cobdev.bbox_diff(a, b), (3, 4, 11, 10))
        self.assertEqual(cobdev.bbox_diff(a, b, (0, 0, 8, 11)), (3, 4, 5, 4))
        self.assertIsNone(cobdev.bbox_diff(a, a))
        self.assertIsNone(cobdev.bbox_diff(a, flat(21, 12, SKY)))

    def test_changed_runs_split_at_unchanged_lines(self):
        a = flat(30, 20, SKY)
        b = paint(paint(a, (2, 3, 8, 6), GRASS), (12, 4, 20, 9), GRASS)
        box = cobdev.bbox_diff(a, b)
        self.assertEqual(cobdev.changed_runs(a, b, box, "x"), [(2, 3, 8, 6), (12, 4, 20, 9)])
        self.assertEqual(cobdev.changed_runs(a, b, box, "y"), [(2, 3, 20, 9)])
        c = paint(paint(a, (2, 1, 8, 4), GRASS), (2, 7, 8, 9), GRASS)
        self.assertEqual(cobdev.changed_runs(a, c, cobdev.bbox_diff(a, c), "y"), [(2, 1, 8, 4), (2, 7, 8, 9)])

    def test_inside_disjoint_grow(self):
        self.assertTrue(cobdev.inside((2, 2, 4, 4), (0, 0, 4, 4)))
        self.assertFalse(cobdev.inside((2, 2, 5, 4), (0, 0, 4, 4)))
        self.assertFalse(cobdev.inside(None, (0, 0, 4, 4)))
        self.assertTrue(cobdev.disjoint([(0, 0, 4, 4), (5, 0, 9, 4), (0, 5, 4, 9)]))
        self.assertFalse(cobdev.disjoint([(0, 0, 4, 4), (4, 4, 9, 9)]))
        self.assertEqual(cobdev.grow((5, 5, 6, 6), 2), (3, 3, 8, 8))
        self.assertEqual(cobdev.box_size((3, 4, 5, 9)), (3, 6))

    def test_vanilla_advances_match_the_unit_tests(self):
        self.assertEqual(cobdev.vanilla_width("Jump Boost V"), 66)
        self.assertEqual(cobdev.vanilla_width("0:38"), 20)
        self.assertEqual(cobdev.hl_text_box_width(["150", " FPS"]), 47)
        self.assertEqual(cobdev.hl_text_box_width(["9", "3", " CPS"], True), 48)
        self.assertEqual(cobdev.hl_text_box_width(["--", " ms"]), 35)
        self.assertEqual(cobdev.hl_expected_panel("keystrokes", 2), (112, 134))
        self.assertEqual(cobdev.hl_expected_panel("armor", 2), (44, 44))
        self.assertIsNone(cobdev.hl_expected_panel("sessionstats", 2))


class HudLookPressedCap(unittest.TestCase):
    """HL-keys reads the W cap through Edit HUD's faint frame fill (0x0DFFFFFF over the preview)."""

    @staticmethod
    def frame(cap_rgb, letter_rgb=None, sf=2):
        img = flat(160, 160, SKY)
        box = (10, 10, 10 + 56 * sf - 1, 10 + 67 * sf - 1)
        img = paint(img, (10 + 19 * sf, 10, 10 + 37 * sf - 1, 10 + 18 * sf - 1), cap_rgb)
        if letter_rgb:
            img = paint(img, (10 + 26 * sf, 10 + 5 * sf, 10 + 28 * sf - 1, 10 + 12 * sf - 1), letter_rgb)
        return img, box

    def test_tinted_pressed_cap_passes(self):
        img, box = self.frame((195, 222, 255), (30, 30, 30))  # pale blue and #121212 under 5% white
        accent, letter = cobdev.hl_pressed_cap(img, box, 2)
        self.assertGreaterEqual(accent, (16 * 2) ** 2)
        self.assertGreater(letter, 0)

    def test_exact_colours_pass_and_an_idle_cap_fails(self):
        img, box = self.frame((192, 220, 255), (18, 18, 18))
        self.assertGreaterEqual(cobdev.hl_pressed_cap(img, box, 2)[0], (16 * 2) ** 2)
        idle, box = self.frame(see_through(SKY))
        self.assertEqual(cobdev.hl_pressed_cap(idle, box, 2), (0, 0))


class HudLookDryRun(unittest.TestCase):
    """The HL step against a fake game: every sub-check runs to the end without raising (its checks fail)."""

    def test_every_sub_check_runs(self):
        backdrop = flat(160, 120, SKY)
        drawn = paint(paint(backdrop, (10, 10, 60, 40), see_through(SKY)), (20, 20, 30, 24), (255, 255, 255))
        calls = []

        class Fake(cobdev.Smoke):
            def call(self, path, params=None, post=None, timeout=60):
                calls.append(path)
                if path == "/world":
                    return {"ok": True, "save": "cobdev"}
                if path == "/has":
                    return {"ok": True, "present": True}
                if path == "/field":
                    name = post["path"].rsplit(".", 1)[-1]
                    value = {"state": "READY", "source": "NETWORK"}.get(name)
                    if value is None:
                        value = 1.0 if name.endswith("Scale") else 0 if name[-1:] in "XY" else False
                    return {"ok": True, "value": value}
                if path == "/invoke":
                    return {"ok": True, "value": 0 if post["path"].endswith(("requests", "published")) else True}
                return {"ok": True}

            def shot(self, tag, wait=300):
                self.step["shots"].append(tag)
                return tag, backdrop if "none" in tag else drawn

            def status(self):
                return {"scaleFactor": 2, "guiScaleSetting": 2, "inWorld": True, "responsive": True}

        real_sleep, real_quiet = cobdev.time.sleep, cobdev.api_quiet
        cobdev.time.sleep = lambda t: None
        cobdev.api_quiet = lambda *a, **k: {"inWorld": True, "responsive": True, "scaleFactor": 2}
        restarts = []
        try:
            s = Fake("dev", "test")
            with contextlib.redirect_stdout(io.StringIO()):  # the step prints every check
                cobdev.smoke_hud_look(s, "com.bedwarsqol.BedwarsQol.config", lambda: restarts.append(1))
        finally:
            cobdev.time.sleep, cobdev.api_quiet = real_sleep, real_quiet
        ids = [st["id"] for st in s.report["steps"]]
        self.assertEqual(ids, ["HL", "HL-bg", "HL-persist", "HL-potion", "HL-mapinfo", "HL-keys", "HL-text",
                               "HL-edit", "HL-scale", "HL-font", "HL-icons-cold", "HL-icons-warm"])
        self.assertEqual([st["id"] for st in s.report["steps"] if "error" in st], [])
        self.assertEqual(len(restarts), 3)  # persist, icons cold, icons warm
        self.assertIn("/scale", calls)


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
