"""Stage a Windows benchmark tree: Cobblify's Prism Ornithe instance vs OneClient's 1.8.9 Ornithe cluster.

Everything is downloaded with hash checks into OUT (layout below), then tarred for the Windows box.
  OUT/game/versions/1.8.9.jar         vanilla client jar
  OUT/game/assets/...                 index 1.8 + objects
  OUT/game/libraries/<maven path>     every library jar of both profiles
  OUT/natives/<profile>/              extracted Windows natives
  OUT/mods/<profile>/                 the mods folder of each profile (+ variants built later)
  OUT/java/mojang-25/                 Mojang java-runtime-epsilon (what Cobblify pins in Prism)
  OUT/java/ms-25.zip                  Microsoft OpenJDK 25 (OneClient's default vendor)
  OUT/profiles.json                   classpath (relative), natives dir, main class, JVM args per profile
"""
import concurrent.futures as cf
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

OUT = sys.argv[1]
RESEARCH = sys.argv[2]  # earlier session's scratchpad (cached meta, OneClient packs)
REPO = sys.argv[3]  # perf-bench worktree
COBBLIFY_JAR = sys.argv[4]

UA = {"User-Agent": "perfbench-stager/1.0"}


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def digest(data, algo):
    return hashlib.new(algo, data).hexdigest()


