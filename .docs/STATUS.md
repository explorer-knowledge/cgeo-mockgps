# Status and history

_Last updated: 2026-11-02_

## Current state (2026-11-02)
- HEAD `5a0d564`, release `cgeo-market_20261018-pb28ee17`: **13,981,023 B (13.3 MiB)**, 9,472 classes, signed with the new private key (SHA-256 `81:EF:FD:8A:...`).
- adb test on RZ8M50CAKCT: the old app was uninstalled (key change, data wiped).
  - install OK, launch 1.0 s, PSS 68-72 MB, Java heap 4 MB
  - no crash and no R8 errors (ClassNotFound, NoSuchMethod, ...)
  - The missing Bergamot `.so` is caught and logged ("Could not initialize Bergamot"), as intended.
- Not yet tested on the phone by hand: the mock menu items, a drive, the speed dialog, Stop and real-position recovery, and the navigation-chooser entries.

## Next steps
1. Download the latest release APK (in the background) and check its size. Expected about 14 MB.
2. Run the simple adb checks in [TESTING.md](TESTING.md).
3. On the phone, check by hand:
   - long-press on the map shows the 3 or 4 mock items, and "Mock location here" moves the position marker
   - drive along a route
   - Stop, from the menu and from the notification
   - offline translation does not crash (it should be unavailable)
4. If the fused location still jumps back to the real position, try c:geo Settings > System > disable "Use Google Play Services".

## History (commits)
| Commit | What |
|---|---|
| b616182 | Initial patch with a separate Mock GPS screen, favourites and OSRM routing. Built as debug. |
| 44a1722 | `onPause` changed to `public` (fixed a compile error). |
| b88f6bc | Release build instead of debug: R8, no LeakCanary (the cause of the lag, leaks and size), arm64 only. 22 MB. |
| 29b5ee0 | Compress native libraries in release. |
| cde2138 | Unique FileProvider authority, fixing `INSTALL_FAILED_CONFLICTING_PROVIDER`. 18.2 MB. |
| 982061b | Removed the screen, favourites and `RouteBuilder`; added `MockGps` (map menus); rewrote the service; excluded Bergamot. |

## Known limits and risks
- BRouter needs offline tiles and a distance under its threshold (default 10 km). Otherwise the route falls back to OSRM, a public demo server for light personal use.
- The cache and waypoint menu entries only appear when c:geo's long-tap and route-menu settings are on.
- Removing Bergamot means c:geo has no offline translation.
- arm64 only, so it won't install on 32-bit-only phones.
- Licenses: c:geo is Apache-2.0 and Mock-Location is GPL-2.0. Fine for personal use; don't redistribute without checking.
