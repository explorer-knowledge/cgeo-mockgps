#!/usr/bin/env python3
"""
Applies the Mock-GPS patch to a fresh c:geo checkout.

  python3 patch/apply.py --cgeo <cgeo checkout> --mock <warren-bank/Android-Mock-Location checkout>

* copies MockLocationProvider.java verbatim from warren-bank's repo (package line rewritten)
* adds our own new classes from patch/files
* makes a few small anchored edits to existing c:geo files (manifest, map long-press menus, build.gradle)

Every edit is idempotent and FAILS LOUDLY if its anchor disappeared upstream, so a broken patch
never silently produces a build without the feature.
"""
import argparse
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
FILES = HERE / "files"


def die(msg):
    print(f"PATCH FAILED: {msg}", file=sys.stderr)
    sys.exit(1)


def edit(path, anchor, replacement, marker, count_must_be=1):
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"  already patched: {path.name} ({marker})")
        return
    found = text.count(anchor)
    if found != count_must_be:
        die(f"anchor found {found}x (expected {count_must_be}x) in {path}:\n---\n{anchor}\n---")
    path.write_text(text.replace(anchor, replacement), encoding="utf-8")
    print(f"  patched: {path.name} ({marker})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cgeo", required=True, type=pathlib.Path)
    ap.add_argument("--mock", required=True, type=pathlib.Path)
    ap.add_argument("--wmaps", type=pathlib.Path, help="woheller69/maps checkout (Google Maps WebView patch)")
    a = ap.parse_args()

    main_dir = a.cgeo / "main"
    java_dir = main_dir / "src/main/java/cgeo/geocaching"
    res_dir = main_dir / "src/main/res"
    map_utils = java_dir / "maps/MapUtils.java"
    translate = java_dir / "utils/offlinetranslate/TranslateAccessor.java"
    navi_dir = java_dir / "apps/navi"
    navi_prefs = res_dir / "xml/preferences_navigation_navigation.xml"
    for p in (main_dir / "AndroidManifest.xml", main_dir / "build.gradle", map_utils, translate,
              navi_dir / "NavigationAppFactory.java", navi_prefs):
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
        "// Only the package line was changed by the patch script.\n"
    )
    (target / "MockLocationProvider.java").write_text(
        src.replace(pkg, "package cgeo.geocaching.mockgps;\n\n" + header), encoding="utf-8")
    print("  copied MockLocationProvider.java from", hits[0])

    # 2. our own new files ---------------------------------------------------
    for f in sorted((FILES / "java").glob("*.java")):
        shutil.copy(f, target / f.name)
        print("  added", f.name)
    for f in sorted((FILES / "navi").glob("*.java")):  # must live in apps.navi: AbstractPointNavigationApp is package-private
        shutil.copy(f, navi_dir / f.name)
        print("  added apps/navi/" + f.name)
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
         '        <service\n'
         '            android:name=".mockgps.MockGpsService"\n'
         '            android:exported="false"\n'
         '            android:foregroundServiceType="location" />\n'
         "    </application>",
         ".mockgps.MockGpsService")

    # mock entries in the map long-press menus (empty map spot, and caches/waypoints = c:geo's own "favourites")
    edit(map_utils,
         "        return SimplePopupMenu.of(activity)\n                .setMenuContent(R.menu.map_longclick)",
         "        return cgeo.geocaching.mockgps.MockGps.addMenuItems(activity, longClickGeopoint, SimplePopupMenu.of(activity))\n"
         "                .setMenuContent(R.menu.map_longclick)",
         "MockGps.addMenuItems(activity, longClickGeopoint")
    edit(map_utils,
         "R.drawable.elevation);\n        }\n    }\n",
         "R.drawable.elevation);\n        }\n"
         "        cgeo.geocaching.mockgps.MockGps.addMenuItems(activity, routeItem.getPoint(), menu);\n    }\n",
         "MockGps.addMenuItems(activity, routeItem.getPoint()")

    # mock entries in c:geo's navigation chooser: reaches every stored cache/waypoint (details, list, compass)
    edit(navi_dir / "NavigationAppFactory.java",
         "CRUISER(new CruiserNavigationApp(), 28, R.string.pref_navigation_menu_cruiser);",
         "CRUISER(new CruiserNavigationApp(), 28, R.string.pref_navigation_menu_cruiser),\n"
         "        MOCK_HERE(new MockNavigationApp(false), 90, R.string.pref_navigation_menu_mock_here),\n"
         "        MOCK_DRIVE(new MockNavigationApp(true), 91, R.string.pref_navigation_menu_mock_drive);",
         "MOCK_HERE(new MockNavigationApp(false)")
    edit(navi_prefs,
         "</PreferenceScreen>",
         '    <CheckBoxPreference\n'
         '        android:defaultValue="true"\n'
         '        android:enabled="false"\n'
         '        android:key="@string/pref_navigation_menu_mock_here"\n'
         '        android:title="@string/mockgps_here"\n'
         '        app:iconSpaceReserved="false" />\n'
         '    <CheckBoxPreference\n'
         '        android:defaultValue="true"\n'
         '        android:enabled="false"\n'
         '        android:key="@string/pref_navigation_menu_mock_drive"\n'
         '        android:title="@string/mockgps_drive_here"\n'
         '        app:iconSpaceReserved="false" />\n'
         "</PreferenceScreen>",
         "pref_navigation_menu_mock_here")

    # CI builds the release type (R8 + resource shrinking, no LeakCanary); give it the same app id and arm64 only
    edit(main_dir / "build.gradle",
         "        release {\n            signingConfig = signingConfigs.release\n            manifestPlaceholders",
         "        release {\n            // mockgps-release\n            applicationIdSuffix = \".mockgps\"\n"
         "            ndk { abiFilters \"arm64-v8a\" }\n"
         "            signingConfig = signingConfigs.release\n            manifestPlaceholders",
         "mockgps-release")

    # c:geo release branches still use proguard-android.txt (-dontoptimize); master switched to the optimize config
    # in 92bbb18e. Without it the APK has ~60% more classes (+1.3 MB). No-op on master (marker already there).
    edit(main_dir / "build.gradle",
         "        proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-project.txt'",
         "        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-project.txt'",
         "proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')")

    # upstream stores .so uncompressed in release; compress them like debug/nightly do, and drop the
    # 9 MB Bergamot offline translator (-4 MB APK, same content as the official 14 MB foss APK)
    edit(main_dir / "build.gradle",
         'androidComponentsExt.onVariants(androidComponentsExt.selector().withBuildType("nightly")) {',
         'androidComponentsExt.onVariants(androidComponentsExt.selector().withBuildType("release")) { // mockgps-compress\n'
         '    packaging.jniLibs.useLegacyPackaging.set(true)\n'
         '    packaging.jniLibs.excludes.add("**/libbergamot-sys.so")\n}\n'
         'androidComponentsExt.onVariants(androidComponentsExt.selector().withBuildType("nightly")) {',
         "mockgps-compress")

    # release uses a fixed FileProvider authority that the official c:geo already owns -> INSTALL_FAILED_CONFLICTING_PROVIDER
    edit(res_dir / "values/strings_not_translatable.xml",
         'name="file_provider_authority">org.cgeo.fileprovider<',
         'name="file_provider_authority">org.cgeo.mockgps.fileprovider<',
         "org.cgeo.mockgps.fileprovider")

    # without the .so, NativeLib throws UnsatisfiedLinkError (an Error); catch it so c:geo falls back to "no translator"
    edit(translate,
         '} catch (final Exception e) {\n                Log.e("TranslateAccessor: Could not initialize Bergamot", e);',
         '} catch (final Throwable e) {\n                Log.e("TranslateAccessor: Could not initialize Bergamot", e);',
         "catch (final Throwable e) {\n                Log.e(\"TranslateAccessor: Could not initialize Bergamot\"")

    print("Patch applied OK.")

    if a.wmaps:
        import gmapswv
        gmapswv.apply(a.cgeo, a.wmaps)


if __name__ == "__main__":
    main()
