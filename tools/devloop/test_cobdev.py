"""Game-free tests for cobdev's pure helpers. Run: python3 -I tools/devloop/test_cobdev.py (or cobdev selftest)."""
import contextlib
import importlib.machinery
import io
import importlib.util
import io
import json
import os
import tempfile
import unittest
import zipfile
from unittest import mock

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


def sky(w, h):
    return w, h, [bytes(SKY * w)] * h


def paint_fn(img, rect, fn):
    """A copy of img with fn(rgb) applied inside rect (x0, y0, x1, y1), through a PNG round trip."""
    w, h, rows = img
    rows = [bytearray(r) for r in rows]
    x0, y0, x1, y1 = rect
    for y in range(y0, y1):
        for x in range(x0, x1):
            rows[y][3 * x:3 * x + 3] = bytes(fn(tuple(rows[y][3 * x:3 * x + 3])))
    return cobdev.png_read(cobdev.png_write(w, h, [bytes(r) for r in rows]))


def black(alpha):
    return lambda c: tuple(int(v * (1 - alpha / 255.0)) for v in c)


class OverlayPixels(unittest.TestCase):
    """The overlay smoke's pixel helpers (OV steps), on synthetic frames."""

    def test_gui_box_to_device_rect(self):
        self.assertEqual(cobdev.gui_rect({"x": 10, "y": 20.5, "w": 30, "h": 9}, 2), (20, 41, 80, 59))
        self.assertEqual(cobdev.gui_rect((1.25, 0, 4, 4), 4), (5, 0, 21, 16))

    def test_drawn_bbox_is_exclusive_and_matches_the_box(self):
        ref = sky(80, 60)
        img = paint_fn(ref, (12, 8, 40, 30), black(0x50))
        drawn = cobdev.drawn_bbox(ref, img)
        self.assertEqual(drawn, (12, 8, 40, 30))
        self.assertTrue(cobdev.rect_close(drawn, cobdev.gui_rect((6, 4, 14, 11), 2), 2))
        self.assertFalse(cobdev.rect_close(drawn, (16, 8, 40, 30), 2))
        self.assertIsNone(cobdev.drawn_bbox(ref, ref))

    def test_fill_ratios_name_the_alpha(self):
        ref = sky(40, 40)
        img = paint_fn(ref, (0, 0, 20, 40), black(0x50))
        self.assertAlmostEqual(cobdev.fill_ratio(img, ref, (2, 2, 18, 38)), 1 - 0x50 / 255.0, delta=0.01)
        self.assertAlmostEqual(cobdev.fill_ratio(img, ref, (22, 2, 38, 38)), 1.0, delta=0.001)
        rows = paint_fn(paint_fn(ref, (0, 0, 40, 40), black(0x80)), (0, 10, 40, 20), black(0x20))
        list_r, row_r = 1 - 0x80 / 255.0, (1 - 0x80 / 255.0) * (1 - 0x20 / 255.0)
        self.assertAlmostEqual(cobdev.ratio_share(rows, ref, (0, 0, 40, 40), list_r), 0.75, delta=0.01)
        self.assertAlmostEqual(cobdev.ratio_share(rows, ref, (0, 0, 40, 40), row_r), 0.25, delta=0.01)

    def test_band_rows_finds_the_longest_fill_run(self):
        ref = sky(20, 100)
        img = paint_fn(paint_fn(ref, (0, 40, 20, 76), black(0x7F)), (0, 10, 20, 14), black(0x7F))
        self.assertEqual(cobdev.band_rows(img, ref, 5, 1 - 0x7F / 255.0), (40, 76))
        self.assertIsNone(cobdev.band_rows(ref, ref, 5, 0.5))
        # a band touching the bottom edge still ends there
        low = paint_fn(ref, (0, 90, 20, 100), black(0x7F))
        self.assertEqual(cobdev.band_rows(low, ref, 5, 1 - 0x7F / 255.0), (90, 100))

    def test_colour_dark_and_bright_pixels(self):
        ref = sky(30, 20)
        img = paint_fn(ref, (3, 3, 5, 10), lambda c: (255, 85, 85))
        img = paint_fn(img, (10, 3, 12, 10), lambda c: (255, 255, 255))
        img = paint_fn(img, (12, 4, 14, 11), lambda c: (20, 30, 60))  # a shadow beside the text
        self.assertEqual(cobdev.color_count(img, (0, 0, 30, 20), (255, 85, 85)), 14)
        self.assertEqual(cobdev.color_count(img, (6, 0, 30, 20), (255, 85, 85)), 0)
        self.assertEqual(cobdev.rightmost_bright(img, (0, 0, 30, 20)), 11)
        self.assertIsNone(cobdev.rightmost_bright(ref, (0, 0, 30, 20)))
        self.assertEqual(cobdev.dark_count(img, ref, (0, 0, 30, 20)), 14)


class OverlayScene(unittest.TestCase):
    """The OV steps' sample rects and their settings bookkeeping (review round 1, I1 and I2)."""

    def test_scoreboard_samples_are_never_empty(self):
        # P1-P3 boxes at GUI scale 2, incl. the small one (x 6, scale 0.7) whose old margin rect was empty
        for box, scale in (({"x": 423.0, "y": 107.0, "w": 56.0, "h": 37.0}, 1.0),
                           ({"x": 6.0, "y": 40.0, "w": 39.2, "h": 38.5}, 0.7),
                           ({"x": 390.0, "y": 150.0, "w": 84.0, "h": 55.5}, 1.5),
                           ({"x": 0.0, "y": 0.0, "w": 12.0, "h": 11.1}, 0.3)):
            for sf in (1, 2, 3):
                lines, title = cobdev.scoreboard_samples(box, scale, sf)
                for rect in (lines, title):
                    self.assertGreater(rect[2] - rect[0], 0, (box, scale, sf, rect))
                    self.assertGreater(rect[3] - rect[1], 0, (box, scale, sf, rect))
                # inside the left margin, left of where the text starts (2 px in)
                self.assertGreaterEqual(lines[0], int(box["x"] * sf))
                self.assertLess(lines[0], (box["x"] + 2 * scale) * sf)
                self.assertLessEqual(title[3], (box["y"] + 9 * scale) * sf + 1)
                self.assertGreaterEqual(lines[1], title[3])

    def test_a_fill_in_the_sample_measures(self):
        ref = sky(120, 200)
        box, scale, sf = {"x": 6.0, "y": 40.0, "w": 39.2, "h": 38.5}, 0.7, 2
        img = paint_fn(ref, cobdev.gui_rect(box, sf), black(0x50))
        for rect in cobdev.scoreboard_samples(box, scale, sf):
            self.assertAlmostEqual(cobdev.fill_ratio(img, ref, rect), 1 - 0x50 / 255.0, delta=0.01)

    def test_large_chat_placement_fits_the_window(self):
        # review round 2, I2: a 960x540 window at GUI scale 2 is 480 GUI px wide
        places = dict(cobdev.ov_chat_places(480))
        self.assertLessEqual(324 * places["p2"][3], 480 - 12)
        self.assertEqual(places["p1"], cobdev.OV_CHAT_PLACES[0][1])
        self.assertEqual(places["p3"], cobdev.OV_CHAT_PLACES[2][1])
        self.assertEqual(dict(cobdev.ov_chat_places(640))["p2"][3], 1.5)  # 1280x720 keeps the planned 1.5
        box = {"x": 0.0, "y": 162.0, "w": 324 * places["p2"][3], "h": 40.5}
        self.assertLess(cobdev.chat_margin_column(box, places["p2"][3], 2), 960)

    def test_samples_off_the_image_are_no_answer_not_a_crash(self):
        ref = sky(40, 30)
        self.assertIsNone(cobdev.ratio_at(ref, ref, 40, 5))
        self.assertIsNone(cobdev.ratio_at(ref, ref, 5, -1))
        self.assertIsNone(cobdev.band_rows(ref, ref, 966, 0.5))

    def fake(self, backgrounds):
        test = self

        class Fake(object):
            def __init__(self):
                self.bg, self.fields = dict(backgrounds), {}

            def call(self, path, params=None, post=None, timeout=60):
                if path == "/invoke":
                    name = post["path"].rsplit(".", 1)[1]
                    args = post["args"].split(cobdev.ARG_SEP)
                    if name == "hudBackground":
                        return {"value": self.bg.get(args[0], True)}
                    test.assertEqual("setHudBackground", name)
                    self.bg[args[0]] = args[1] == "true"
                return {"ok": True}

            def try_call(self, path, params=None, post=None, timeout=60):
                return self.call(path, params, post), None

            def field(self, path, value=None):
                if value is None:
                    return {"value": self.fields.get(path, True)}
                self.fields[path] = value
                return {"ok": True}

            def status(self):
                return {"guiScaleSetting": 3}

            def cmd(self, command):
                pass

            def probe(self, name, *args):
                self.probes = getattr(self, "probes", []) + [name]
                return {"ok": True}
        return Fake()

    def test_backgrounds_start_on_and_come_back_as_they_were(self):
        real = cobdev.time.sleep
        cobdev.time.sleep = lambda _: None
        try:
            s = self.fake({"scoreboard": False, "chat": True})
            scene = cobdev.OvScene(s, "com.bedwarsqol.BedwarsQol.config")
            scene.setup()
            self.assertEqual({"scoreboard": True, "tablist": True, "chat": True}, s.bg)
            # review round 2, I1: vanilla's chat options are normalised for the scene (restored by chat-heights restore)
            self.assertIn("chat-defaults", s.probes)
            scene.background("scoreboard", False)
            scene.background("chat", False)
            scene.background("scoreboard", True)
            scene.restore()
            self.assertEqual({"scoreboard": False, "tablist": True, "chat": True}, s.bg)
        finally:
            cobdev.time.sleep = real


