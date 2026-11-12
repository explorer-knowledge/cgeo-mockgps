"""
Patch 2: remove the Google Maps SDK from c:geo and show Google Maps as a restricted WebView instead.

* the activity is generated from woheller69/maps (MainActivity.java: allow/deny-lists, cache + cookie wipe, ...)
  by a handful of asserted text substitutions, so upstream improvements flow in automatically
* selecting "Google Maps" in the map source menu opens that WebView (at the current map position)
* the "Google Maps directions" navigation entry opens it too
* play-services-maps, the API key and all SDK-based map code are deleted; the build fails loudly if any SDK use remains
"""
import pathlib
import re
import shutil
import xml.etree.ElementTree as ET

from patchlib import cut_between, delete, die, edit

HERE = pathlib.Path(__file__).resolve().parent
FILES = HERE / "files" / "gmapswv"

STRING_NAMES = ["title_location_permission", "text_location_permission", "error_no_gps",
                "no_app_installed", "title_open_link", "text_warning_link", "text_open_link"]


def must_replace(text, old, new):
    if old not in text:
        die(f"upstream woheller69/maps MainActivity.java changed, expected text missing: {old!r}")
    return text.replace(old, new)


def generate_activity(wmaps, target):
    up = wmaps / "app/src/main/java/us/spotco/maps/MainActivity.java"
    if not up.exists():
        die(f"{up} not found - woheller69/maps was restructured")
    t = up.read_text(encoding="utf-8")
    t = must_replace(t, "package us.spotco.maps;", "package cgeo.geocaching.gmapswv;\n\nimport cgeo.geocaching.R;")
    t = re.sub(r"^[^\n]*(FreeDroidWarn|GithubStar)[^\n]*\n", "", t, flags=re.M)
    t = must_replace(t, "requestWindowFeature(Window.FEATURE_NO_TITLE);", "")
    t = must_replace(t, "R.layout.activity_main", "R.layout.gmapswv_activity")
    t = must_replace(t, "R.id.mapsWebView", "R.id.gmapswv_webview")
    t = re.sub(r"R\.string\.(" + "|".join(STRING_NAMES) + r")\b", r"R.string.gmapswv_\1", t)
    t = must_replace(t, "R.drawable.ic_warning", "android.R.drawable.ic_dialog_alert")
    t = must_replace(t, "public class MainActivity extends Activity", "public class GoogleMapsWebViewActivity extends Activity")
    for bad in ("FreeDroidWarn", "GithubStar", "BuildConfig", "us.spotco", "MainActivity"):
        if bad in t:
            die(f"generated activity still contains {bad!r} - upstream changed")
    leftovers = set(re.findall(r"(?<![\w.])R\.(?:string|drawable|layout|id)\.\w+", t)) - {"R.layout.gmapswv_activity", "R.id.gmapswv_webview"}
    leftovers = {x for x in leftovers if not x.startswith("R.string.gmapswv_")}
    if leftovers:
        die(f"generated activity references unknown resources: {sorted(leftovers)}")
    header = "// Derived from https://github.com/woheller69/maps (GMapsWV, AGPL-3.0) by patch/gmapswv.py\n"
    (target / "GoogleMapsWebViewActivity.java").write_text(header + t, encoding="utf-8")
    print("  generated GoogleMapsWebViewActivity.java from upstream MainActivity.java")


