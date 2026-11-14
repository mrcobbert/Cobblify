"""Build inst/<config>/ (mods, options.txt, config overrides), unpack the Microsoft JDK, write plan files."""
import json
import os
import shutil
import sys
import zipfile

ST = sys.argv[1]
RESEARCH = sys.argv[2]
BENCH_JAR = sys.argv[3]

bundles = json.load(open(os.path.join(ST, "bundles.json")))
ALL = os.path.join(ST, "mods/oneclient-all")
COB = os.path.join(ST, "mods/cobblify")

# Microsoft OpenJDK 25, top folder stripped
ms = os.path.join(ST, "java/ms-25")
if not os.path.isdir(ms):
    with zipfile.ZipFile(os.path.join(ST, "java/ms-25.zip")) as z:
        for n in z.namelist():
            rel = n.split("/", 1)[1] if "/" in n else ""
            if not rel or n.endswith("/"):
                continue
            dest = os.path.join(ms, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(z.read(n))
os.remove(os.path.join(ST, "java/ms-25.zip")) if os.path.exists(os.path.join(ST, "java/ms-25.zip")) else None

OPTIONS = """renderDistance:12
maxFps:260
enableVsync:false
fancyGraphics:true
ao:2
renderClouds:true
particles:0
mipmapLevels:4
entityShadows:true
useVbo:true
fboEnable:true
guiScale:2
fullscreen:false
pauseOnLostFocus:false
lang:en_US
tutorialStep:none
snooperEnabled:false
soundCategory_master:0.0
chatVisibility:0
"""

PRISM = ["-Duser.language=en",
         "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
         "-Xms512m", "-Xmx4096m",
         "-Dfabric.fixPackageAccess=true", "-Dfabric.gameVersion=1.8.9",
         "-Djava.library.path={natives}"]
ONECLIENT = ["-Xms512M", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:+UseCompactObjectHeaders",
             "-Dfabric.fixPackageAccess=true", "-Dfabric.gameVersion=1.8.9",
             "-Djava.library.path={natives}", "--add-modules=jdk.incubator.vector", "-Xmx4096M",
             "-XX:ErrorFile={game}\\hs_err_%p.log", "-Dfabric.modsFolder={mods}"]
MOJANG = "java/mojang-25/bin/javaw.exe"
MSJDK = "java/ms-25/bin/javaw.exe"
LCALL = {"LC_ALL": "C.UTF-8"}

cob = sorted(os.listdir(COB))
cob_nocob = [m for m in cob if not m.lower().startswith("cobblify")]
one_all = sorted(os.listdir(ALL))
one_perf = sorted(bundles["performance"])
extra = ["WindowsPriorityBoost-1.0.0.jar", "asynclogger-2.2.3+1.8.9-ornithe.jar"]

configs = {
    # Cobblify's Windows Prism instance exactly as the 0.19.0 launcher makes it.
    "cob": dict(profile="cobblify", java=MOJANG, jvm=PRISM, env={}, mods=[(COB, m) for m in cob], oc=False),
    # OneClient as installed by default (all four 1.8.9 bundles).
    "one": dict(profile="oneclient", java=MSJDK, jvm=ONECLIENT, env=LCALL, mods=[(ALL, m) for m in one_all], oc=True),
    # Isolation variants.
    "cobnoc": dict(profile="cobblify", java=MOJANG, jvm=PRISM, env={}, mods=[(COB, m) for m in cob_nocob], oc=False),
    "oneperf": dict(profile="oneclient", java=MSJDK, jvm=ONECLIENT, env=LCALL, mods=[(ALL, m) for m in one_perf], oc=True),
    "cobwpb": dict(profile="cobblify", java=MOJANG, jvm=PRISM, env={}, mods=[(COB, m) for m in cob] + [(ALL, m) for m in extra], oc=False),
    "cobjvm": dict(profile="cobblify", java=MSJDK, jvm=ONECLIENT, env=LCALL, mods=[(COB, m) for m in cob], oc=False),
}

oc_override = os.path.join(RESEARCH, "oc/packs/performance/overrides/config/fabric_loader_dependencies.json")
for name, c in configs.items():
    d = os.path.join(ST, "inst", name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(os.path.join(d, "mods"))
    for src, m in c["mods"]:
        shutil.copy2(os.path.join(src, m), os.path.join(d, "mods", m))
    with open(os.path.join(d, "options.txt"), "w", newline="\r\n") as f:
        f.write(OPTIONS)
    if c["oc"]:
        os.makedirs(os.path.join(d, "config"))
        shutil.copy2(oc_override, os.path.join(d, "config"))
    print(name, len(c["mods"]), "mods")

os.makedirs(os.path.join(ST, "bench"), exist_ok=True)
shutil.copy2(BENCH_JAR, os.path.join(ST, "bench/cobbench.jar"))
shutil.rmtree(os.path.join(ST, "mods"))

plan_configs = {k: {"profile": v["profile"], "java": v["java"], "jvm": v["jvm"], "env": v["env"]} for k, v in configs.items()}
base = {"configs": plan_configs, "width": 1920, "height": 1080, "timeoutSeconds": 420, "pauseSeconds": 8}

smoke = dict(base, name="smoke", benchProps={"warmup": 8, "measure": 5, "settle": 2},
             steps=["prepare:cob:prepare", "template:cob:-", "run:cob:smoke-cob", "run:one:smoke-one"])
json.dump(smoke, open(os.path.join(ST, "plan-smoke.json"), "w"), indent=1)

# Round 1: warm-up launch per config (discarded), then interleaved runs.
order = ["cob", "one", "one", "cob", "cob", "one", "one", "cob", "cob", "one"]
variants = ["cobnoc", "oneperf", "cobwpb", "cobjvm"]
steps = [f"run:{c}:warm-{c}" for c in configs]
count = {c: 0 for c in configs}
vround = 0
for i, c in enumerate(order):
    count[c] += 1
    steps.append(f"run:{c}:{c}-{count[c]}")
    if i % 3 == 2 and vround < 3:
        for v in (variants if vround % 2 == 0 else list(reversed(variants))):
            count[v] += 1
            steps.append(f"run:{v}:{v}-{count[v]}")
        vround += 1
r1 = dict(base, name="round1", benchProps={"warmup": 20, "measure": 20, "settle": 5}, steps=steps)
json.dump(r1, open(os.path.join(ST, "plan-round1.json"), "w"), indent=1)
print(len(steps), "round1 steps")
