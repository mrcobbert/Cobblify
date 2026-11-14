"""Stage a Forge 1.8.9 (Prism-made, like Cobblify 0.18's) instance for the Windows bench.

Adds to OUT (the same tree as stage.py): game/libraries (Forge + vanilla LWJGL nightly), natives/forge,
java/jre8 (Mojang jre-legacy), inst/forge*/ (mods: Cobblify Forge jar, OptiFine, cobbench-forge), and a
"forge" entry in profiles.json.
"""
import concurrent.futures as cf
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

OUT, FORGE_JSON, COB_FORGE_JAR, BENCH_SRC, JAVA_HOME = sys.argv[1:6]
UA = {"User-Agent": "perfbench-stager/1.0"}


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()


def get(url, dest, sha1=None):
    full = os.path.join(OUT, dest)
    if os.path.exists(full) and (not sha1 or hashlib.sha1(open(full, "rb").read()).hexdigest() == sha1):
        return full
    for attempt in range(4):
        data = fetch(url)
        if not sha1 or hashlib.sha1(data).hexdigest() == sha1:
            break
    else:
        raise SystemExit("sha1 mismatch " + url)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    open(full, "wb").write(data)
    return full


def maven_path(name):
    p = name.split(":")
    g, a, v = p[0], p[1], p[2]
    c = p[3] if len(p) > 3 else None
    return "/".join(g.split(".") + [a, v, f"{a}-{v}" + (f"-{c}" if c else "") + ".jar"])


def allow(lib):
    ok = not lib.get("rules")
    for r in lib.get("rules", []):
        if r.get("os") and r["os"].get("name") != "windows":
            continue
        ok = r["action"] == "allow"
    return ok


vm = json.loads(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
vanilla = json.loads(fetch(next(v for v in vm["versions"] if v["id"] == "1.8.9")["url"]))
forge = json.load(open(FORGE_JSON))

entries = {}
for lib in vanilla["libraries"] + forge["libraries"]:
    if not allow(lib):
        continue
    g, a = lib["name"].split(":")[0:2]
    e = {"cp": None, "nat": None}
    dl = lib.get("downloads", {})
    if dl.get("artifact"):
        art = dl["artifact"]
        e["cp"] = (art["url"], art.get("sha1"), "game/libraries/" + art["path"])
    elif "natives" not in lib:
        base = lib.get("url") or "https://libraries.minecraft.net/"
        base += "" if base.endswith("/") else "/"
        e["cp"] = (base + maven_path(lib["name"]), None, "game/libraries/" + maven_path(lib["name"]))
    if "natives" in lib and "windows" in lib["natives"]:
        nat = dl.get("classifiers", {}).get(lib["natives"]["windows"].replace("${arch}", "64"))
        if nat:
            e["nat"] = (nat["url"], nat.get("sha1"), "game/libraries/" + nat["path"])
    entries[f"{g}:{a}"] = e

jobs = [e["cp"] for e in entries.values() if e["cp"]] + [e["nat"] for e in entries.values() if e["nat"]]

# Mojang jre-legacy (Java 8) for windows-x64
allj = json.loads(fetch(
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"))
leg = allj["windows-x64"]["jre-legacy"][0]
print("jre-legacy", leg["version"]["name"])
man = json.loads(fetch(leg["manifest"]["url"]))
for path, f in man["files"].items():
    if f["type"] == "file":
        jobs.append((f["downloads"]["raw"]["url"], f["downloads"]["raw"]["sha1"], "java/jre8/" + path))

with cf.ThreadPoolExecutor(16) as ex:
    list(ex.map(lambda j: get(j[0], j[2], j[1]), jobs))

nd = os.path.join(OUT, "natives/forge")
os.makedirs(nd, exist_ok=True)
for e in entries.values():
    if e["nat"]:
        with zipfile.ZipFile(os.path.join(OUT, e["nat"][2])) as z:
            for n in z.namelist():
                if not n.startswith("META-INF") and not n.endswith("/"):
                    open(os.path.join(nd, os.path.basename(n)), "wb").write(z.read(n))

cp = [e["cp"][2] for e in entries.values() if e["cp"]] + ["game/versions/1.8.9.jar"]
profiles = json.load(open(os.path.join(OUT, "profiles.json")))
profiles["forge"] = {"classpath": cp, "mainClass": forge["mainClass"], "nativesDir": "natives/forge",
                     "tweakers": forge["+tweakers"], "profileJvmArgs": []}
json.dump(profiles, open(os.path.join(OUT, "profiles.json"), "w"), indent=1)

# OptiFine 1.8.9 HD U M5 from optifine.net (the download page names a one-time link)
of_dest = os.path.join(OUT, "extra/OptiFine_1.8.9_HD_U_M5.jar")
if not os.path.exists(of_dest):
    page = fetch("https://optifine.net/adloadx?f=OptiFine_1.8.9_HD_U_M5.jar").decode("utf-8", "replace")
    m = re.search(r"href=['\"](downloadx\?f=OptiFine_1\.8\.9_HD_U_M5\.jar[^'\"]*)['\"]", page)
    if not m:
        raise SystemExit("OptiFine link not found")
    data = fetch("https://optifine.net/" + m.group(1).replace("&amp;", "&"))
    if not data.startswith(b"PK"):
        raise SystemExit("OptiFine download is not a jar")
    os.makedirs(os.path.dirname(of_dest), exist_ok=True)
    open(of_dest, "wb").write(data)
print("optifine sha1", hashlib.sha1(open(of_dest, "rb").read()).hexdigest(), os.path.getsize(of_dest))

# Compile cobbench-forge against the Forge universal jar + LWJGL 2
build = os.path.join(OUT, "..", "forgebench-build")
shutil.rmtree(build, ignore_errors=True)
os.makedirs(os.path.join(build, "classes"))
ccp = [os.path.join(OUT, p) for p in cp if "forge-1.8.9" in p or "/lwjgl/lwjgl/" in p or "launchwrapper" in p]
srcs = [os.path.join(dp, f) for dp, _, fs in os.walk(BENCH_SRC) for f in fs if f.endswith(".java")]
subprocess.check_call([os.path.join(JAVA_HOME, "bin/javac"), "--release", "8", "-nowarn", "-cp", ":".join(ccp),
                       "-d", os.path.join(build, "classes")] + srcs)
info = [{"modid": "cobbench", "name": "cobbench (benchmark only)", "version": "1.0.0", "mcversion": "1.8.9",
         "description": "Frame-time benchmark; inert unless -Dcobbench.mode is set."}]
open(os.path.join(build, "classes/mcmod.info"), "w").write(json.dumps(info))
bench_jar = os.path.join(OUT, "bench/cobbench-forge.jar"); os.makedirs(os.path.dirname(bench_jar), exist_ok=True)
with zipfile.ZipFile(bench_jar, "w", zipfile.ZIP_DEFLATED) as z:
    for dp, _, fs in os.walk(os.path.join(build, "classes")):
        for f in fs:
            full = os.path.join(dp, f)
            z.write(full, os.path.relpath(full, os.path.join(build, "classes")))

for name in ("forge",):
    d = os.path.join(OUT, "inst", name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(os.path.join(d, "mods"))
    shutil.copy2(COB_FORGE_JAR, os.path.join(d, "mods"))
    shutil.copy2(of_dest, os.path.join(d, "mods"))
    shutil.copy2(bench_jar, os.path.join(d, "mods"))
print("forge cp", len(cp), "entries; mods:", os.listdir(os.path.join(OUT, "inst/forge/mods")))
