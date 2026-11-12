# Agent rules (read first)

Rules the owner gave across chats. They are not optional.

## Hard rules
1. **No local Android builds or checks.** Never run `gradle`, `./gradlew`, `javac`, `lint`, `aapt`, `sdkmanager` or any APK build or check on the PC. All builds run on **GitHub Actions** (`.github/workflows/build.yml`).
2. **Testing = adb only, and keep it simple.** Download the APK from the latest GitHub release, then install, launch, and check `dumpsys meminfo`, the crash log and the APK size. No complex test suites. See [TESTING.md](TESTING.md).
3. **Commit or push only when the owner asks.** Pushing to `main` (paths `patch/**` or the workflow file) starts a CI build.
4. Use the **ponytail** skill (`/ponytail:ponytail`): smallest complete change, no extras, and every reply ends with what was skipped or what is at risk.
5. Commit message trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or the model in use).

## Allowed locally
- Reading code, grep, and `curl`/`gh api` to GitHub.
- `git clone` of upstream into `/tmp` for reading.
- Running `patch/apply.py` on a **throwaway copy** of c:geo to check anchors. It is a Python text patch, not a build:
  ```
  git clone --depth 1 https://github.com/cgeo/cgeo.git /tmp/cg
  git clone --depth 1 -b service https://github.com/warren-bank/Android-Mock-Location.git /tmp/mock
  rm -rf /tmp/cgtest && cp -r /tmp/cg /tmp/cgtest
  python3 -I patch/apply.py --cgeo /tmp/cgtest --mock /tmp/mock   # run twice: second run must say "already patched" everywhere
  ```
- `gh run list`, `gh run view --log-failed`, `gh release download`.

## Owner's goals (priority order)
1. App must not lag or leak (that is why it ships a **release** build: R8 on, no LeakCanary).
2. APK size close to official c:geo (about **14 MB**).
3. Mock location must feel like **part of c:geo**: map long-press menus, no separate screen.

## Gotchas learned the hard way
- `gh auth` needs the `workflow` scope to push `.github/workflows/*`.
- Overrides must match base visibility (the `onPause` must be `public` in c:geo's base activity).
- The release build had a fixed FileProvider authority, which caused `INSTALL_FAILED_CONFLICTING_PROVIDER` next to official c:geo. The patch renames it.
- `gh release download` is slow on the owner's connection. Run it in the background.
