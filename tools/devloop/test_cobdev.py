"""Game-free tests for cobdev's pure helpers. Run: python3 -I tools/devloop/test_cobdev.py (or cobdev selftest)."""
import contextlib
import importlib.machinery
import io
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

    def __init__(self, boxes, scale=2):
        cobdev.Smoke.__init__(self, "dev", "test")
        self.begin("A4-edithud", "test")
        self.boxes = {b["id"]: dict(b) for b in boxes}
        self.sc, self.selected, self.press = scale, None, None

    def call(self, path, params=None, post=None, timeout=60):
        q = params or post or {}
        if path == "/scale":
            self.sc = int(q["n"])
        elif path == "/probe":
            return {"scale": self.sc, "boxes": [dict(b) for b in self.boxes.values()]}
        elif path == "/click":
            self.selected = next(i for i, b in self.boxes.items()
                                 if b["x"] <= q["x"] <= b["x"] + b["w"] and b["y"] <= q["y"] <= b["y"] + b["h"])
        elif path == "/key" and self.selected:
            step = 10 if q.get("mods") == "shift" else 1
            self.boxes[self.selected]["x"] += step * (1 if q["code"] == cobdev.KEYS["right"] else -1) / float(self.sc)
        elif path == "/down":
            b = next(b for b in self.boxes.values() if b["shown"])
            self.selected, self.press = b["id"], (q["x"], b["x"])
        elif path == "/drag":
            b = self.boxes[self.selected]
            x = self.press[1] + (q["x"] - self.press[0]) / float(self.sc)
            if q.get("mods") != "alt" and 0 < x - b["pad"] <= 4.0 / self.sc:
                x = b["pad"]
            b["x"] = x
        return {"ok": True, "value": False}

    def shot(self, tag, wait=300):
        return "/dev/null", (1, 1, [b"\0\0\0"])


class EditHudChecks(unittest.TestCase):
    def run_check(self, fn, boxes):
        s = FakeEditor(boxes)
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
