#!/usr/bin/env python3
"""Build a separate, fixture-only native macOS GUI app from the current frontend."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-frontend-build", action="store_true", help="Use the current launcher/dist; normal builds run npm run build first")
    parser.add_argument("--output", type=Path, default=Path("/Users/Shared/Cobblify-GUI-Test"))
    parser.add_argument("--target-dir", type=Path, default=Path.home() / ".cache/cobblify-gui-test/target")
    parser.add_argument("--sdk", type=Path, default=Path("/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"))
    args = parser.parse_args()
    if os.uname().sysname != "Darwin" or os.uname().machine != "arm64":
        parser.error("this native fixture app currently targets Apple Silicon macOS")
    if not args.sdk.is_dir():
        parser.error(f"SDK not found: {args.sdk}; use --sdk to select the intended macOS SDK")
    source = Path(__file__).resolve().parent
    launcher = source.parent.parent
    if not args.skip_frontend_build:
        run("npm", "run", "build", cwd=launcher)
    dist = launcher / "dist"
    if not (dist / "index.html").is_file():
        parser.error("launcher/dist is missing; build the frontend first")
    # Compile a private staging copy. Generated assets/schemas never dirty the repository.
    build_root = Path.home() / ".cache/cobblify-gui-test"
    build_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="source-", dir=build_root) as scratch:
        staged = Path(scratch) / "native-preview"
        shutil.copytree(source, staged, ignore=shutil.ignore_patterns("assets", "gen", "target", "__pycache__"))
        fixtures = subprocess.run(["node", str(source / "export-fixtures.mjs")], check=True, capture_output=True, text=True)
        (staged / "fixtures.json").write_text(fixtures.stdout)
        native_chrome = launcher / "src-tauri/src/window_chrome.rs"
        shutil.copy2(native_chrome, staged / "src/window_chrome.rs")
        shutil.copytree(dist, staged / "assets")
        (staged / "icons").mkdir()
        shutil.copy2(launcher / "src-tauri/icons/icon.png", staged / "icons/icon.png")
        env = os.environ.copy()
        env.update(SDKROOT=str(args.sdk), MACOSX_DEPLOYMENT_TARGET="11.0", CARGO_TARGET_DIR=str(args.target_dir.resolve()))
        # Product release credentials are neither required nor read by this package.
        run("cargo", "build", "--locked", "--release", "--target", "aarch64-apple-darwin", "--manifest-path", str(staged / "Cargo.toml"), cwd=staged, env=env)
        binary = args.target_dir / "aarch64-apple-darwin/release/cobblify-gui-test"
        args.output.mkdir(parents=True, exist_ok=True)
        app = args.output / "Cobblify GUI Test.app"
        if app.exists():
            with (app / "Contents/Info.plist").open("rb") as stream:
                previous = plistlib.load(stream)
            if previous.get("CFBundleIdentifier") != "club.cobblify.gui-test":
                raise RuntimeError(f"refusing to replace an app with a different identifier: {app}")
        with tempfile.TemporaryDirectory(prefix=".assemble-", dir=args.output) as assembly:
            fresh = Path(assembly) / app.name
            contents = fresh / "Contents"
            (contents / "MacOS").mkdir(parents=True)
            (contents / "Resources").mkdir()
            shutil.copy2(binary, contents / "MacOS/cobblify-gui-test")
            info = {
                "CFBundleName": "Cobblify GUI Test", "CFBundleDisplayName": "Cobblify GUI Test",
                "CFBundleIdentifier": "club.cobblify.gui-test", "CFBundleExecutable": "cobblify-gui-test",
                "CFBundlePackageType": "APPL", "CFBundleShortVersionString": "0.0.0", "CFBundleVersion": "1",
                "NSHighResolutionCapable": True, "LSMinimumSystemVersion": "11.0",
            }
            with (contents / "Info.plist").open("wb") as stream:
                plistlib.dump(info, stream)
            assets = staged / "assets"
            files = {str(path.relative_to(assets)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(assets.rglob("*")) if path.is_file()}
            metadata = {"fixtureOnly": True, "sdk": str(args.sdk), "frontendSha256": files, "nativeChromeSha256": hashlib.sha256((staged / "src/window_chrome.rs").read_bytes()).hexdigest(), "fixtureDataSha256": hashlib.sha256((staged / "fixtures.json").read_bytes()).hexdigest(), "fixtureControlsSha256": hashlib.sha256((staged / "fixture-controls.js").read_bytes()).hexdigest()}
            (contents / "Resources/build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
            run("codesign", "--force", "--sign", "-", str(fresh))
            run("codesign", "--verify", "--deep", "--strict", str(fresh))
            # The shared directory is intentionally readable by the owner's GUI account.
            for path in (fresh, *fresh.rglob("*")):
                path.chmod(0o755 if path.is_dir() or path.parent.name == "MacOS" else 0o644)
            if app.exists():
                shutil.rmtree(app)
            shutil.move(str(fresh), str(app))
        run("codesign", "--verify", "--deep", "--strict", str(app))
        args.output.chmod(0o755)
        shutil.copy2(source / "README.md", args.output / "README.md")
        (args.output / "README.md").chmod(0o644)
        print(f"Ready: {app}")
        print(f'Open: open "{app}"')
        print(f'Native check (foreground GUI session): "{app}/Contents/MacOS/cobblify-gui-test" --check')


if __name__ == "__main__":
    main()