def generate_strings(wmaps, res_dir):
    src = wmaps / "app/src/main/res/values/strings.xml"
    if not src.exists():
        die(f"{src} not found")
    found = {s.get("name"): s.text for s in ET.parse(src).getroot().iter("string")}
    missing = [n for n in STRING_NAMES if n not in found]
    if missing:
        die(f"strings missing upstream: {missing}")
    root = ET.Element("resources")
    for n in STRING_NAMES:
        e = ET.SubElement(root, "string", {"name": "gmapswv_" + n, "translatable": "false"})
        e.text = found[n]
    ET.indent(root)
    (res_dir / "values/gmapswv_strings.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="unicode") + "\n", encoding="utf-8")
    print("  generated values/gmapswv_strings.xml")


def webkit_version(wmaps):
    m = re.search(r"androidx\.webkit:webkit:([0-9][^'\"\s]*)", (wmaps / "app/build.gradle").read_text(encoding="utf-8"))
    if not m:
        die("androidx.webkit dependency not found in woheller69/maps app/build.gradle")
    return m.group(1)


def verify_sdk_gone(main_dir):
    bad = []
    for p in (main_dir / "src/main").rglob("*"):
        if p.is_file() and p.suffix in (".java", ".kt", ".xml"):
            txt = p.read_text(encoding="utf-8", errors="ignore")
            if "com.google.android.gms.maps" in txt or "unifiedmap.googlemaps" in txt:
                bad.append(str(p.relative_to(main_dir)))
    gradle = (main_dir / "build.gradle").read_text(encoding="utf-8")
    if "play-services-maps" in gradle:
        bad.append("build.gradle (play-services-maps)")
    if bad:
        die("Google Maps SDK usage still present after patching (upstream added new users?):\n  " + "\n  ".join(bad))
    print("  verified: no Google Maps SDK usage left")


def apply(cgeo, wmaps):
    main_dir = cgeo / "main"
    java = main_dir / "src/main/java/cgeo/geocaching"
    res = main_dir / "src/main/res"
    um = java / "unifiedmap"

    # 1. new code ---------------------------------------------------------------
    target = java / "gmapswv"
    target.mkdir(exist_ok=True)
    generate_activity(wmaps, target)
    generate_strings(wmaps, res)
    shutil.copy(FILES / "GoogleMapsWebViewLauncher.java", target / "GoogleMapsWebViewLauncher.java")
    shutil.copy(FILES / "gmapswv_activity.xml", res / "layout/gmapswv_activity.xml")
    print("  added launcher + layout")

    # 2. purge the SDK-based map ---------------------------------------------------
    delete(um / "googlemaps")
    delete(main_dir / "src/test/java/cgeo/geocaching/unifiedmap/googlemaps")  # unit test of the deleted ScaleDrawer
    delete(um / "geoitemlayer/GoogleV2GeoItemLayer.java")
    delete(um / "tileproviders/AbstractGoogleTileProvider.java")
    delete(um / "tileproviders/GoogleSatelliteSource.java")
    delete(um / "tileproviders/GoogleTerrainSource.java")
    # the remaining "Google Maps" source is only a menu entry that opens the WebView (OSM underneath as safe fallback)
    shutil.copy(FILES / "GoogleMapSource.java", um / "tileproviders/GoogleMapSource.java")
    print("  replaced GoogleMapSource.java")

    tpf = um / "tileproviders/TileProviderFactory.java"
    edit(tpf,
         "        if (isGoogleMapsInstalled()) {\n"
         "            registerTileProvider(new GoogleMapSource());\n"
         "            registerTileProvider(new GoogleSatelliteSource());\n"
         "            registerTileProvider(new GoogleTerrainSource());\n"
         "        }\n",
         "        registerTileProvider(new GoogleMapSource()); // opens the restricted Google Maps WebView\n",
         marker="opens the restricted Google Maps WebView")
    edit(tpf,
         "\n                        || (ctp instanceof AbstractGoogleTileProvider && ctp.supportsThemes()));",
         ");",
         gone_ok=True)

    # the API-key / SDK availability gate is dead code now
    cut_between(tpf, "    private static boolean isGoogleMapsInstalled() {", "    private static void registerTileProvider(")

    toh = um / "overlays/TileOverlayLayerHelper.java"
    edit(toh, "import com.google.android.gms.maps.model.TileOverlayOptions;\n", "", gone_ok=True)
    edit(toh, "import com.google.android.gms.maps.model.UrlTileProvider;\n", "", gone_ok=True)
    cut_between(toh, "    /** returns a TileOverlayOptions for every enabled overlay (Google Maps variant) */",
                "    private static URL toUrl(")

    # 3. hooks ------------------------------------------------------------------------
    edit(um / "UnifiedMapActivity.java",
         "} else if (tileProviderLocal != null) {\n"
         "                item.setChecked(true);\n"
         "                Settings.setPreviousTileProvider(tileProvider);",
         "} else if (tileProviderLocal instanceof cgeo.geocaching.unifiedmap.tileproviders.GoogleMapSource) {\n"
         "                // Google Maps = restricted WebView in its own screen (gmapswv patch); the c:geo map stays as it is\n"
         "                cgeo.geocaching.gmapswv.GoogleMapsWebViewLauncher.open(this,\n"
         "                        mapFragment == null ? null : mapFragment.getCenter(),\n"
         "                        mapFragment == null ? 0 : mapFragment.getCurrentZoom());\n"
         "                return true;\n"
         "            } else if (tileProviderLocal != null) {\n"
         "                item.setChecked(true);\n"
         "                Settings.setPreviousTileProvider(tileProvider);",
         marker="GoogleMapsWebViewLauncher.open(this,")

    nav = java / "apps/navi/GoogleMapsDirectionApp.java"
    edit(nav, "import cgeo.geocaching.unifiedmap.googlemaps.GoogleMapsUtils;\n", "", gone_ok=True)
    edit(nav, "return GoogleMapsUtils.isGoogleMapsInstalled();", "return true;", gone_ok=True)
    edit(nav, "context.startActivity(new Intent(Intent.ACTION_VIEW, Uri",
         "context.startActivity(new Intent(context, cgeo.geocaching.gmapswv.GoogleMapsWebViewActivity.class).setData(Uri",
         marker="GoogleMapsWebViewActivity.class).setData(Uri")

    # 4. build + manifest -----------------------------------------------------------------
    edit(main_dir / "build.gradle",
         "    implementation 'com.google.android.gms:play-services-maps:20.0.0'\n",
         f"    implementation 'androidx.webkit:webkit:{webkit_version(wmaps)}' // Google Maps WebView (gmapswv patch)\n",
         marker="Google Maps WebView (gmapswv patch)")
    manifest = main_dir / "AndroidManifest.xml"
    edit(manifest,
         '        <meta-data\n'
         '            android:name="com.google.android.geo.API_KEY"\n'
         '            android:value="@string/maps_api2_key" />\n',
         "", gone_ok=True)
    edit(manifest,
         "    </application>",
         '        <!-- Google Maps WebView patch -->\n'
         '        <activity\n'
         '            android:name=".gmapswv.GoogleMapsWebViewActivity"\n'
         '            android:label="Google Maps"\n'
         '            android:exported="false"\n'
         '            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode"\n'
         '            android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />\n'
         "    </application>",
         marker=".gmapswv.GoogleMapsWebViewActivity")

    verify_sdk_gone(main_dir)
    print("Google Maps WebView patch applied OK.")
