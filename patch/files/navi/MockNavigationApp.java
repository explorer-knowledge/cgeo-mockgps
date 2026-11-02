package cgeo.geocaching.apps.navi;

import cgeo.geocaching.R;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.mockgps.MockGps;
import cgeo.geocaching.mockgps.MockGpsService;

import android.content.Context;

import androidx.annotation.NonNull;

/** "Mock location here" / "Mock drive here" in c:geo's navigation chooser (cache details, cache list, waypoints, compass) */
class MockNavigationApp extends AbstractPointNavigationApp {

    private final boolean drive;

    MockNavigationApp(final boolean drive) {
        super(drive ? R.string.mockgps_drive_here : R.string.mockgps_here, null);
        this.drive = drive;
    }

    @Override
    public boolean isInstalled() {
        return true;
    }

    @Override
    public boolean isUsableAsDefaultNavigationApp() {
        return false;
    }

    @Override
    public void navigate(@NonNull final Context context, @NonNull final Geopoint coords) {
        if (drive) {
            MockGps.drive(context.getApplicationContext(), coords);
        } else {
            MockGpsService.startFixed(context.getApplicationContext(), coords);
        }
    }
}