class OverlaySmokeNoise(unittest.TestCase):
    """Triage of the first integrated smoke run (2026-11-14): checks that failed on correct rendering."""

    def stray(self, img, pts, rgb=(90, 140, 60)):
        for x, y in pts:
            img = paint_fn(img, (x, y, x + 1, y + 1), lambda c, v=rgb: v)
        return img

    def test_stray_pixels_do_not_stretch_a_drawn_box(self):
        ref = sky(200, 120)
        img = paint_fn(ref, (80, 10, 120, 20), black(0x80))          # the tab list
        img = self.stray(img, [(3, 100), (190, 5), (60, 110), (150, 90)])  # grass shimmer
        self.assertEqual(cobdev.quiet_bbox(ref, img), (80, 10, 120, 20))
        self.assertEqual(cobdev.box_width(cobdev.quiet_bbox(ref, img)), 40)
        self.assertEqual(cobdev.drawn_bbox(ref, img), (3, 5, 191, 111))  # the old box would be ~190 px wide
        self.assertIsNone(cobdev.quiet_bbox(ref, self.stray(ref, [(3, 100), (190, 5)])))

    def test_frames_match_allows_a_few_shimmering_pixels_only(self):
        ref = sky(200, 120)
        same, n = cobdev.frames_match(ref, self.stray(ref, [(3, 100), (190, 5), (60, 110), (150, 90)]))
        self.assertTrue(same)
        self.assertEqual(4, n)
        swirl = paint_fn(ref, (120, 50, 140, 70), lambda c: (90, 120, 140))  # a potion particle
        same, n = cobdev.frames_match(ref, swirl)
        self.assertFalse(same)
        self.assertEqual(400, n)

    def one_row_tab(self, background):
        """A 1-player tab list at scale 1.5, GUI scale 2: border, head, name text, ping bars."""
        ref = sky(400, 200)
        box = {"x": 50.0, "y": 20.0, "w": 138.0, "h": 15.0}
        rect = cobdev.gui_rect(box, 2)
        img = ref
        if background:
            img = paint_fn(img, rect, black(0x80))
            img = paint_fn(img, (rect[0] + 3, rect[1] + 3, rect[2] - 3, rect[3] - 3), black(0x20))
        img = paint_fn(img, (rect[0] + 3, rect[1] + 3, rect[0] + 27, rect[1] + 27), lambda c: (120, 80, 60))  # head
        img = paint_fn(img, (rect[0] + 30, rect[1] + 4, rect[2] - 30, rect[3] - 4), lambda c: (255, 255, 255))  # text
        img = paint_fn(img, (rect[2] - 26, rect[1] + 8, rect[2] - 4, rect[3] - 4), lambda c: (0, 255, 0))  # ping
        return ref, img, box

    def test_tab_background_off_on_a_short_list(self):
        ref, off, box = self.one_row_tab(False)
        # the old check: most of a one-row list is content, so "80% clear" can never hold
        self.assertLess(cobdev.ratio_share(off, ref, cobdev.gui_rect(box, 2), 1.0), 0.8)
        ok, detail = cobdev.tab_background_off(off, ref, box, 1.5, 2)
        self.assertTrue(ok, detail)
        ref, on, box = self.one_row_tab(True)
        ok, detail = cobdev.tab_background_off(on, ref, box, 1.5, 2)
        self.assertFalse(ok, detail)
        x = cobdev.tab_border_column(box, 1.5, 2)
        self.assertTrue(box["x"] * 2 <= x < (box["x"] + 1.5) * 2)


class ChatBandGaps(unittest.TestCase):
    """Full smoke 2026-11-14 11:56: at 1280x720 the crosshair's arm crosses the chat's margin column, which cut the
    open chat's 20-row band to 17 rows."""

    def chat(self, rows, crosshair_at=None):
        ref = sky(700, 720)
        box = {"x": 2.0, "y": 332.0 - 9 * rows, "w": 324.0, "h": 9.0 * rows}
        img = paint_fn(ref, cobdev.gui_rect(box, 2), black(0x7F))
        if crosshair_at is not None:  # a 1 GUI px crosshair arm (2 device px), inverted over the fill
            img = paint_fn(img, (630, crosshair_at, 650, crosshair_at + 2), lambda c: (255 - c[0], 255 - c[1], 255 - c[2]))
        return ref, img, box

    def test_a_crosshair_arm_does_not_split_the_band(self):
        ref, img, box = self.chat(20, crosshair_at=359)
        rows, top, bottom = cobdev.ov_chat_rows(None, img, ref, box, 2, 1.0)
        self.assertEqual(20, rows)
        self.assertEqual((304, 664), (top, bottom))

    def test_a_real_gap_still_ends_the_band(self):
        ref, img, box = self.chat(10)
        img = paint_fn(img, (0, 500, 700, 520), lambda c: (120, 166, 255))  # 10 GUI px of sky inside the band
        self.assertIsNotNone(cobdev.band_rows(img, ref, 648, 1 - 0x7F / 255.0))
        rows, top, bottom = cobdev.ov_chat_rows(None, img, ref, box, 2, 1.0)
        self.assertLess(rows, 10)


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


class Flags(unittest.TestCase):
    """--mods, --device and keyhold --raw reach the bridge as plain parameters (Edit HUD checks)."""

    def parse(self, *argv):
        with contextlib.redirect_stderr(io.StringIO()):  # argparse prints usage before SystemExit
            return cobdev.build_parser().parse_args(list(argv))

    def test_drag_with_mods_and_device(self):
        a = self.parse("drag", "10", "20", "--mods", "alt,shift", "--device")
        self.assertEqual(cobdev.input_params(a, ("x", "y")), {"x": 10, "y": 20, "mods": "alt,shift", "device": 1})

    def test_plain_input_sends_no_extras(self):
        a = self.parse("click", "5", "6")
        self.assertEqual(cobdev.input_params(a, ("x", "y", "button")), {"x": 5, "y": 6, "button": 0})
        a = self.parse("drag", "5", "6")
        self.assertEqual(cobdev.input_params(a, ("x", "y")), {"x": 5, "y": 6})

    def test_down_and_up_take_device_pixels(self):
        for cmd in ("down", "up"):
            a = self.parse(cmd, "1279", "0", "--device", "--button", "1")
            self.assertEqual(cobdev.input_params(a, ("x", "y", "button")), {"x": 1279, "y": 0, "button": 1, "device": 1})

    def test_ctrl_click(self):
        a = self.parse("click", "5", "6", "--mods", "ctrl")
        self.assertEqual(cobdev.input_params(a, ("x", "y", "button")), {"x": 5, "y": 6, "button": 0, "mods": "ctrl"})

    def test_click_and_key_have_no_device_flag(self):
        with self.assertRaises(SystemExit):
            self.parse("click", "1", "2", "--device")
        with self.assertRaises(SystemExit):
            self.parse("key", "z", "--device")

    def test_modifiers_are_whitelisted_and_normalised(self):
        self.assertEqual(self.parse("up", "1", "2", "--mods", " Shift, alt,shift").mods, "shift,alt")
        for bad in ("meta", "alt,cmd", "alt,,shift", ""):
            with self.assertRaises(SystemExit):
                self.parse("down", "1", "2", "--mods", bad)

    def test_key_letters_and_mods(self):
        a = self.parse("key", "z", "--mods", "ctrl")
        self.assertEqual(cobdev.key_params(a), {"code": 44, "mods": "ctrl"})
        self.assertEqual(cobdev.key_params(self.parse("key", "Y")), {"code": 21})
        self.assertEqual(cobdev.key_params(self.parse("key", "right", "--mods", "shift")), {"code": 205, "mods": "shift"})
        self.assertEqual(cobdev.key_params(self.parse("key", "57", "--char", " ")), {"code": 57, "char": " "})

    def test_keyhold_raw(self):
        a = self.parse("keyhold", "key.playerlist", "on", "--raw")
        self.assertEqual(cobdev.keyhold_params(a), {"name": "key.playerlist", "state": "on", "raw": 1})
        a = self.parse("keyhold", "key.forward", "off", "--raw")
        self.assertEqual(cobdev.keyhold_params(a), {"name": "key.forward", "state": "off", "raw": 1})

    def test_keyhold_binding_and_list(self):
        self.assertEqual(cobdev.keyhold_params(self.parse("keyhold", "Zoom", "on")), {"name": "Zoom", "state": "on"})
        self.assertEqual(cobdev.keyhold_params(self.parse("keyhold")), {})

    def test_keyhold_needs_name_and_state(self):
        for argv in (("keyhold", "--raw"), ("keyhold", "Zoom"), ("keyhold", "Zoom", "maybe", "--raw")):
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                cobdev.keyhold_params(self.parse(*argv))


class EditHudSmoke(unittest.TestCase):
    """A4-edithud puts back every field and the GUI scale it touched, whatever happens in between."""

    def run_step(self, probe_error=None, boxes=None):
        calls, fields = [], {}

        class Fake(cobdev.Smoke):
            def call(self, path, params=None, post=None, timeout=60):
                calls.append((path, params or post or {}))
                self.step["commands"].append({"call": path})
                if path == "/field":
                    if "value" in post:
                        fields[post["path"]] = post["value"]
                        return {"ok": True}
                    return {"ok": True, "value": fields.get(post["path"], False)}
                if path == "/probe":
                    if probe_error:
                        raise cobdev.ApiError(probe_error)
                    return {"ok": True, "scale": 2, "sw": 640, "sh": 360, "boxes": boxes or [],
                            "states": [{"id": b["id"]} for b in boxes or []], "editor": {"selectedId": None}}
                return {"ok": True}

            def has(self, cls, member=None):
                return True

            def status(self):
                return {"guiScaleSetting": 3}

            def shot(self, tag, wait=300):
                return "/dev/null", (1, 1, [b"\0\0\0"])

        s = Fake("dev", "test")
        cobdev.smoke_edithud(s, "com.bedwarsqol.BedwarsQol.config", "dev", checks=("E0",))
        return s, calls, fields

    def test_restores_fields_and_scale_after_a_pass(self):
        boxes = [{"id": i, "shown": True} for i in ("potion", "armor", "inventory", "diamondtimer", "emeraldtimer",
                                                    "keystrokes", "sessionstats", "heightlimit", "fps", "cps", "ping")]
        s, calls, fields = self.run_step(boxes=boxes)
        self.assertEqual(s.step["status"], "pass", s.step["checks"])
        self.assertTrue(all(v == "false" for v in fields.values()), fields)
        self.assertEqual(calls[-2:], [("/scale", {"n": 3}), ("/close", {})])
        self.assertIn(("/size", {"w": 1280, "h": 720}), calls)

    def test_an_older_bridge_without_the_probe_is_not_a_failure(self):
        s, calls, fields = self.run_step(probe_error="unknown probe hud-boxes (fov, hurt)")
        self.assertEqual(s.step["status"], "not available yet")
        self.assertTrue(all(v == "false" for v in fields.values()), fields)
        self.assertEqual(calls[-2:], [("/scale", {"n": 3}), ("/close", {})])

    def test_a_missing_module_fails_e0(self):
        s, _, _ = self.run_step(boxes=[{"id": "potion", "shown": True}])
        self.assertEqual(s.step["status"], "fail")


