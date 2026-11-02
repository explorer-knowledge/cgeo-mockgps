# c:geo + Mock GPS (auto-patched)

I made this because I needed it. I kept switching between two separate apps: [c:geo](https://github.com/cgeo/cgeo)
for geocaching and maps, and [Android-Mock-Location](https://github.com/warren-bank/Android-Mock-Location) for
faking my position. Both are great at what they do, so I wondered why not combine them. This project builds c:geo
with mock location built right into its map and navigation menus.It is all done by claude.

All the real work belongs to the creators of those two apps; this repo only glues them together (see
[Credits](#credits)). Nothing is forked or vendored: GitHub Actions clones both upstream repos fresh, applies
`patch/apply.py`, and builds the APK.

## Setup (once)
```
./setup.sh            # needs the GitHub CLI (gh); creates the repo and pushes it
```
The push starts the first build. APK: `https://github.com/<you>/cgeo-mockgps/releases/latest/download/cgeo-mockgps.apk`

## Updates
A daily workflow checks for a new **c:geo release** (GitHub releases of cgeo/cgeo) and only builds when there is one,
or when this repo's patch changes. Ordinary commits to c:geo do not trigger a build. Android-Mock-Location's
`service` branch is taken as it is at build time. Each build publishes a new release (last 5 kept). If upstream changes break an anchor in
`apply.py`, the build fails and the workflow opens an issue instead of publishing a half-patched app.

## What you get (inside the c:geo map, no separate screen)
Long-press an empty spot on the map, or a cache / waypoint (needs Settings > Map > long-tap + route menu enabled):
* **Mock location here** - fixes your position there
* **Mock route: start here** - remembers a start point
* **Mock drive here** - drives from the remembered start (or your current position) to this point, then holds it.
  Road route from c:geo's BRouter (offline tiles), else OSRM online, else straight line. Default speed follows c:geo's
  routing mode (car 50, bike 15, walk 5 km/h) until you set one.
* **Mock speed: N km/h** - set the drive speed (saved; also changes a drive that is running)
* **Stop mock location** - also available as Stop in the notification

The navigation chooser ("Navigate" on cache details, cache list, waypoints, compass) also lists
**Mock location here** and **Mock drive here** for any stored cache or waypoint (hide them under Settings > Navigation).
Stopping sends one stale, inaccurate final fix so c:geo and Android drop the mock position immediately.

Your own caches, waypoints and user-defined caches serve as favourites.
On the phone: Developer options > **Select mock location app > c:geo**. The APK installs next to the normal
c:geo (package `cgeo.geocaching.mockgps`). Release build (R8), arm64 only, without the Bergamot offline translator.

## Notes
* Signing: the first run generates a key and stores it on a `signing` branch so updates install over each other.
  If you prefer, add a repo secret `KEYSTORE_B64` (base64 of your keystore) and it is used instead.
* Licenses: c:geo is Apache-2.0, Android-Mock-Location is GPL-2.0. Fine for personal use; check before redistributing the APK.
* OSRM mode sends your start/destination to router.project-osrm.org (public demo server, light personal use only).

## Credits
Huge thanks to the people who actually built these apps:
* **c:geo**: https://github.com/cgeo/cgeo. The c:geo team and contributors (Apache-2.0).
* **FakeTraveler**: https://github.com/mcastillof/FakeTraveler. mcastillof. The original author of the
  `MockLocationProvider` class.
* **Android-Mock-Location**: https://github.com/warren-bank/Android-Mock-Location. Warren Bank (GPL-2.0).
  His copy of `MockLocationProvider` is the one this project downloads and includes unchanged apart from the package line.

If you like the result, please support and star the original projects.
