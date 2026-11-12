# Project: c:geo + Mock GPS

Repo: https://github.com/explorer-knowledge/cgeo-mockgps (branch `main`)
Latest APK: https://github.com/explorer-knowledge/cgeo-mockgps/releases/latest/download/cgeo-mockgps.apk
Package: `cgeo.geocaching.mockgps`. It installs next to the official c:geo.

## Idea
Nothing is forked. Each CI run clones **cgeo/cgeo** at its **latest GitHub release tag** (for example `market_20261018`, not master) and **warren-bank/Android-Mock-Location** (`service`) fresh, applies `patch/apply.py`, builds, and publishes a GitHub release. A daily cron builds only when c:geo publishes a new release or the patch changes. The release tag is `cgeo-<cgeo release tag>-p<patch hash>`. Mock-Location changes alone never trigger a build. The last 5 releases are kept. If an anchor breaks, the patch fails loudly and the workflow opens an issue.

## Files
| Path | What |
|---|---|
| `patch/apply.py` | Copies `MockLocationProvider.java` from warren-bank (package line rewritten), copies `patch/files/java/*`, and makes anchored, idempotent edits to c:geo. `edit(path, anchor, replacement, marker)` dies unless the anchor is found exactly once. |
| `patch/files/java/MockGps.java` | Menu glue plus route building. `addMenuItems(activity, geopoint, menu)` adds: Mock location here / Mock route: start here / Mock drive here / Stop (Stop only while running). Route: c:geo BRouter, then OSRM online, then a straight line; it never throws. Speed: the "Mock speed" menu item (SharedPreferences `mockgps/speed_kmh`, also changes a running drive), else a default from the routing mode (car 50, bike 15, otherwise 5 km/h). |
| `patch/files/java/MockGpsService.java` | Foreground service (type location). Ticks once per second on the **main looper**: no thread, no locks. Pushes to GPS, network and fused (API 31+) test providers plus GMS `FusedLocationProviderClient` mock mode. Entry points: `startFixed`, `startTrip`, `setSpeed`, `stop`. On stop it pushes a fix stamped 10 min old with 5 km accuracy before removing the test providers. `GeoData.determineBestLocation` prefers any GPS fix under 30 s old, so a fresh mock fix would block the real position. A trip interpolates along the polyline and then holds at the destination. |
| `patch/files/navi/MockNavigationApp.java` | Copied into `cgeo.geocaching.apps.navi` (its base class is package-private). Two enum entries, `MOCK_HERE` (id 90) and `MOCK_DRIVE` (id 91), put mock in the "Navigate" chooser for every cache and waypoint. |
| `patch/files/res/values/mockgps_strings.xml` | App names and the two navigation preference keys. |
| `.github/workflows/build.yml` | The CI pipeline. Signing key comes from secret `KEYSTORE_B64` (set 2026-11-02, a new private key), else from the `signing` branch (the old public key, no longer used), else it is generated once and stored there. The private keystore lives only at `~/.android-keys/cgeo-mockgps.keystore` on the owner's PC (alias `androiddebugkey`, password `android`, RSA 4096, SHA-256 `81:EF:FD:8A:...:45:83`). Back it up: losing it means updates can no longer install over existing APKs. Writes `private.properties` and runs `./gradlew assembleBasicRelease`. |
| `setup.sh` | One-time repo creation (already done). Do not re-run. |

## Edits apply.py makes to c:geo
- `AndroidManifest.xml`: adds the `ACCESS_MOCK_LOCATION` permission and the `MockGpsService` entry. c:geo already declares the `FOREGROUND_SERVICE_LOCATION` and `POST_NOTIFICATIONS` permissions.
- `maps/MapUtils.java`:
  - `createMapLongClickPopupMenu` wraps `SimplePopupMenu.of(activity)` with `MockGps.addMenuItems(...)` (long-press on an empty spot).
  - The end of `addCacheWaypointLongClickPopupMenu` calls `MockGps.addMenuItems(activity, routeItem.getPoint(), menu)` (long-press on a cache or waypoint). This needs c:geo's long-tap and route-menu settings on; both default to on.
  - Caches, waypoints and user-defined caches act as the "favourites". There is no custom favourites store.
- `apps/navi/NavigationAppFactory.java` gets the `MOCK_HERE` and `MOCK_DRIVE` enum entries; `res/xml/preferences_navigation_navigation.xml` gets their CheckBoxPreferences.
- `build.gradle` (R8): `proguard-android.txt` becomes `proguard-android-optimize.txt` on release-branch bases. c:geo master has had it since 92bbb18e. Without it: 15,140 classes instead of 9,499, and +1.3 MB.
- `build.gradle`:
  - The release type gets `applicationIdSuffix ".mockgps"` and the `arm64-v8a` ABI filter.
  - Native libraries are compressed in release.
  - `libbergamot-sys.so` (offline translator, about 4 MB compressed) is excluded.
- `utils/offlinetranslate/TranslateAccessor.java`: `catch (Exception)` becomes `catch (Throwable)`, so a missing `.so` falls back to "no translator" instead of crashing.
- `res/values/strings_not_translatable.xml`: the FileProvider authority becomes `org.cgeo.mockgps.fileprovider`.

## Size facts (from research, 2026-11-02)
- Official 14 MB = `cgeo-foss-release_202060805.apk`, which has no Bergamot. Official market APKs are 51.7 MB universal.
- Our APK before dropping Bergamot was 18.2 MB: dex 5.2 MB, `resources.arsc` 5.3 MB (uncompressed, 33 languages), lib 4.2 MB, res 2.3 MB, assets 0.7 MB.
- Further cuts we did not make:
  - Fewer UI languages: −3 to −4 MB. Edit the `locales` list in c:geo's `build.gradle`.
  - Remove `-dontobfuscate`: about −0.5 to −1 MB, but there is a reflection risk.
- Mock-Location is about 1 MB because it is a tiny app with no maps or libraries. c:geo can't get near that.

## Google Maps inside the app
- c:geo hides the Google map types unless `maps_api2_key` is at least 30 characters long (`TileProviderFactory.isGoogleMapsInstalled`).
- CI writes `maps.api2.key` from repo secret **`MAPS_API_KEY`** into `private.properties`; c:geo's build turns it into `keys.xml`.
- The official c:geo key only works for their package and signature, so we need our own key:
  - Google Cloud > "Maps SDK for Android"
  - Restrict the key to Android apps: package `cgeo.geocaching.mockgps`, SHA-1 `E5:69:43:09:D6:4A:ED:8D:F1:E8:76:84:D1:1F:D0:72:DE:3B:77:37`
- Adding or changing the secret does not change the release tag, so start a **forced** run afterwards.

## Phone setup
- Developer options > Select mock location app > **c:geo**.
- c:geo needs its location permission. On API 34+, the foreground service fails without it and shows a toast.
