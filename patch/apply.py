#!/usr/bin/env python3
"""
Applies the Mock-GPS patch to a fresh c:geo checkout.

  python3 patch/apply.py --cgeo <cgeo checkout> --mock <warren-bank/Android-Mock-Location checkout>

* copies MockLocationProvider.java verbatim from warren-bank's repo (package line rewritten)
* adds our own new classes from patch/files
* makes a few small anchored edits to existing c:geo files (manifest, menu, MainActivity, build.gradle)

Every edit is idempotent and FAILS LOUDLY if its anchor disappeared upstream, so a broken patch
never silently produces a build without the feature.
"""
import argparse
import pathlib
import shutil
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from patchlib import die, edit  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent
FILES = HERE / "files"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cgeo", required=True, type=pathlib.Path)
    ap.add_argument("--mock", required=True, type=pathlib.Path)
    ap.add_argument("--wmaps", type=pathlib.Path, help="woheller69/maps checkout (Google Maps WebView patch)")
    a = ap.parse_args()

    main_dir = a.cgeo / "main"
    java_dir = main_dir / "src/main/java/cgeo/geocaching"
    res_dir = main_dir / "src/main/res"
    for p in (main_dir / "AndroidManifest.xml", java_dir / "MainActivity.java",
              res_dir / "menu/main_activity_options.xml", main_dir / "build.gradle"):
        if not p.exists():
            die(f"expected c:geo file missing: {p}")

    target = java_dir / "mockgps"
    target.mkdir(exist_ok=True)

    # 1. code taken from warren-bank/Android-Mock-Location -------------------
    hits = list(a.mock.rglob("MockLocationProvider.java"))
    hits = [h for h in hits if "service/looper" in h.as_posix() and "withGoogle" not in h.as_posix() and "withHuawei" not in h.as_posix()]
    if len(hits) != 1:
        die(f"MockLocationProvider.java not found exactly once in {a.mock}: {hits}")
    src = hits[0].read_text(encoding="utf-8")
    pkg = "package com.github.warren_bank.mock_location.service.looper;"
    if pkg not in src:
        die("package line in MockLocationProvider.java changed upstream")
    if "getLocation(String providerName, double lat, double lon)" not in src or "public void shutdown()" not in src:
        die("MockLocationProvider API changed upstream - MockGpsService must be adapted")
    header = (
        "// Copied from https://github.com/warren-bank/Android-Mock-Location (GPL-2.0, (c) Warren Bank)\n"
        "// which in turn copied it from https://github.com/mcastillof/FakeTraveler\n"
        "// Only the package line was changed by the patch script.\n"
    )
    (target / "MockLocationProvider.java").write_text(
        src.replace(pkg, "package cgeo.geocaching.mockgps;\n\n" + header), encoding="utf-8")
    print("  copied MockLocationProvider.java from", hits[0])

    # 2. our own new files ---------------------------------------------------
    for f in sorted((FILES / "java").glob("*.java")):
        shutil.copy(f, target / f.name)
        print("  added", f.name)
    for f in sorted((FILES / "res/values").glob("*.xml")):
        shutil.copy(f, res_dir / "values" / f.name)
        print("  added res/values/" + f.name)

    # 3. small edits to existing c:geo files --------------------------------
    manifest = main_dir / "AndroidManifest.xml"
    edit(manifest,
         '    <uses-permission android:name="android.permission.READ_CONTACTS" />',
         '    <uses-permission android:name="android.permission.READ_CONTACTS" />\n'
         '    <uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION" tools:ignore="MockLocation" />',
         "ACCESS_MOCK_LOCATION")
    edit(manifest,
         "    </application>",
         '        <!-- Mock GPS patch -->\n'
         '        <activity\n'
         '            android:name=".mockgps.MockGpsActivity"\n'
         '            android:label="Mock GPS"\n'
         '            android:exported="false" />\n'
         '        <service\n'
         '            android:name=".mockgps.MockGpsService"\n'
         '            android:exported="false"\n'
         '            android:foregroundServiceType="location" />\n'
         "    </application>",
         ".mockgps.MockGpsActivity")

    edit(res_dir / "menu/main_activity_options.xml",
         '    <item\n        android:id="@+id/menu_wherigo"',
         '    <item\n'
         '        android:id="@+id/menu_mockgps"\n'
         '        android:icon="@drawable/ic_menu_goto"\n'
         '        android:title="@string/mockgps_menu_title"/>\n\n'
         '    <item\n        android:id="@+id/menu_wherigo"',
         "menu_mockgps")

    edit(java_dir / "MainActivity.java",
         "} else if (id == R.id.menu_wherigo) {",
         "} else if (id == R.id.menu_mockgps) {\n"
         "            startActivity(new Intent(this, cgeo.geocaching.mockgps.MockGpsActivity.class));\n"
         "        } else if (id == R.id.menu_wherigo) {",
         "menu_mockgps")

    # own application id so it installs next to the official c:geo and official dev builds
    edit(main_dir / "build.gradle",
         'applicationIdSuffix = ".developer"',
         'applicationIdSuffix = ".mockgps"',
         'applicationIdSuffix = ".mockgps"')

    print("Mock GPS patch applied OK.")

    if a.wmaps:
        import gmapswv
        gmapswv.apply(a.cgeo, a.wmaps)


if __name__ == "__main__":
    main()
