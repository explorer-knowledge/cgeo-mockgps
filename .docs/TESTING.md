# Simple adb test

Device: `RZ8M50CAKCT` (the owner's phone, connected over USB). Build on CI first, then:

```bash
# 1. check the build
gh run list --limit 3
gh run view --log-failed | tail -60        # only if it failed; read the "error:" lines

# 2. download (slow connection: run in background)
mkdir -p /tmp/apk && cd /tmp/apk && rm -f *.apk
gh release download -R explorer-knowledge/cgeo-mockgps --pattern '*.apk'
ls -l cgeo-mockgps.apk && unzip -tq cgeo-mockgps.apk

# 3. install + launch + memory + crashes
P=cgeo.geocaching.mockgps
adb install -r cgeo-mockgps.apk
adb shell am start -W -n $P/cgeo.geocaching.MainActivity | grep -E "Status|TotalTime"
adb shell dumpsys meminfo $P | grep -E "TOTAL PSS|Java Heap|Native Heap|Activities"
adb logcat -d -b crash | tail -20

# 4. while mocking (after selecting it on the phone)
adb shell dumpsys location | grep -iA3 "test\|mock"
```

Notes:
- The mock items live in the c:geo map's long-press menu. Use them on the phone; adb can't reach them simply.
- `INSTALL_FAILED_*` errors: read the reason. It is usually a provider, permission or signature conflict with the installed official c:geo.
