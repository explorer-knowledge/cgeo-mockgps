# c:geo + Mock GPS (auto-patched)

GitHub Actions builds [c:geo](https://github.com/cgeo/cgeo) with a Mock GPS screen added, using code from
[warren-bank/Android-Mock-Location](https://github.com/warren-bank/Android-Mock-Location). Nothing is forked or
vendored: every build clones both upstream repos fresh and applies `patch/apply.py`.

## Setup (once)
```
./setup.sh            # needs the GitHub CLI (gh); creates the repo and pushes it
```
The push starts the first build. APK: `https://github.com/<you>/cgeo-mockgps/releases/latest/download/cgeo-mockgps.apk`

## Updates
A daily workflow checks the heads of cgeo/cgeo (`master`) and Android-Mock-Location (`service`). If either or the
patch changed, it rebuilds and publishes a new release (last 5 kept). If upstream changes break an anchor in
`apply.py`, the build fails and the workflow opens an issue instead of publishing a half-patched app.

## What you get (inside the c:geo map, no separate screen)
Long-press an empty spot on the map, or a cache / waypoint (needs Settings > Map > long-tap + route menu enabled):
* **Mock location here** - fixes your position there
* **Mock route: start here** - remembers a start point
* **Mock drive here** - drives from the remembered start (or your current position) to this point, then holds it.
  Road route from c:geo's BRouter (offline tiles), else OSRM online, else straight line. Speed follows c:geo's
  routing mode: car 50, bike 15, walk 5 km/h.
* **Stop mock location** - also available as Stop in the notification

Your own caches, waypoints and user-defined caches serve as favourites.
On the phone: Developer options > **Select mock location app > c:geo**. The APK installs next to the normal
c:geo (package `cgeo.geocaching.mockgps`). Release build (R8), arm64 only, without the Bergamot offline translator.

## Notes
* Signing: the first run generates a key and stores it on a `signing` branch so updates install over each other.
  If you prefer, add a repo secret `KEYSTORE_B64` (base64 of your keystore) and it is used instead.
* Licenses: c:geo is Apache-2.0, Android-Mock-Location is GPL-2.0. Fine for personal use; check before redistributing the APK.
* OSRM mode sends your start/destination to router.project-osrm.org (public demo server, light personal use only).