class FakeEditor(cobdev.Smoke):
    """A tiny model of Edit HUD for the A4-edithud checks: boxes in GUI px, arrows move one screen pixel (Shift
    ten), a device-pixel drag moves start + travel / scale, and the left screen edge snaps within 4 screen px."""

    def __init__(self, boxes, scale=2, display=(1280, 720)):
        cobdev.Smoke.__init__(self, "dev", "test")
        self.begin("A4-edithud", "test")
        self.boxes = {b["id"]: dict(b) for b in boxes}
        self.display = display
        self.sc, self.selected, self.press = cobdev.gui_scale(display[0], display[1], scale), None, None

    @property
    def sw(self):
        return -(-self.display[0] // self.sc)  # ScaledResolution rounds up

    @property
    def sh(self):
        return -(-self.display[1] // self.sc)

    def status(self):
        return {"display": "%dx%d" % self.display, "scaleFactor": self.sc}

    def call(self, path, params=None, post=None, timeout=60):
        q = params or post or {}
        if path == "/scale":
            self.sc = cobdev.gui_scale(self.display[0], self.display[1], int(q["n"]))
        elif path == "/probe":
            return {"scale": self.sc, "sw": self.sw, "sh": self.sh, "boxes": [dict(b) for b in self.boxes.values()]}
        elif path == "/click":
            self.selected = next((i for i, b in self.boxes.items()  # empty space clears it, as in Edit HUD
                                  if b["x"] <= q["x"] <= b["x"] + b["w"] and b["y"] <= q["y"] <= b["y"] + b["h"]), None)
        elif path == "/key" and self.selected:
            step = 10 if q.get("mods") == "shift" else 1
            self.boxes[self.selected]["x"] += step * (1 if q["code"] == cobdev.KEYS["right"] else -1) / float(self.sc)
        elif path == "/down":
            b = next(b for b in self.boxes.values() if b["shown"])
            self.selected, self.press = b["id"], (q["x"], b["x"], q["y"], b["y"])
        elif path == "/drag":
            b = self.boxes[self.selected]
            x = self.press[1] + (q["x"] - self.press[0]) / float(self.sc)
            y = self.press[3] + (q["y"] - self.press[2]) / float(self.sc)
            if q.get("mods") != "alt":
                reach = 4.0 / self.sc
                if 0 < x - b["pad"] <= reach:
                    x = b["pad"]
                right = self.sw - (x + b["w"] + b["pad"])
                if 0 < right <= reach:
                    x = self.sw - b["w"] - b["pad"]
            b["x"], b["y"] = x, y
        return {"ok": True, "value": False}

    def shot(self, tag, wait=300):
        return "/dev/null", (1, 1, [b"\0\0\0"])


class EditHudChecks(unittest.TestCase):
    def run_check(self, fn, boxes, display=(1280, 720)):
        s = FakeEditor(boxes, display=display)
        real = cobdev.time.sleep
        cobdev.time.sleep = lambda t: None
        try:
            fn(cobdev.EditHudRun(s, "cfg"))
        finally:
            cobdev.time.sleep = real
        return s

    def test_e8_passes_on_an_editor_that_moves_one_screen_pixel(self):
        s = self.run_check(cobdev.edithud_e8, [{"id": "inventory", "x": 110.5, "y": 3, "w": 206, "h": 74, "pad": 1,
                                                "shown": True}])
        self.assertEqual(s.step["status"], "pass", s.step["checks"])
        self.assertEqual(len([c for c in s.step["checks"] if c["check"].startswith("E8")]), 8)

    def test_e8_and_e9_on_a_738x894_window_test_gui_2_only(self):
        # the prod run: 738x894 is GUI 369x447, and GUI 3 is not reachable (738 / 3 < 320): noted, not failed
        for fn, item in ((cobdev.edithud_e8, "inventory"), (cobdev.edithud_e9, "heightlimit")):
            box = {"id": item, "x": 120.5, "y": 200, "w": 120, "h": 38, "pad": 1, "shown": True}
            s = self.run_check(fn, [box], display=(738, 894))
            self.assertEqual(s.step["status"], "pass", s.step["checks"])
            self.assertTrue(all("GUI 2" in c["check"] for c in s.step["checks"]), s.step["checks"])
            self.assertTrue(any("not reachable" in n for n in s.step.get("notes", [])))

    def test_e9_passes_when_only_the_edge_snaps(self):
        s = self.run_check(cobdev.edithud_e9, [{"id": "heightlimit", "x": 3, "y": 100, "w": 120, "h": 38, "pad": 1,
                                                "shown": True}])
        self.assertEqual(s.step["status"], "pass", s.step["checks"])

    def test_e8_fails_on_an_editor_that_moves_whole_gui_pixels(self):
        class Coarse(FakeEditor):
            def call(self, path, params=None, post=None, timeout=60):
                r = FakeEditor.call(self, path, params, post, timeout)
                if path == "/key" and self.selected:
                    b = self.boxes[self.selected]
                    b["x"] = float(round(b["x"]))
                return r

        s = Coarse([{"id": "inventory", "x": 110.5, "y": 3, "w": 206, "h": 74, "pad": 1, "shown": True}])
        real = cobdev.time.sleep
        cobdev.time.sleep = lambda t: None
        try:
            cobdev.edithud_e8(cobdev.EditHudRun(s, "cfg"))
        finally:
            cobdev.time.sleep = real
        self.assertEqual(s.step["status"], "fail")


class EditHudHelpers(unittest.TestCase):
    @staticmethod
    def hb(boxes, states):
        return {"boxes": boxes, "states": states}

    def test_pinning_is_not_a_change_but_a_move_is(self):
        box = {"id": "potion", "x": 3, "y": 30, "w": 52, "h": 44, "pad": 1, "shown": True}
        auto = {"id": "potion", "anchor": -1, "scale": 1.0, "enabled": True, "background": True}
        pinned = dict(auto, anchor=0, x=2, y=29)
        before = self.hb([box], [auto])
        self.assertEqual(cobdev.others_unchanged(before, self.hb([box], [pinned]), set()), [])
        moved = dict(box, x=3.01)
        self.assertTrue(cobdev.others_unchanged(before, self.hb([moved], [pinned]), set()))
        self.assertTrue(cobdev.others_unchanged(before, self.hb([box], [dict(auto, scale=1.5)]), set()))
        self.assertTrue(cobdev.others_unchanged(self.hb([box], [pinned]), self.hb([box], [dict(pinned, anchor=3)]), set()))
        self.assertEqual(cobdev.others_unchanged(before, self.hb([moved], [pinned]), {"potion"}), [])
        hidden = dict(box, shown=False)
        self.assertEqual(cobdev.others_unchanged(self.hb([hidden], [auto]), self.hb([dict(hidden, y=90)], [auto]), set()),
                         [], "a hidden module's ghost may re-flow")

    def test_left_column_and_overlap(self):
        inv = {"id": "inventory", "x": 218, "y": 3, "w": 204, "h": 72, "pad": 1, "shown": True}
        armor = {"id": "armor", "x": 3, "y": 3, "w": 20, "h": 20, "pad": 1, "shown": True}
        low = {"id": "heightlimit", "x": 3, "y": 160, "w": 118, "h": 36, "pad": 1, "shown": True}
        ghost = dict(armor, id="potion", shown=False)
        self.assertEqual([b["id"] for b in cobdev.left_column(self.hb([inv, armor, low, ghost], []), "inventory")],
                         ["armor"])
        self.assertEqual(cobdev.overlap_area(inv, armor), 0)
        self.assertEqual(cobdev.overlap_area(armor, dict(armor, x=13)), 12 * 22)
        self.assertTrue(cobdev.pinned_anchor({"anchor": 0}))
        self.assertFalse(cobdev.pinned_anchor({"anchor": -1}))
        self.assertFalse(cobdev.pinned_anchor({}))


class EditHudPixels(unittest.TestCase):
    def test_cap_and_box_rectangles(self):
        ks = {"x": 100.0, "y": 50.0, "w": 56.0}
        # W cap at local (19, 0, 18, 18), box scale 1, GUI 2: middle 60% = (22.6..33.4, 3.6..14.4) local
        self.assertEqual(cobdev.cap_px(ks, "W", 2), (245, 107, 267, 129))
        # a box drawn at 1.5: the caps scale with it
        self.assertEqual(cobdev.cap_px(dict(ks, w=84.0), "W", 2), (268, 111, 300, 143))
        self.assertEqual(cobdev.px_box(10, 20, 30, 40, 3), (30, 60, 120, 180))
        self.assertEqual(cobdev.px_box(10, 20, 30, 40, 2, inset=1.0), (22, 42, 78, 118))


class DefaultLayoutCheck(unittest.TestCase):
    """g30_problems over the DefaultHudLayoutTest fixture at 960x540 (visual boxes; content = visual - 2)."""
    SPOTS = {"fps": (2, 2, 46, 18), "cps": (2, 22, 52, 18), "ping": (2, 42, 40, 18), "armor": (2, 62, 22, 22),
             "potion": (2, 86, 52, 44), "heightlimit": (2, 251, 120, 38), "diamondtimer": (916, 2, 42, 22),
             "emeraldtimer": (872, 2, 42, 22), "sessionstats": (820, 26, 138, 148), "inventory": (377, 2, 206, 74),
             "keystrokes": (894, 458, 64, 80)}

    def hb(self, spots):
        return {"sw": 960, "sh": 540, "boxes": [{"id": i, "x": x + 1, "y": y + 1, "w": w - 2, "h": h - 2, "pad": 1,
                                                 "shown": True} for i, (x, y, w, h) in spots.items()]}

    def test_the_default_layout_passes(self):
        self.assertEqual(cobdev.g30_problems(self.hb(self.SPOTS)), [])

    def test_a_moved_module_is_reported(self):
        moved = dict(self.SPOTS, keystrokes=(800, 400, 64, 80))
        self.assertEqual(len(cobdev.g30_problems(self.hb(moved))), 2)
        gap = dict(self.SPOTS, cps=(2, 23, 52, 18))
        self.assertTrue(cobdev.g30_problems(self.hb(gap)))

    def test_hidden_and_missing_modules_are_skipped(self):
        spots = dict(self.SPOTS)
        del spots["fps"], spots["cps"], spots["ping"]
        spots["armor"], spots["potion"] = (2, 2, 22, 22), (2, 26, 52, 44)
        self.assertEqual(cobdev.g30_problems(self.hb(spots)), [])


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
        self.assertEqual(cobdev.hl_expected_panel("armor", 2), (48, 36))  # the 24 x 18 FPS-style box
        self.assertIsNone(cobdev.hl_expected_panel("sessionstats", 2))


class HudLookNoise(unittest.TestCase):
    """The first live run (2026-11-14): world specks, the horizon redrawing far chunks, a camera that turned and a
    ticking potion timer made frame diffs measure the world instead of the module. The HL boxes must ignore them."""

    W, H = 300, 200
    HORIZON = 100

    def world(self, horizon_shift=0, specks=()):
        img = flat(self.W, self.H, SKY)
        img = paint(img, (0, self.HORIZON + horizon_shift, self.W - 1, self.H - 1), GRASS)
        for x, y in specks:
            img = paint(img, (x, y, x, y), (200, 40, 40))
        return img

    def fps_on(self, base):
        """An FPS box at (4, 4), 94 x 36 px (47 x 18 GUI px at scale factor 2), white text with a 63 shadow."""
        img = paint(base, (4, 4, 97, 39), see_through(SKY))
        img = paint(img, (14, 16, 30, 27), (63, 63, 63))
        return paint(img, (12, 14, 28, 25), (255, 255, 255))

    def fps_off(self, base):
        img = paint(base, (12, 12, 30, 23), (63, 63, 63))
        return paint(img, (10, 10, 28, 21), (255, 255, 255))

    def test_changed_points_skips_identical_rows_and_matches_bbox_diff(self):
        a = self.world()
        b = paint(a, (5, 7, 9, 8), GRASS)
        pts = cobdev.changed_points(a, b)
        self.assertEqual(len(pts), 10)
        self.assertEqual(cobdev.bbox_of(pts), (5, 7, 9, 8))
        self.assertEqual(cobdev.bbox_diff(a, b), (5, 7, 9, 8))
        self.assertEqual(cobdev.changed_points(a, a), [])
        self.assertIsNone(cobdev.changed_points(a, flat(10, 10, SKY)))

    def test_specks_and_a_horizon_band_do_not_count(self):
        before = self.world()
        frame = self.fps_on(self.world(horizon_shift=1, specks=[(150, 160), (220, 180)]))
        self.assertEqual(cobdev.bbox_diff(before, frame)[2:], (self.W - 1, 180))  # the old, noisy box
        self.assertEqual(cobdev.hud_bbox(before, frame), (4, 4, 97, 39))

    def test_a_second_backdrop_removes_a_change_seen_only_in_the_first(self):
        before = self.world(horizon_shift=-2)          # a transient horizon in the first backdrop only
        after = self.world()
        frame = self.fps_on(self.world())
        self.assertEqual(cobdev.hud_boxes(before, frame, also=after), [(4, 4, 97, 39)])

    def test_two_panels_far_apart_are_two_boxes_biggest_first(self):
        before = self.world()
        frame = paint(paint(before, (200, 4, 273, 47), see_through(SKY)), (120, 4, 163, 47), see_through(SKY))
        self.assertEqual(cobdev.hud_boxes(before, frame), [(200, 4, 273, 47), (120, 4, 163, 47)])
        self.assertEqual(cobdev.union_box(cobdev.hud_boxes(before, frame)), (120, 4, 273, 47))

    def test_bg_checks_pass_through_noise_and_detect_a_moved_scene(self):
        before, after = self.world(), self.world()
        on = self.fps_on(self.world(specks=[(150, 160)]))
        off = self.fps_off(self.world(horizon_shift=1))
        checks, box, moved = cobdev.hl_bg_checks("fps", "textbox", (before, after), on, off, 2)
        self.assertEqual(box, (4, 4, 97, 39))
        self.assertFalse(moved)
        self.assertEqual([c for c in checks if not c[1]], [])
        turned = self.fps_off(self.world(horizon_shift=40))  # the camera looked down between the shots
        self.assertTrue(cobdev.hl_bg_checks("fps", "textbox", (before, after), on, turned, 2)[2])

    def test_icon_swap_ignores_the_ticking_time_line(self):
        panel, sf = (4, 4, 139, 51), 2
        old = paint(self.world(), panel, see_through(SKY))
        modern = paint(old, (12, 12, 40, 40), (180, 40, 40))           # the icon slot
        ticked = paint(modern, (60, 34, 80, 42), (170, 170, 170))       # "0:43" became "0:42"
        self.assertTrue(cobdev.hl_icon_swap(old, ticked, panel, sf)[0])
        renamed = paint(modern, (60, 14, 80, 20), (255, 255, 255))      # the name line changed: not only the icon
        self.assertFalse(cobdev.hl_icon_swap(old, renamed, panel, sf)[0])
        self.assertFalse(cobdev.hl_icon_swap(old, old, panel, sf)[0])
        self.assertFalse(cobdev.hl_icon_swap(old, modern, None, sf)[0])


class HudLookProdRun(unittest.TestCase):
    """The first prod run (2026-11-14): the window changed size inside a module's shots and after a restart, and the
    potion frames were taken one blink period apart."""

    def test_a_backdrop_of_another_size_is_ignored_not_fatal(self):
        before = flat(300, 200, SKY)
        frame = paint(before, (200, 4, 273, 47), see_through(SKY))
        resized = flat(150, 200, SKY)
        self.assertEqual(cobdev.hud_boxes(before, frame, also=resized), [(200, 4, 273, 47)])

    def test_frames_of_different_sizes_mean_a_retake(self):
        before = flat(300, 200, SKY)
        on = paint(before, (4, 4, 97, 39), see_through(SKY))
        small = flat(150, 200, SKY)
        checks, box, moved = cobdev.hl_bg_checks("fps", "textbox", (before, small), on, on, 2)
        self.assertTrue(moved)
        self.assertIsNone(box)

    def test_counting_past_the_frame_is_clipped(self):
        img = paint(flat(20, 10, SKY), (15, 5, 19, 9), (63, 63, 63))
        self.assertEqual(cobdev.count_exact(img, (10, 0, 112, 134), (63, 63, 63)), 25)
        self.assertEqual(cobdev.count_near(img, (-5, -5, 40, 40), (63, 63, 63), 0), 25)
        self.assertEqual(cobdev.xs_of(img, (0, 0, 99, 99), (63, 63, 63)), (15, 19))

    def test_potion_frames_see_both_halves_of_the_blink_from_any_start(self):
        offsets = cobdev.hl_blink_offsets()
        for start in [i / 20.0 for i in range(20)]:
            phases = [((start + t) % 1.0) for t in offsets]  # PotionRows: 0.5 s shown, 0.5 s hidden
            self.assertTrue(any(p < 0.5 for p in phases) and any(p >= 0.5 for p in phases), (start, phases))
        self.assertLess(offsets[-1], 4.0)  # all inside a 9 s effect, after the setup
        # the old spacing (one frame every ~1 s) can stay in one half
        self.assertFalse(any(((0.1 + t) % 1.0) >= 0.5 for t in (0, 0.99, 1.98, 2.97)))


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


class EditHudE12Cleanup(unittest.TestCase):
    """E12's scoreboard fixture is removed and the release is checked even when the overlay boxes are missing."""

    def test_missing_overlay_boxes_still_clean_up(self):
        sent = []

        class Fake(cobdev.Smoke):
            def call(self, path, params=None, post=None, timeout=60):
                q = params or post or {}
                sent.append((path, q.get("command") or q.get("name") or q.get("state")))
                if path == "/probe" and q.get("name") == "overlay-boxes":
                    return {"items": {}}
                if path == "/probe":
                    return {"scale": 2, "boxes": [{"id": "inventory", "x": 200, "y": 3, "w": 200, "h": 70, "pad": 1,
                                                   "shown": True}], "states": [],
                            "editor": {"tabHeld": False, "playerListPressed": False, "visibleIds": ["inventory"]}}
                return {"ok": True, "value": False}

            def shot(self, tag, wait=300):
                return "/dev/null", (1, 1, [b"\0\0\0"])

        s = Fake("dev", "test")
        s.begin("A4-edithud", "test")
        real = cobdev.time.sleep
        cobdev.time.sleep = lambda t: None
        try:
            cobdev.edithud_e12(cobdev.EditHudRun(s, "cfg"))
        finally:
            cobdev.time.sleep = real
        commands = [c for p, c in sent if p == "/command"]
        self.assertIn("/scoreboard objectives remove cobtab", commands)
        self.assertEqual(commands[-1], "/scoreboard objectives remove cobtab")
        self.assertIn(("/keyhold", "key.playerlist"), sent)
        names = [c["check"] for c in s.step["checks"]]
        self.assertTrue(any("released" in n for n in names), names)


class EditHudSmokeHelpers(unittest.TestCase):
    """The robustness helpers behind E7/E7b (size steps), E11 (the editor's dim) and the pointer."""

    def test_on_step_means_whole_device_pixels_per_font_pixel(self):
        self.assertTrue(cobdev.on_step(1.5, 2))
        self.assertTrue(cobdev.on_step(4 / 3.0, 3))
        self.assertFalse(cobdev.on_step(1.25, 2))

    def test_a_resize_stops_flush_or_rests_on_a_step_just_short_in_minecrafts_font(self):
        # a 90 GUI px box at 1.5: resting on the step may leave up to 90 * 0.05 / 1.5 = 3 px
        self.assertTrue(cobdev.grow_stopped_ok(0.02, 90.0, 1.37, 2, True))     # flush, between steps
        self.assertTrue(cobdev.grow_stopped_ok(2.5, 90.0, 1.5, 2, True))       # on the step, just short
        self.assertFalse(cobdev.grow_stopped_ok(2.5, 90.0, 1.37, 2, True))     # short, but not on a step
        self.assertFalse(cobdev.grow_stopped_ok(8.0, 90.0, 1.5, 2, True))      # a step, but too far short
        self.assertFalse(cobdev.grow_stopped_ok(-0.5, 90.0, 1.5, 2, True))     # overlapping
        self.assertTrue(cobdev.grow_stopped_ok(0.02, 82.0, 1.2, 2, False))     # modern font: flush
        self.assertFalse(cobdev.grow_stopped_ok(2.5, 82.0, 1.5, 2, False))

    def test_free_point_avoids_every_box(self):
        hb = {"sw": 60, "sh": 30, "boxes": [{"x": 1, "y": 1, "w": 30, "h": 28, "pad": 1}]}
        x, y = cobdev.free_point(hb, margin=2)
        self.assertGreater(x, 34)
        self.assertIsNone(cobdev.free_point({"sw": 40, "sh": 30, "boxes": [{"x": 0, "y": 0, "w": 40, "h": 30, "pad": 0}]}))

    def test_changed_frac_allows_the_editors_dim_only(self):
        play = paint(flat(10, 10, SKY), (0, 0, 9, 4), (40, 40, 40))
        dimmed = tuple(int(c * (1 - cobdev.DIM_ALPHA)) for c in SKY)
        editor = paint(paint(flat(10, 10, dimmed), (0, 0, 9, 4), (40, 40, 40)), (9, 9, 9, 9), (255, 255, 255))
        self.assertEqual(cobdev.changed_frac(play, editor, (0, 0, 9, 9)), 0.0)        # dimmed sky, same HUD
        self.assertAlmostEqual(cobdev.changed_frac(play, editor, (0, 0, 10, 10)), 0.01)  # one changed pixel
        self.assertEqual(cobdev.changed_frac(play, editor, (0, 0, 10, 10), [(9, 9, 10, 10)]), 0.0)  # excluded chrome
        brighter = paint(flat(10, 10, SKY), (0, 0, 9, 4), (200, 200, 200))
        self.assertGreater(cobdev.changed_frac(play, brighter, (0, 0, 10, 10)), 0.4)  # content changed


class EditHudClearOf(unittest.TestCase):
    """'No overlap' as the editor's walls mean it: flush within HudWalls.EPS (1e-3 GUI px) is not overlap."""

    def test_the_rerun_tab_list_is_flush_with_the_scoreboard(self):
        # smoke-20261114-115606-dev: right edge 444.99997 + 132.001 = 577.00097 against the scoreboard at 577.0
        tab = {"x": 444.99997, "y": 168.5, "w": 132.001, "h": 14.347936, "pad": 0}
        sb = {"x": 577.0, "y": 164.0, "w": 62.0, "h": 19.0, "pad": 0}
        self.assertTrue(cobdev.clear_of(tab, sb))
        self.assertTrue(cobdev.clear_of(sb, tab))
        self.assertFalse(cobdev.clear_of(dict(tab, w=132.01), sb))   # 0.01 px in: overlap
        self.assertTrue(cobdev.clear_of(dict(tab, y=200), sb))       # beside, not level with it
        self.assertFalse(cobdev.clear_of(dict(tab, x=500), sb))


class EditHudWindowSizes(unittest.TestCase):
    """Every coordinate comes from the live window: the prod window (738x894) and the dev one (1280x720)."""

    def test_gui_scale_follows_vanilla(self):
        self.assertEqual(cobdev.gui_scale(738, 894, 2), 2)
        self.assertEqual(cobdev.gui_scale(738, 894, 3), 2)
        self.assertEqual(cobdev.gui_scale(738, 894, 0), 2)
        self.assertEqual(cobdev.gui_scale(1280, 720, 3), 3)
        self.assertEqual(cobdev.gui_scale(1280, 720, 0), 3)
        self.assertEqual(cobdev.gui_scale(1280, 720, 2), 2)

    def test_clamp_point(self):
        self.assertEqual(cobdev.clamp_point(976, 147, 738, 894), (737, 147))
        self.assertEqual(cobdev.clamp_point(-16, 42, 369, 447), (0, 42))

    def test_free_spot_at_both_window_sizes(self):
        # the prod layout at 369x447: Inventory sits in the middle; the dev layout at 640x360
        prod = {"sw": 369, "sh": 447, "boxes": [
            {"id": "inventory", "x": 83, "y": 175.5, "w": 204, "h": 72, "pad": 1, "shown": True},
            {"id": "chat", "x": 2, "y": 329, "w": 324, "h": 90, "pad": 0, "shown": True},
            {"id": "sessionstats", "x": 260, "y": 30, "w": 103, "h": 138, "pad": 4, "shown": True},
            {"id": "ghost", "x": 150, "y": 100, "w": 50, "h": 50, "pad": 0, "shown": False}]}
        dev = {"sw": 640, "sh": 360, "boxes": [
            {"id": "inventory", "x": 218, "y": 3, "w": 204, "h": 72, "pad": 1, "shown": True},
            {"id": "chat", "x": 2, "y": 242, "w": 324, "h": 90, "pad": 0, "shown": True}]}
        for hb in (prod, dev):
            x, y = cobdev.free_spot(hb, 62, 73)
            box = {"x": x, "y": y, "w": 62, "h": 73, "pad": 0}
            self.assertTrue(0 <= x and x + 62 <= hb["sw"] and 0 <= y and y + 73 <= hb["sh"])
            for b in hb["boxes"]:
                if b["shown"]:
                    self.assertTrue(cobdev.clear_of(box, b), (hb["sw"], b["id"], x, y))
        self.assertIsNone(cobdev.free_spot({"sw": 100, "sh": 100, "boxes": [
            {"id": "all", "x": 0, "y": 0, "w": 100, "h": 100, "pad": 0, "shown": True}]}, 10, 10))


WOOL_LIGHT, WOOL_DARK = (177, 177, 177), (20, 18, 18)  # white and black wool on a north face (shade 0.8)


def checker(w, h, cell=48, light=WOOL_LIGHT, dark=WOOL_DARK, grain=6, x_shift=0):
    """A wool checkerboard with a little deterministic texture grain, cells of `cell` px (the real board's cells are
    46-69 px on an 854-1280 px window, against a 6-9 px smear)."""
    rows = []
    for y in range(h):
        row = bytearray()
        for x in range(w):
            base = light if ((x + x_shift) // cell + y // cell) % 2 == 0 else dark
            g = (((x + x_shift) // 4) * 5 + (y // 4) * 3) % (2 * grain + 1) - grain  # 4 px texels
            row += bytes(max(0, min(255, c + g)) for c in base)
        rows.append(bytes(row))
    return w, h, rows


def box_blur(img, n, vertical=False):
    """An n-tap box blur along one axis (edge pixels clamp), like a smear along a horizontal or vertical turn."""
    w, h, rows = img
    out = []
    for y in range(h):
        row = bytearray()
        for x in range(w):
            acc = [0, 0, 0]
            for k in range(n):
                d = k - n // 2
                xx, yy = (x, min(h - 1, max(0, y + d))) if vertical else (min(w - 1, max(0, x + d)), y)
                p = cobdev.px(img, xx, yy)
                for i in range(3):
                    acc[i] += p[i]
            row += bytes(int(round(a / n)) for a in acc)
        out.append(bytes(row))
    return w, h, out


def scaled(img, f):
    w, h, rows = img
    return w, h, [bytes(min(255, int(round(v * f))) for v in r) for r in rows]


class MotionBlurMeasures(unittest.TestCase):
    """Step MB's image measures (PLAN 2026-11-17-motion-blur Phase 3, step 4) on synthetic frames."""

    REST = checker(320, 180)

    def test_region_is_the_middle_half_and_15_to_55_percent(self):
        self.assertEqual(cobdev.mb_region(1280, 720), (320, 108, 960, 396))
        self.assertEqual(cobdev.mb_region(854, 480), (214, 72, 640, 264))

    def test_detail_is_zero_on_a_flat_frame_and_positive_on_the_board(self):
        rect = cobdev.mb_region(320, 180)
        self.assertEqual(cobdev.mb_detail(flat(320, 180, (90, 90, 90)), rect), (0.0, 0.0))
        dh, dv = cobdev.mb_detail(self.REST, rect)
        self.assertGreater(dh, 100)
        self.assertGreater(dv, 100)

    def test_detail_is_the_mean_squared_neighbour_difference(self):
        # one vertical edge of 100 luma steps in a 4 x 2 region: 2 of the 6 horizontal pairs differ
        img = (4, 2, [bytes([0] * 6 + [100] * 6)] * 2)
        dh, dv = cobdev.mb_detail(img, (0, 0, 4, 2))
        self.assertAlmostEqual(dh, 100.0 ** 2 * 2 / 6, places=6)
        self.assertEqual(dv, 0.0)

    def test_squared_not_l1_a_blurred_isolated_edge_scores_lower(self):
        # one hard edge versus the same edge spread over 8 px: L1 is the same, the mean square is 8x lower
        sharp = (16, 1, [bytes(sum(([0, 0, 0] if x < 8 else [160, 160, 160] for x in range(16)), []))])
        ramp = (16, 1, [bytes(sum(([min(160, max(0, (x - 4) * 20))] * 3 for x in range(16)), []))])
        self.assertLess(cobdev.mb_detail(ramp, (0, 0, 16, 1))[0], cobdev.mb_detail(sharp, (0, 0, 16, 1))[0] / 4)

    def test_luma_mean_and_variance(self):
        mean, var = cobdev.mb_luma(flat(10, 10, (100, 100, 100)), (0, 0, 10, 10))
        self.assertAlmostEqual(mean, 100.0, places=6)
        self.assertEqual(var, 0.0)
        self.assertGreater(cobdev.mb_luma(self.REST, cobdev.mb_region(320, 180))[1], 1000)

    def test_a_horizontal_smear_passes_every_turn_check(self):
        checks = cobdev.mb_turn_checks(box_blur(self.REST, 9), self.REST)
        self.assertEqual([c[0] for c in checks if not c[1]], [], checks)
        self.assertEqual(len(checks), 4)

    def test_an_unblurred_turn_fails_the_horizontal_check_only(self):
        failed = [c[0] for c in cobdev.mb_turn_checks(self.REST, self.REST) if not c[1]]
        self.assertEqual(len(failed), 1)
        self.assertIn("horizontal", failed[0])

    def test_a_vertical_smear_fails_the_vertical_control(self):
        failed = [c[0] for c in cobdev.mb_turn_checks(box_blur(self.REST, 9, vertical=True), self.REST) if not c[1]]
        self.assertTrue(any("vertical" in f for f in failed), failed)

    def test_a_blank_frame_fails(self):
        failed = [c[0] for c in cobdev.mb_turn_checks(flat(320, 180, (99, 98, 98)), self.REST) if not c[1]]
        self.assertTrue(any("blank" in f for f in failed), failed)
        self.assertTrue(any("vertical" in f for f in failed), failed)

    def test_a_brighter_frame_fails_the_luminance_check(self):
        failed = [c[0] for c in cobdev.mb_turn_checks(scaled(box_blur(self.REST, 9), 1.25), self.REST) if not c[1]]
        self.assertTrue(any("luminance" in f for f in failed), failed)

    def test_fixture_guard_passes_on_the_board_only(self):
        rect = cobdev.mb_region(320, 180)
        self.assertTrue(cobdev.mb_fixture(self.REST, rect)[0], cobdev.mb_fixture(self.REST, rect)[1])
        for img in (scene(320, 180, lambda x: 90), flat(320, 180, WOOL_LIGHT), flat(320, 180, WOOL_DARK),
                    checker(320, 180, light=SKY, dark=GRASS)):  # sky and grass edges are not the board
            ok, detail = cobdev.mb_fixture(img, rect)
            self.assertFalse(ok, detail)


class RewindExtCounts(unittest.TestCase):
    LINE = ("blocks=8 tornTail=false complete=true segments=1 segmentsStartingAtBoundary=1 netRecords=43 "
            "trackTicks=10 markerKey=1 formatVersion=2 extTickHead=31 extOut=0 extDig=0 extBlock=0 extSound=4 "
            "extView=1 extMouse=0 extChatInput=0 extFov=1 extUse=0 extInv=41 extSettings=1 extIdentity=1 extInput=2 "
            "extHud=3 extCont=0 extTap=0 extUnknown=0 unsupportedVersion=false pass=true")

    def test_counts_are_read_from_the_summary_line(self):
        fmt, ext = cobdev.rewind_ext_counts(self.LINE)
        self.assertEqual(fmt, 2)
        self.assertEqual(ext["TickHead"], 31)
        self.assertEqual(ext["Inv"], 41)
        self.assertEqual(ext["Unknown"], 0)
        self.assertEqual(len(ext), 18)  # 17 tags and Unknown
        self.assertEqual(cobdev.rewind_ext_counts(None), (None, {}))

    def test_a_complete_v2_session_has_no_problem(self):
        self.assertEqual(cobdev.rewind_ext_problems(self.LINE, moved=True), [])

    def test_each_missing_record_is_named(self):
        v1 = "blocks=8 complete=true formatVersion=1 extUnknown=0 pass=true"
        p = cobdev.rewind_ext_problems(v1, moved=True)
        self.assertIn("formatVersion=1", p)
        for name in ("TickHead", "Settings", "Identity", "View", "Fov"):
            self.assertIn("no %s record" % name, p)
        self.assertIn("no Input record though the jump binding was held", p)
        bad = self.LINE.replace("extInv=41", "extInv=7").replace("extIdentity=1", "extIdentity=2") \
            .replace("extUnknown=0", "extUnknown=3").replace("extInput=2", "extInput=0")
        p = cobdev.rewind_ext_problems(bad, moved=False)
        self.assertEqual(p, ["Identity=2 (one per segment)", "Inv=7 (all 41 slots at the segment start)",
                             "Unknown=3"])


class RewindActionCounts(unittest.TestCase):
    """RW's scripted actions (Rewind parity Phase 16): what their recording must hold, counts only."""

    LINE = RewindExtCounts.LINE.replace("extOut=0", "extOut=9").replace("extDig=0", "extDig=6") \
        .replace("extBlock=0", "extBlock=2").replace("extView=1", "extView=7").replace("extMouse=0", "extMouse=12") \
        .replace("extCont=0", "extCont=54")

    def test_a_recording_of_every_action_has_no_problem(self):
        self.assertEqual(cobdev.rewind_action_problems(self.LINE), [])
        self.assertEqual(cobdev.rewind_ext_problems(self.LINE, moved=True), [])

    def test_each_short_count_is_named_with_its_minimum(self):
        p = cobdev.rewind_action_problems(RewindExtCounts.LINE)
        self.assertEqual(p, ["Dig=0 (at least 1)", "Block=0 (at least 2)", "Out=0 (at least 1)",
                             "View=1 (at least 3)", "Mouse=0 (at least 1)", "Cont=0 (at least 1)"])
        self.assertEqual(len(cobdev.rewind_action_problems(None)), len(cobdev.RW_ACTION_EXT_MIN))


class MotionBlurHelpers(unittest.TestCase):
    def test_board_is_21_by_9_white_fill_then_black_cells(self):
        cmds = cobdev.mb_board_commands()
        self.assertEqual(cmds[0], "/fill -10 4 8 10 12 8 minecraft:wool 0")
        black = [c.split()[1:4] for c in cmds[1:]]
        self.assertTrue(all(c.startswith("/setblock ") and c.endswith(" minecraft:wool 15") for c in cmds[1:]))
        self.assertEqual(len(black), 94)  # 189 cells, (x + y) odd
        cells = {tuple(int(v) for v in b) for b in black}
        self.assertIn((-9, 4, 8), cells)
        self.assertNotIn((-10, 4, 8), cells)
        self.assertTrue(all(len(c) <= 100 for c in cmds))  # Policy.command: vanilla's chat limit
        self.assertEqual(cobdev.mb_board_clear(), "/fill -10 4 8 10 12 8 minecraft:air")

    def test_hud_rects_are_the_hotbar_crosshair_and_shown_boxes(self):
        hb = {"scale": 2, "sw": 200, "sh": 120,
              "boxes": [{"id": "potion", "x": 4, "y": 6, "w": 30, "h": 10, "pad": 1, "shown": True},
                        {"id": "armor", "x": 50, "y": 6, "w": 30, "h": 10, "pad": 1, "shown": False}]}
        rects = dict(cobdev.mb_hud_rects(hb))
        self.assertEqual(rects["hotbar"], (16, 194, 384, 240))
        self.assertEqual(rects["crosshair"], (184, 104, 220, 140))
        self.assertEqual(rects["potion"], (6, 10, 70, 34))
        self.assertNotIn("armor", rects)

    def test_identity_counts_every_changed_pixel(self):
        a = checker(80, 40)
        rows = [bytearray(r) for r in a[2]]
        rows[5][3 * 7] ^= 1  # one channel of one pixel, by one step
        b = (80, 40, [bytes(r) for r in rows])
        self.assertEqual(cobdev.mb_identical(a, a, [("x", (0, 0, 80, 40))]), [("x", 0)])
        self.assertEqual(cobdev.mb_identical(a, b, [("in", (0, 0, 10, 10)), ("out", (20, 0, 80, 40)),
                                                    ("off", (-5, -5, 2, 2))]), [("in", 1), ("out", 0), ("off", 0)])

    def test_cut_frame_is_the_first_frame_with_the_new_serial(self):
        frames = [{"frameId": 9, "cutSerial": 3}, {"frameId": 10, "cutSerial": 3},
                  {"frameId": 11, "cutSerial": 4, "resetReason": "position-correction"},
                  {"frameId": 12, "cutSerial": 4, "resetReason": None}]
        frame, why = cobdev.mb_cut_frame(frames, 3)
        self.assertEqual(frame["frameId"], 11, why)
        frame, why = cobdev.mb_cut_frame(frames[:2], 3)
        self.assertIsNone(frame)
        self.assertIn("no frame", why)
        frame, why = cobdev.mb_cut_frame([frames[0], frames[3]], 3)  # frames 10 and 11 were never sampled
        self.assertIsNone(frame)
        self.assertIn("skipped", why)


class FakeMotionBlurGame(cobdev.Smoke):
    """A model of the dev client for step MB: settings, cuts, the turn and the frames mb-shot returns."""

    W, H = 400, 240

    def __init__(self, blurs=True):
        cobdev.Smoke.__init__(self, "dev", "test")
        self.blurs = blurs
        self.fields = {"motionBlur": False, "mbStrength": 3, "fpsEnabled": True, "cpsEnabled": True,
                       "pingEnabled": False}
        self.sent, self.cut, self.frame, self.gl, self.yaw, self.scales = [], 0, 100, 7, 0.0, []
        self.rest = checker(self.W, self.H)
        self.turned = box_blur(self.rest, 9)

    def record(self, **over):
        self.frame += 1
        r = {"frameId": self.frame, "yaw": self.yaw, "pitch": 0.0, "smearPx": 0.0, "taps": 0, "active": False,
             "worldPassRan": False, "resetReason": None, "cutSerial": self.cut, "cutSerialNow": self.cut,
             "glEntries": self.gl, "failed": None, "suppressed": False, "fps": 120}
        r.update(over)
        return r

    def shot(self, img, rec):
        out = {"ok": True, "record": rec, "width": self.W, "height": self.H}
        out["png"] = cobdev.base64.b64encode(cobdev.png_write(*img)).decode()
        return out

    def call(self, path, params=None, post=None, timeout=60):
        q = params or post or {}
        self.sent.append((path, q))
        if path == "/has":
            return {"ok": True, "present": True}
        if path == "/field":
            name = q["path"].rsplit(".", 1)[-1]
            if "value" in q:
                v = q["value"]
                self.fields[name] = v == "true" if v in ("true", "false") else int(v)
                return {"ok": True, "value": self.fields[name]}
            return {"ok": True, "value": self.fields.get(name)}
        if path == "/scale":
            self.scales.append(q["n"])
        if path == "/command" and q["command"].startswith("/tp @p ~0.5"):
            self.cut += 1
        if path == "/probe":
            name, args = q["name"], q.get("args", "").split(cobdev.ARG_SEP) if q.get("args") else []
            if name == "mb-record" and args == ["log"]:
                before = self.cut - 1
                return {"ok": True, "frames": [self.record(cutSerial=before, cutSerialNow=before),
                                               self.record(resetReason="position-correction"), self.record()]}
            if name == "mb-record":
                return dict(self.record(), ok=True)
            if name == "mb-pose":
                self.yaw = float(args[0])
                return {"ok": True}
            if name == "mb-shot" and args == ["stopTurn"]:
                self.yaw = 0.4374999701976776
                rec = self.record(smearPx=8.6, taps=2, active=True, worldPassRan=True)
                return dict(self.shot(self.turned if self.blurs else self.rest, rec), turning=True, turnElapsedMs=80)
            if name == "mb-shot":
                return dict(self.shot(self.rest, self.record()), turning=False)
            if name == "hud-boxes":
                return {"ok": True, "scale": 2, "sw": 200, "sh": 120,
                        "boxes": [{"id": "armor", "x": 4, "y": 6, "w": 30, "h": 10, "pad": 1, "shown": True}]}
        return {"ok": True}

    def status(self):
        return {"guiScaleSetting": 3, "scaleFactor": 2, "display": "%dx%d" % (self.W, self.H)}


class MotionBlurDryRun(unittest.TestCase):
    """Step MB against a fake game: it runs to the end, judges the frames, and puts everything back."""

    def run_step(self, game):
        real_sleep, real_shots = cobdev.time.sleep, cobdev.SHOTS
        cobdev.time.sleep = lambda t: None
        with tempfile.TemporaryDirectory() as tmp:
            cobdev.SHOTS = tmp
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    cobdev.smoke_motion_blur(game, "com.bedwarsqol.BedwarsQol.config")
            finally:
                cobdev.time.sleep, cobdev.SHOTS = real_sleep, real_shots
        return {st["id"]: st for st in game.report["steps"]}

    def test_a_blurring_game_passes_and_everything_is_restored(self):
        g = FakeMotionBlurGame()
        steps = self.run_step(g)
        self.assertEqual(list(steps), ["MB", "MB-cut", "MB-still", "MB-turn"])
        for sid, st in steps.items():
            self.assertEqual(st["status"], "pass", (sid, [c for c in st["checks"] if not c["ok"]], st.get("error")))
        self.assertEqual(g.fields, {"motionBlur": False, "mbStrength": 3, "fpsEnabled": True, "cpsEnabled": True,
                                    "pingEnabled": False})
        commands = [q["command"] for p, q in g.sent if p == "/command"]
        self.assertIn("/fill -10 4 8 10 12 8 minecraft:wool 0", commands)
        self.assertIn(cobdev.mb_board_clear(), commands)
        self.assertEqual(g.scales[-1], 3)
        self.assertIn(("/invoke", {"path": cobdev.MB + ".release", "args": ""}), g.sent)
        poses = [q["args"] for p, q in g.sent if p == "/probe" and q["name"] == "mb-pose"]
        self.assertIn(cobdev.ARG_SEP.join(["0.4374999701976776", "0"]), poses)  # the turn frame's own yaw

    def test_a_game_that_does_not_blur_fails_the_turn_step(self):
        steps = self.run_step(FakeMotionBlurGame(blurs=False))
        self.assertEqual(steps["MB-turn"]["status"], "fail")
        self.assertEqual(steps["MB-still"]["status"], "pass")


class RewindSmoke(unittest.TestCase):
    """RW (PLAN A11): passes only on RewindCheck's verdict for one closed recording, records into cobdev's own
    relative folder, and puts both Rewind settings back whatever happens."""

    CFG = "com.bedwarsqol.BedwarsQol.config"

    DONE = {"ok": True, "phase": "DONE", "done": True, "failure": None, "dug": True, "placed": True,
            "inventoryShown": True, "perspectiveShown": True, "chestOpened": True, "chestSlots": 90, "chestFilled": 54,
            "jumped": True}

    def run_step(self, latest, present=True, playback=False, actions=None, cleanup=None, poll_error=None):
        calls = []
        fields = {self.CFG + ".rewind": "false", self.CFG + ".rewindRecordGames": "true",
                  self.CFG + ".fpsEnabled": "false"}
        status = self.DONE if actions is None else actions

        def actions_reply(arg):
            if arg == "status":
                return status
            if arg == "cleanup":
                return {"ok": True, "restored": True} if cleanup is None else cleanup
            return {"ok": True}

        class Fake(cobdev.Smoke):
            def call(self, path, params=None, post=None, timeout=60):
                q = params or post or {}
                calls.append((path, dict(q)))
                self.step["commands"].append({"call": path})
                if path == "/field":
                    if "value" in q:
                        fields[q["path"]] = q["value"]
                        return {"ok": True}
                    return {"ok": True, "value": fields.get(q["path"])}
                if path == "/probe":
                    if q["name"] == "rewind-watch-actions":
                        return actions_reply(q["args"])
                    return {"ok": True, "deleted": 0, "left": 0} if q["name"] == "rewind-clear" else latest
                if path == "/world":
                    return {"ok": True, "save": "cobdev"}
                return {"ok": True}

            def has(self, cls, member=None):
                return playback if cls == cobdev.REWIND_PLAYBACK else present

            def peek(self, name, *args):
                if name == "rewind-watch-actions" and poll_error is not None:
                    raise poll_error
                return actions_reply(args[0]) if name == "rewind-watch-actions" else None

        s = Fake("dev", "test")
        self.last_step = (s, calls, fields)
        ready = {"title": True, "inWorld": True, "responsive": True}
        watched = []
        with mock.patch.object(cobdev, "api_quiet", return_value=ready), mock.patch.object(cobdev.time, "sleep"), \
                mock.patch.object(cobdev, "smoke_rewind_watch", lambda st: watched.append("RWW")), \
                mock.patch.object(cobdev, "smoke_rewind_timings", lambda st: watched.append("RWW-time")):
            cobdev.smoke_rewind(s, self.CFG)
        self.watched = watched
        return s, calls, fields

    def test_with_playback_rw_records_a_swing_in_survival_then_hands_over_to_rww(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True, "line": RewindActionCounts.LINE}
        s, calls, fields = self.run_step(latest, playback=True)
        self.assertEqual(s.step["status"], "pass", s.step["checks"])
        self.assertEqual(self.watched, ["RWW", "RWW-time"])
        key = {"/command": "command", "/probe": "name", "/keyhold": "state"}
        seq = [(c[0], c[1].get(key[c[0]])) for c in calls if c[0] in key]
        i_survival = seq.index(("/command", "/gamemode 0"))
        i_swing = seq.index(("/probe", "rewind-watch-swing"))
        i_creative = seq.index(("/command", "/gamemode 1"))
        i_leave = max(i for i, c in enumerate(calls) if c == ("/world", {"leave": "1"}))
        self.assertLess(i_survival, i_swing)
        # a raw keyhold never sets a binding, so RW holds none: the actions' jump is what writes INPUT
        self.assertFalse([c for c in calls if c[0] == "/keyhold"])
        # Rewind parity Phase 16: the actions run while recording, after the swing; their cleanup before the leave
        acts = [c[1]["args"] for c in calls if c[0] == "/probe" and c[1]["name"] == "rewind-watch-actions"]
        self.assertEqual(acts, ["status", "start", "cleanup"])
        i_start = seq.index(("/probe", "rewind-watch-actions"))
        self.assertLess(i_swing, i_start)
        self.assertLess(i_start, i_creative)
        self.assertEqual(fields[self.CFG + ".fpsEnabled"], "false")  # the HUD item is put back
        self.assertIn(("/field", {"path": self.CFG + ".fpsEnabled", "value": "true"}), calls)
        names = [c["check"] for c in s.step["checks"]]
        self.assertIn("RW actions: the double chest opened with its 54 slots filled at its first tick (90 in all)",
                      names)
        self.assertLess(calls.index(("/command", {"command": "/gamemode 1"})), i_leave)
        clears = [c for c in calls if c[0] == "/probe" and c[1]["name"] == "rewind-clear"]
        self.assertEqual(len(clears), 2)  # RWW watches before the folder is cleared

    def test_actions_short_of_the_chest_or_their_records_fail_rw(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True, "line": RewindExtCounts.LINE}
        half = dict(self.DONE, chestFilled=27)
        s, calls, _ = self.run_step(latest, playback=True, actions=half)
        failed = [c["check"] for c in s.step["checks"] if not c["ok"]]
        self.assertEqual(failed, [
            "RW actions: the double chest opened with its 54 slots filled at its first tick (90 in all)",
            "format v2: the actions' dig, blocks, sounds, outbound packets, views, mouse, window and HUD"])
        acts = [c[1]["args"] for c in calls if c[0] == "/probe" and c[1]["name"] == "rewind-watch-actions"]
        self.assertEqual(acts[-1], "cleanup")

    def test_the_jump_must_happen_and_leave_an_input_record(self):
        line = RewindActionCounts.LINE
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True, "line": line}
        s, _, _ = self.run_step(latest, playback=True, actions=dict(self.DONE, jumped=False))
        self.assertEqual([c["check"] for c in s.step["checks"] if not c["ok"]],
                         ["RW actions: the stone dug, wool placed, inventory and F5 shown, one jump"])
        latest = dict(latest, line=line.replace("extInput=2", "extInput=0"))
        s, _, _ = self.run_step(latest, playback=True)
        failed = [c for c in s.step["checks"] if not c["ok"]]
        self.assertEqual([c["check"] for c in failed], ["format v2: heartbeat, settings, one identity, 41 inventory "
                                                        "slots, view, FOV and held input recorded"])
        # without the actions (no playback) nothing holds a binding, so no INPUT record is asked for
        s, _, _ = self.run_step(latest)
        self.assertEqual(s.step["status"], "pass", s.step["checks"])

    def test_the_actions_cleanup_runs_after_a_failure_and_its_result_is_checked(self):
        # review code-2 I1: the cleanup is in a finally (a failure between start and cleanup would leave the stone,
        # the chest and the items for the next start to refuse), and a cleanup that did not restore fails RW
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True, "line": RewindActionCounts.LINE}
        with self.assertRaises(RuntimeError):
            self.run_step(latest, playback=True, poll_error=RuntimeError("bridge went away"))
        s, calls, _ = self.last_step
        acts = [c[1]["args"] for c in calls if c[0] == "/probe" and c[1]["name"] == "rewind-watch-actions"]
        self.assertEqual(acts, ["status", "start", "cleanup"])

        s, calls, _ = self.run_step(latest, playback=True, cleanup={"ok": True, "restored": False})
        failed = [c["check"] for c in s.step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["RW actions: the smoke world put back (blocks, inventory, dropped items)"])
        s, calls, _ = self.run_step(latest, playback=True)
        self.assertIn("RW actions: the smoke world put back (blocks, inventory, dropped items)",
                      [c["check"] for c in s.step["checks"] if c["ok"]])

    def test_without_playback_rw_runs_no_actions(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True, "line": RewindExtCounts.LINE}
        s, calls, _ = self.run_step(latest)
        self.assertEqual(s.step["status"], "pass", s.step["checks"])
        self.assertFalse([c for c in calls if c[0] == "/probe" and c[1]["name"] == "rewind-watch-actions"])

    def test_a_failing_rw_check_does_not_watch(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 16, "pass": False, "line": "markerKey=0"}
        self.run_step(latest, playback=True)
        self.assertEqual(self.watched, [])

    def test_a_passing_check_passes_and_settings_come_back(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True, "line": RewindExtCounts.LINE}
        s, calls, fields = self.run_step(latest)
        self.assertEqual(s.step["status"], "pass", s.step["checks"])
        self.assertEqual(fields[self.CFG + ".rewind"], "false")
        self.assertEqual(fields[self.CFG + ".rewindRecordGames"], "true")
        paths = [c[0] for c in calls]
        self.assertFalse([c for c in calls if c[0] == "/keyhold"])
        self.assertIn(("/invoke", {"path": cobdev.REWIND + ".markerKeyPressed", "args": ""}), calls)
        self.assertLess(paths.index("/invoke"), max(i for i, c in enumerate(calls) if c == ("/world", {"leave": "1"})))
        clears = [c for c in calls if c[0] == "/probe" and c[1]["name"] == "rewind-clear"]
        self.assertEqual(len(clears), 2)  # before the session and after the check

    def test_a_passing_v1_recording_fails_the_format_v2_check(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 0, "pass": True,
                  "line": "complete=true formatVersion=1 pass=true"}
        s, _, fields = self.run_step(latest)
        self.assertEqual(s.step["status"], "fail")
        failed = [c for c in s.step["checks"] if not c["ok"]]
        self.assertEqual(len(failed), 1)
        self.assertIn("format v2", failed[0]["check"])
        self.assertEqual(fields[self.CFG + ".rewind"], "false")

    def test_a_failing_check_fails_and_still_cleans_up(self):
        latest = {"ok": True, "found": True, "closed": 1, "exit": 16, "pass": False, "line": "markerKey=0"}
        s, calls, fields = self.run_step(latest)
        self.assertEqual(s.step["status"], "fail")
        self.assertEqual(fields[self.CFG + ".rewind"], "false")
        self.assertEqual(calls[-1][0], "/invoke")  # the restored settings are saved last

    def test_no_recording_fails(self):
        s, _, _ = self.run_step({"ok": True, "found": False, "closed": 0})
        self.assertEqual(s.step["status"], "fail")

    def test_an_older_build_is_not_a_failure(self):
        s, calls, _ = self.run_step({}, present=False)
        self.assertEqual(s.step["status"], "not available yet")
        self.assertEqual(calls, [])

    def test_every_runtime_records_into_the_dev_clients_own_relative_folder(self):
        for mode in ("dev", "prod"):
            flags = cobdev.launch_flags("/x/devagent.jar", mode)
            rewind = [f for f in flags if f.startswith("-Dcobblify.rewindDir=")]
            self.assertEqual(rewind, ["-Dcobblify.rewindDir=" + cobdev.REWIND_DIR])
            self.assertFalse(os.path.isabs(cobdev.REWIND_DIR))
            self.assertNotIn(".cobblify", cobdev.REWIND_DIR)
            self.assertIn("-javaagent:/x/devagent.jar", flags)
            self.assertIn("-Dcobdev.mode=" + mode, flags)

    def test_smoke_only_rewind_parses(self):
        args = cobdev.build_parser().parse_args(["smoke", "--only", "rewind"])
        self.assertEqual(args.only, "rewind")


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


class RewindWatchHelpers(unittest.TestCase):
    """Step RWW's pure helpers (PLAN 2026-11-17-rewind-watch Phase 7)."""

    def test_points_are_30_s_either_side_of_the_middle_or_half_the_way(self):
        self.assertEqual(cobdev.rww_points(0, 8000), (4000, 3400, 4600))
        self.assertEqual(cobdev.rww_points(20, 220), (120, 70, 170))  # a 10 s recording: half the way
        k, fwd, back = cobdev.rww_points(20, 21)
        self.assertTrue(20 <= fwd <= k <= back <= 21)

    def test_differs_names_the_groups_that_are_not_equal_or_missing(self):
        same = {"groups": {g: "h" + g for g in cobdev.CONTRACT_GROUPS}}
        self.assertEqual(cobdev.rww_differs(same, dict(same), dict(same)), [])
        other = {"groups": dict(same["groups"], **{"3-chunks": "x"})}
        self.assertEqual(cobdev.rww_differs(same, other, same), ["3-chunks"])
        partial = {"groups": {g: v for g, v in same["groups"].items() if g != "7-camera"}}
        self.assertEqual(cobdev.rww_differs(partial, partial, partial), ["7-camera"])
        self.assertEqual(cobdev.rww_differs(None, same, same), list(cobdev.CONTRACT_GROUPS))

    def test_the_fov_setting_used_differs_from_the_recording_and_the_players_own(self):
        self.assertEqual(cobdev.rww_fov_setting(70.0, 70.0), 110.0)
        self.assertEqual(cobdev.rww_fov_setting(100.0, 70.0), 30.0)
        self.assertEqual(cobdev.rww_fov_setting(110.0, 30.0), 50.0)
        for recorded in (30.0, 47.5, 70.0, 82.25, 96.0, 110.0, 126.5):
            for own in (30.0, 70.0, 90.0, 110.0):
                v = cobdev.rww_fov_setting(recorded, own)
                self.assertTrue(30.0 <= v <= 110.0)
                self.assertGreaterEqual(abs(v - recorded), 15, (recorded, own))
                self.assertGreaterEqual(abs(v - own), 15, (recorded, own))

    def test_ms_per_game_minute_of_the_target(self):
        self.assertEqual(cobdev.ms_per_game_minute(6000, 6 * 1200), 1000.0)
        self.assertEqual(cobdev.ms_per_game_minute(500, 0), 500 * 1200.0)  # never divides by zero

    def test_longest_bedwars_reads_only_closed_bed_wars_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            def rec(rid, server, length, complete=True, cbr=True):
                with open(os.path.join(d, rid + ".json"), "w") as f:
                    json.dump({"complete": complete, "serverType": server, "lengthUs": length}, f)
                if cbr:
                    open(os.path.join(d, rid + ".cbr"), "wb").close()
            rec("20261101-100000-aaaa", "BEDWARS", 300)
            rec("20261101-110000-bbbb", "BEDWARS", 500)
            rec("20261101-120000-cccc", "DUELS", 900)
            rec("20261101-130000-dddd", "BEDWARS", 950, complete=False)
            rec("20261101-140000-eeee", "BEDWARS", 990, cbr=False)
            rec("20261101-150000-ffff", "REPLAY", 999)
            open(os.path.join(d, "20261101-110000-bbbb.stats.json"), "w").close()
            cbr, meta = cobdev.longest_bedwars(d)
            self.assertEqual(os.path.basename(cbr), "20261101-110000-bbbb.cbr")
            self.assertEqual(os.path.basename(meta), "20261101-110000-bbbb.json")
            self.assertIsNone(cobdev.longest_bedwars(os.path.join(d, "missing")))


class FakePlayback(cobdev.Smoke):
    """A game whose Rewind session answers like the real one does when everything works."""

    START, END, SWING = 20, 1000, 50
    PERSPECTIVE, WINDOW = 300, 400
    # RW's one FOV record (extFov=1): from its first tick on your view's world FOV is RECORDED_FOV
    FOV_TICK, RECORDED_FOV, OWN_FOV = 22, 70.0, 90.0

    def __init__(self):
        super().__init__("dev", "test")
        self.st = {"ok": True, "active": False, "state": "IDLE", "timerSpeed": 1.0, "thirdPersonView": 0,
                   "chatLines": 3, "updateCounter": 77, "landedMs": -1, "stoppedAt": -1, "discarded": None}
        self.pose = 0
        self.closed = 1
        self.fov_setting = self.OWN_FOV
        self.fov_saved = None
        self.fov_sets = []

    def applied_fov(self):
        """getFOVModifier's world pass: the recorded FOV in your view once it is recorded, else the setting."""
        if self.st.get("camera") == "YOU" and self.st.get("playhead", -1) >= self.FOV_TICK:
            return self.RECORDED_FOV
        return self.fov_setting

    def _fov(self, args):
        if args[:1] == ["set"]:
            if self.fov_saved is None:
                self.fov_saved = self.fov_setting
            self.fov_setting = float(args[1])
            self.fov_sets.append(self.fov_setting)
        elif args == ["restore"]:
            if self.fov_saved is not None:
                self.fov_setting, self.fov_saved = self.fov_saved, None
        recorded = self.RECORDED_FOV if self.st.get("playhead", -1) >= self.FOV_TICK else None
        return {"ok": True, "setting": self.fov_setting, "applied": self.applied_fov(), "recorded": recorded,
                "interpolating": False, "fovRecords": 1, "firstFovTick": self.FOV_TICK, "camera": self.st.get("camera")}

    def _state(self):
        return dict(self.st, pose="p%d" % self.pose)

    def _land(self, playhead=None):
        if playhead is not None:
            self.st["playhead"] = self.st["ticksRun"] = max(self.START, min(self.END, playhead))
        self.st["landedMs"] = 12

    def call(self, path, params=None, post=None, timeout=60):
        self.step["commands"].append({"call": path})
        st = self.st
        if path == "/has":
            return {"ok": True, "present": True}
        if path == "/key":
            code = params["code"]
            if code == cobdev.KEYS["space"]:
                st["paused"] = not st["paused"]
            elif code in (cobdev.KEYS["1"], cobdev.KEYS["2"]):
                free = code == cobdev.KEYS["2"]
                st["camera"] = "FREE" if free else "YOU"
                st["controllerGameType"] = "SPECTATOR" if free else "SURVIVAL"
                st["cameraHurtTime"] = 0 if free else 4
            return {"ok": True}
        if path == "/keyhold":
            if params["state"] == "on" and st.get("camera") == "FREE":
                self.pose += 1
            return {"ok": True}
        if path != "/probe":
            return {"ok": True}
        name = post["name"]
        args = post["args"].split(cobdev.ARG_SEP) if post["args"] else []
        if name == "rewind-watch-state":
            return self._state()
        if name == "rewind-latest":
            return {"ok": True, "closed": self.closed, "found": True}
        if name == "rewind-watch-open":
            st.update(active=True, state="WATCHING", muted=True, failures=0, camera="YOU", paused=False,
                      startTick=self.START, endTick=self.END, discarded=2, firstSwingTick=self.SWING,
                      perspectiveTick=self.PERSPECTIVE, windowOpenTick=self.WINDOW, windowCloseTick=self.WINDOW + 10,
                      controllerGameType="SURVIVAL", cameraSlot=1, recordedSlot=1, cameraHeld="sword",
                      recordedHeld="sword", cameraSwinging=True, recordedGameType="SURVIVAL")
            self._land(self.START)
            return {"ok": True, "closed": self.closed}
        if name == "rewind-watch-seek":
            target = int(args[0]) if len(args) == 1 else st["playhead"] + int(args[1]) // 50
            st["discarded"] += 2 if target < st["playhead"] else 0  # a rebuild's join
            self._land(target)
            st["thirdPersonView"] = 1 if self.PERSPECTIVE < st["playhead"] <= self.PERSPECTIVE + 10 else 0
            return {"ok": True, "target": st["playhead"]}
        if name == "rewind-watch-window":
            inside = self.WINDOW <= st["playhead"] < self.WINDOW + 10
            return {"ok": True, "held": "chest" if inside else "none", "windowOpen": inside,
                    "slots": 90 if inside else 0, "ownSlots": 54 if inside else 0, "ownFilled": 54 if inside else 0,
                    "inventory": "inv"}
        if name == "rewind-watch-play-to":
            st["paused"] = True
            st["stoppedAt"] = int(args[0])
            self._land(int(args[0]))
            return {"ok": True}
        if name == "rewind-watch-snapshot":
            return {"ok": True, "groups": {g: "h" for g in cobdev.CONTRACT_GROUPS},
                    "counts": {"tick": st["playhead"], "excludedNonLiving": 3, "hudItemsShown": 1}, "missing": []}
        if name == "rewind-watch-quit":
            st.update(active=False, state="IDLE")
            self._land()
            return {"ok": True}
        if name == "rewind-watch-swing":
            return {"ok": True, "slot": 1}
        if name == "rewind-watch-fov":
            return self._fov(args)
        return {"ok": True}

    def peek(self, name, *args):
        return self._state() if name == "rewind-watch-state" else None

    def shot(self, tag, wait=300):
        self.step["shots"].append(tag)
        return tag, None

    def status(self):
        return {"title": self.st["state"] == "IDLE"}


class RewindWatchDryRun(unittest.TestCase):
    """Step RWW against a fake session: every check runs and passes; a wrong snapshot or restore fails it."""

    def run_step(self, s):
        real_sleep = cobdev.time.sleep
        cobdev.time.sleep = lambda t: None
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                s.begin("RW", "fake")
                cobdev.smoke_rewind_watch(s)
        finally:
            cobdev.time.sleep = real_sleep
        return s.report["steps"][-1]

    def test_every_check_passes_on_a_working_session(self):
        step = self.run_step(FakePlayback())
        self.assertEqual(step["id"], "RWW")
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, [])
        self.assertEqual(len(step["checks"]), 22)
        self.assertEqual(step["shots"], ["rww-your-view", "rww-free-camera", "rww-double-chest"])

    FOV_CHECKS = ["3d RW recorded a FOV and your view is past it (one sample, not between two)",
                  "3d the client's FOV setting is changed to differ from the recorded FOV",
                  "3d your view: the world FOV is the recorded FOV, not the client's setting",
                  "3d free camera: the world FOV follows the client's setting, not the recording",
                  "3d the client's FOV setting is put back"]

    def test_your_views_fov_is_the_recorded_one_whatever_the_setting_and_the_setting_comes_back(self):
        s = FakePlayback()
        step = self.run_step(s)
        checks = {c["check"]: c["ok"] for c in step["checks"]}
        self.assertEqual([checks.get(c) for c in self.FOV_CHECKS], [True] * 5)
        self.assertEqual(len(s.fov_sets), 1)
        changed = s.fov_sets[0]
        self.assertGreaterEqual(abs(changed - s.RECORDED_FOV), 15)
        self.assertGreaterEqual(abs(changed - s.OWN_FOV), 15)
        self.assertEqual(s.fov_setting, s.OWN_FOV)
        self.assertIsNone(s.fov_saved)

    def test_a_view_that_follows_the_setting_fails_the_fov_check_and_still_puts_it_back(self):
        class Live(FakePlayback):
            def applied_fov(self):
                return self.fov_setting  # the recorded FOV never applied
        s = Live()
        step = self.run_step(s)
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["3d your view: the world FOV is the recorded FOV, not the client's setting"])
        self.assertEqual(s.fov_setting, s.OWN_FOV)

    def test_a_setting_left_changed_fails(self):
        class Sticky(FakePlayback):
            def _fov(self, args):
                return super()._fov([] if args == ["restore"] else args)
        step = self.run_step(Sticky())
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["3d the client's FOV setting is put back"])

    def test_a_recording_with_no_fov_record_fails_3d_and_changes_no_setting(self):
        class NoFov(FakePlayback):
            FOV_TICK = 10 ** 6
        s = NoFov()
        step = self.run_step(s)
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["3d RW recorded a FOV and your view is past it (one sample, not between two)"])
        self.assertEqual(s.fov_sets, [])

    def test_a_check_in_the_middle_that_raises_still_puts_the_setting_back(self):
        class Broken(FakePlayback):
            def call(self, path, params=None, post=None, timeout=60):
                if path == "/key" and params["code"] == cobdev.KEYS["2"] and self.fov_saved is not None:
                    raise cobdev.ApiError("the game went away")
                return super().call(path, params, post, timeout)
        s = Broken()
        with self.assertRaises(cobdev.ApiError):
            self.run_step(s)
        self.assertEqual(s.fov_setting, s.OWN_FOV)

    def test_a_backward_seek_that_differs_fails_step_3(self):
        class Drift(FakePlayback):
            def call(self, path, params=None, post=None, timeout=60):
                r = super().call(path, params, post, timeout)
                if post and post.get("name") == "rewind-watch-snapshot" and self.st["discarded"] > 4:
                    r["groups"]["6-chat-titles"] = "other"  # after the backward rebuild
                return r
        step = self.run_step(Drift())
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["3 straight play, forward seek and backward seek agree in every contract group"])

    def test_a_parity_group_that_differs_or_a_chest_short_of_its_slots_fails(self):
        class Drift(FakePlayback):
            def call(self, path, params=None, post=None, timeout=60):
                r = super().call(path, params, post, timeout)
                if post and post.get("name") == "rewind-watch-snapshot" and self.st["discarded"] > 4:
                    r["groups"]["9-window"] = "other"
                if post and post.get("name") == "rewind-watch-window" and r.get("held") == "chest":
                    r["ownFilled"] = 27
                return r
        step = self.run_step(Drift())
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["3 straight play, forward seek and backward seek agree in every contract group",
                                  "3c the double chest: held, 90 slots, its 54 filled, the camera's inventory untouched"])

    def test_a_recording_without_the_actions_notes_3c_and_still_passes(self):
        class NoActions(FakePlayback):
            PERSPECTIVE = WINDOW = -1
        step = self.run_step(NoActions())
        self.assertEqual([c["check"] for c in step["checks"] if not c["ok"]], [])
        self.assertTrue(any(n["note"].startswith("3c the recording has no recorded F5") for n in step["notes"]))

    def test_a_chat_line_left_behind_fails_step_4(self):
        class Leak(FakePlayback):
            def call(self, path, params=None, post=None, timeout=60):
                r = super().call(path, params, post, timeout)
                if post and post.get("name") == "rewind-watch-quit":
                    self.st["chatLines"] = 4
                return r
        step = self.run_step(Leak())
        failed = [c["check"] for c in step["checks"] if not c["ok"]]
        self.assertEqual(failed, ["4 timer speed, F5 view, chat lines and update counter are back"])

    def test_an_older_bridge_is_not_available_not_a_failure(self):
        class Old(FakePlayback):
            def try_call(self, path, params=None, post=None, timeout=60):
                if post and post.get("name") == "rewind-watch-state":
                    return None, "unknown probe rewind-watch-state"
                return super().try_call(path, params, post, timeout)
        step = self.run_step(Old())
        self.assertEqual(step["status"], "not available yet")


if __name__ == "__main__":
    unittest.main(verbosity=1)
