# c:geo + Mock GPS + Google Maps WebView (auto-patched)

GitHub Actions builds [c:geo](https://github.com/cgeo/cgeo) with two patches. Nothing is forked or vendored:
every build clones the upstream repos fresh and applies `patch/apply.py`.

1. **Mock GPS** - code from [warren-bank/Android-Mock-Location](https://github.com/warren-bank/Android-Mock-Location):
   fixed position, favourites, route between two points (OSRM / BRouter / straight line) simulated at your speed.
2. **Google Maps as restricted WebView** - code from [woheller69/maps](https://github.com/woheller69/maps) (GMapsWV):
   the Google Maps SDK (`play-services-maps`, API key, SDK map code) is removed from c:geo. Selecting **Google Maps (WebView)**
   in the map source menu - or "Google Maps directions" in a cache's navigation menu - opens a locked-down WebView
   (allow-list of Google domains, trackers blocked, no DOM storage, cookies/cache/history wiped when opened and closed).
   Search, place details, street view etc. work like on google.com/maps.

## Setup (once)
```
./setup.sh            # needs the GitHub CLI (gh); creates the repo and pushes it
```
APK: `https://github.com/<you>/cgeo-mockgps/releases/latest/download/cgeo-mockgps.apk`

## Automatic updates
Every 6 hours a workflow looks up the latest **release tags** of
`cgeo/cgeo` (`market_*`), `woheller69/maps` (`V*`) and `Android-Mock-Location` (`service/v*`).
A new tag (or a change in `patch/`) triggers a rebuild and a new GitHub release (last 5 kept); otherwise the run ends in seconds.
If an upstream change breaks the patch, the build fails and the workflow opens an issue instead of publishing a half-patched app.
Manual run (Actions > Build patched c:geo > Run workflow): tick *force*, or give `cgeo_ref` (e.g. `master`) to build a c:geo branch.

## Using it
* Mock GPS: c:geo main menu > Mock GPS; Developer options > **Select mock location app > c:geo (mockgps)**.
* Google Maps: map screen > map source menu > **Google Maps (WebView)**. It opens centered on the current c:geo map position;
  Back goes back inside the page, then closes. Copying a Google place link inside the page turns the clipboard into a `geo:` URI
  (a GMapsWV feature) that c:geo can paste as coordinates.
* The APK installs next to the normal c:geo (package `cgeo.geocaching.mockgps`).

## Limits / notes
* The Google WebView is a separate screen: c:geo's cache/waypoint overlays are not drawn on it, and Google Satellite/Terrain sources are gone
  (the web page has its own layer switcher). A stored "Google Maps" source setting falls back to plain OpenStreetMap.
* Signing: the first run generates a key and stores it on a `signing` branch so updates install over each other
  (or add a repo secret `KEYSTORE_B64`).
* Licenses: c:geo Apache-2.0, Android-Mock-Location GPL-2.0, woheller69/maps AGPL-3.0. Fine for personal use; do not redistribute the APK
  without checking the license mix.
* OSRM mode sends start/destination to router.project-osrm.org (public demo server, light personal use only).