def get(url, dest, sha1=None, sha512=None, sha256=None):
    dest = os.path.join(OUT, dest)
    if os.path.exists(dest):
        data = open(dest, "rb").read()
        if (not sha1 or digest(data, "sha1") == sha1) and (not sha512 or digest(data, "sha512") == sha512) and (
            not sha256 or digest(data, "sha256") == sha256
        ):
            return dest
    data = fetch(url)
    for algo, want in (("sha1", sha1), ("sha512", sha512), ("sha256", sha256)):
        if want and digest(data, algo) != want.lower():
            raise SystemExit(f"hash mismatch {algo} {url}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(data)
    return dest


def maven_path(name):
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    file = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    return "/".join(group.split(".") + [artifact, version, file])


def rules_allow(lib):
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        osr = r.get("os")
        if osr and osr.get("name") not in ("windows",):
            continue
        ok = r["action"] == "allow"
    return ok


def resolve(libs):
    """[(key, classpath_entry or None, natives (url, sha1, path) or None)] deduped by group:artifact, last wins."""
    out = {}
    for lib in libs:
        if not rules_allow(lib):
            continue
        name = lib["name"]
        g, a = name.split(":")[0:2]
        key = f"{g}:{a}"
        entry = {"name": name, "artifact": None, "natives": None}
        dl = lib.get("downloads", {})
        on_cp = lib.get("include_in_classpath", True)
        if dl.get("artifact"):
            art = dl["artifact"]
            path = art.get("path") or maven_path(name)
            if on_cp:
                entry["artifact"] = (art["url"], art.get("sha1"), "game/libraries/" + path)
        elif "natives" not in lib and on_cp:
            base = lib.get("url") or "https://libraries.minecraft.net/"
            if not base.endswith("/"):
                base += "/"
            entry["artifact"] = (base + maven_path(name), None, "game/libraries/" + maven_path(name))
        if "natives" in lib and "windows" in lib["natives"]:
            cls = lib["natives"]["windows"].replace("${arch}", "64")
            nat = dl.get("classifiers", {}).get(cls)
            if nat:
                entry["natives"] = (nat["url"], nat.get("sha1"), "game/libraries/" + nat["path"])
        # natives (lwjgl-platform, jinput-platform) and classpath jars use different artifact ids, so
        # last-wins by group:artifact replaces each with the newer profile's version only.
        out[key] = entry
    return list(out.values())


def main():
    os.makedirs(OUT, exist_ok=True)
    jobs = []

    # ── Cobblify (Prism instance as the launcher makes it, Windows) ──
    patch = json.load(open(os.path.join(REPO, "launcher/src-tauri/src/profile/ornithe-net.minecraft.json")))
    orn_profile = json.load(open(os.path.join(RESEARCH, "dl/ornmeta/gen2_profile_1.8.9_0.20.1.json")))
    upgrades = [
        ("org.slf4j:slf4j-api:2.0.17", "https://libraries.minecraft.net/"),
        ("org.apache.logging.log4j:log4j-slf4j2-impl:2.19.0", "https://libraries.minecraft.net/"),
        ("org.apache.logging.log4j:log4j-api:2.19.0", "https://libraries.minecraft.net/"),
        ("org.apache.logging.log4j:log4j-core:2.19.0", "https://libraries.minecraft.net/"),
        ("it.unimi.dsi:fastutil:8.5.9", "https://libraries.minecraft.net/"),
        ("com.google.code.gson:gson:2.10", "https://libraries.minecraft.net/"),
        ("net.ornithemc:log4j-patch:1.0.0", "https://maven.ornithemc.net/releases/"),
        ("net.sf.jopt-simple:jopt-simple:5.0.4", "https://libraries.minecraft.net/"),
        ("com.mojang:brigadier:1.3.11", "https://libraries.minecraft.net/"),
    ]
    loader_libs = [l for l in orn_profile["libraries"] if not l["name"].startswith("net.ornithemc:calamus")]
    cob_libs = (
        patch["libraries"]
        + [{"name": "net.ornithemc:calamus-intermediary-gen2:1.8.9", "url": "https://maven.ornithemc.net/releases/"}]
        + loader_libs
        + [{"name": n, "url": u} for n, u in upgrades]
    )
    cob = resolve(cob_libs)

    # ── OneClient (Polyfrost profile inheriting vanilla 1.8.9) ──
    vm = json.loads(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    v189 = next(v for v in vm["versions"] if v["id"] == "1.8.9")
    vanilla = json.loads(fetch(v189["url"]))
    pf = json.load(open(os.path.join(RESEARCH, "dl/pfmeta/pf_ornithe_1.8.9_0.20.1.json")))
    one = resolve(vanilla["libraries"] + pf["libraries"])

    profiles = {}
    for pname, libs, jvm in (
        ("cobblify", cob, patch["+jvmArgs"]),
        ("oneclient", one, [a for a in pf["arguments"]["jvm"] if isinstance(a, str)]),
    ):
        cp = []
        nat = []
        for e in libs:
            if e["artifact"]:
                jobs.append(e["artifact"])
                cp.append(e["artifact"][2])
            if e["natives"]:
                jobs.append(e["natives"])
                nat.append(e["natives"][2])
        cp.append("game/versions/1.8.9.jar")
        profiles[pname] = {
            "classpath": cp,
            "natives": nat,
            "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "profileJvmArgs": jvm,
            "libs": [e["name"] for e in libs],
        }

    client = vanilla["downloads"]["client"]
    jobs.append((client["url"], client["sha1"], "game/versions/1.8.9.jar"))

    # assets
    ai = vanilla["assetIndex"]
    idx_path = get(ai["url"], f"game/assets/indexes/{ai['id']}.json", sha1=ai["sha1"])
    idx = json.load(open(idx_path))
    for _, o in idx["objects"].items():
        h = o["hash"]
        jobs.append((f"https://resources.download.minecraft.net/{h[:2]}/{h}", h, f"game/assets/objects/{h[:2]}/{h}"))

    # mods
    mods_jobs = []
    cob_pins = [
        ("pylon-0.2.8.jar", "https://cdn.modrinth.com/data/wx17t6U9/versions/zz9ReJww/pylon-0.2.8.jar",
         "d993bbbc1bcbc42698ec4d3685a849b4e247298435513249b96f51fb2e3756db7df24809305643dd1e4dcb623b81c26d68cb0a404f23cb5f71341e6f10a83cfc"),
        ("argentum-1.0.3-beta.8.jar", "https://github.com/Polyfrost/Argentum/releases/download/v1.0.3-beta.8/argentum-1.0.3-beta.8.jar",
         "991de0675a595a7dcf1629e3eaace35d05bd825f1bce2c0554013c6d10f13fa2b33f538ae13f95a508328a46c92612041fd14c2933e94ef9979f6e7377986b67"),
        ("Sarcio-1.2.0-beta.1.jar", "https://github.com/Polyfrost/Sarcio/releases/download/v1.2.0-beta.1/Sarcio-1.2.0-beta.1.jar",
         "92b6830f4609745836880446c8e71ec223fb9f34f2a1acc6d5379d70050b01fc3b1f7f1e5b02d5df50d495d2bcc4a15e15a26373f48caab209d961cd3afe8a00"),
        ("ornithe-standard-libraries-0.23.1.jar", "https://cdn.modrinth.com/data/EHGtwpJv/versions/840EtTTj/ornithe-standard-libraries-0.23.1.jar",
         "85210284ba82c90cefe06f0325cc3cac0497faf341cc5494843e4e1e6b272c5346e909b68021555e188fdbb61f7a4316e4e133388b0947b363c06029d7811bea"),
    ]
    for f, u, h in cob_pins:
        mods_jobs.append((u, None, f"mods/cobblify/{f}", h))
    bundles = {}
    for b in ("performance", "hud", "qol", "utility"):
        idx = json.load(open(os.path.join(RESEARCH, f"oc/packs/{b}/modrinth.index.json")))
        bundles[b] = []
        for f in idx["files"]:
            fname = os.path.basename(f["path"])
            sub = "mods" if f["path"].startswith("mods/") else "resourcepacks"
            dest = f"mods/oneclient-all/{fname}" if sub == "mods" else f"extra/resourcepacks/{fname}"
            mods_jobs.append((f["downloads"][0], f["hashes"].get("sha1"), dest, f["hashes"].get("sha512")))
            if sub == "mods":
                bundles[b].append(fname)
    json.dump(bundles, open(os.path.join(OUT, "bundles.json"), "w"), indent=1)

    # Java: Mojang java-runtime-epsilon for windows-x64
    allj = json.loads(fetch(
        "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"))
    eps = allj["windows-x64"]["java-runtime-epsilon"][0]
    man = json.loads(fetch(eps["manifest"]["url"]))
    print("mojang java", eps["version"]["name"], "manifest sha1", eps["manifest"]["sha1"])
    for path, f in man["files"].items():
        if f["type"] == "file":
            raw = f["downloads"]["raw"]
            jobs.append((raw["url"], raw["sha1"], f"java/mojang-25/{path}"))

    def run(job):
        if len(job) == 4:
            url, sha1, dest, sha512 = job
            return get(url, dest, sha1=sha1, sha512=sha512)
        url, sha1, dest = job
        return get(url, dest, sha1=sha1)

    all_jobs = jobs + mods_jobs
    seen = set()
    uniq = []
    for j in all_jobs:
        if j[2] not in seen:
            seen.add(j[2])
            uniq.append(j)
    print("downloads:", len(uniq))
    with cf.ThreadPoolExecutor(16) as ex:
        for i, _ in enumerate(ex.map(run, uniq)):
            if i % 500 == 0:
                print(" ", i)

    # Microsoft OpenJDK 25 (OneClient's first-choice vendor)
    sumtxt = fetch("https://aka.ms/download-jdk/microsoft-jdk-25-windows-x64.zip.sha256sum.txt").decode().split()
    print("ms jdk sha256", sumtxt[0], sumtxt[-1] if len(sumtxt) > 1 else "")
    get("https://aka.ms/download-jdk/microsoft-jdk-25-windows-x64.zip", "java/ms-25.zip", sha256=sumtxt[0])

    # natives
    for pname, p in profiles.items():
        nd = os.path.join(OUT, "natives", pname)
        os.makedirs(nd, exist_ok=True)
        for jar in p["natives"]:
            with zipfile.ZipFile(os.path.join(OUT, jar)) as z:
                for n in z.namelist():
                    if n.startswith("META-INF") or n.endswith("/"):
                        continue
                    with open(os.path.join(nd, os.path.basename(n)), "wb") as f:
                        f.write(z.read(n))
        p["nativesDir"] = f"natives/{pname}"
        del p["natives"]

    os.makedirs(os.path.join(OUT, "mods/cobblify"), exist_ok=True)
    data = open(COBBLIFY_JAR, "rb").read()
    open(os.path.join(OUT, "mods/cobblify", os.path.basename(COBBLIFY_JAR)), "wb").write(data)
    print("cobblify jar sha256", digest(data, "sha256"))

    json.dump(profiles, open(os.path.join(OUT, "profiles.json"), "w"), indent=1)
    for pname, p in profiles.items():
        print(pname, len(p["classpath"]), "cp entries")


main()
