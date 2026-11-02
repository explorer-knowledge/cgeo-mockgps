package cgeo.geocaching.mockgps;

import cgeo.geocaching.R;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.maps.routing.Routing;
import cgeo.geocaching.maps.routing.RoutingMode;
import cgeo.geocaching.network.Network;
import cgeo.geocaching.sensors.GeoData;
import cgeo.geocaching.sensors.LocationDataProvider;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.ui.ViewUtils;
import cgeo.geocaching.ui.dialog.SimplePopupMenu;
import cgeo.geocaching.utils.AndroidRxUtils;
import cgeo.geocaching.utils.Log;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/** Adds the mock-location entries to c:geo's map long-press menus and builds the simulated drive. */
public final class MockGps {

    /** start point picked via "Mock route: start here"; null = start from the current position */
    private static volatile Geopoint routeStart;

    private MockGps() {
    }

    /** called from MapUtils for long-press on empty map and on caches/waypoints */
    public static SimplePopupMenu addMenuItems(final Activity activity, final Geopoint p, final SimplePopupMenu menu) {
        final Context ctx = activity.getApplicationContext(); // menu lambdas must not keep the activity alive
        menu.addMenuItem("Mock location here", R.drawable.ic_menu_mylocation, i -> MockGpsService.startFixed(ctx, p));
        menu.addMenuItem("Mock route: start here", R.drawable.ic_menu_route, i -> {
            routeStart = p;
            ViewUtils.showShortToast(ctx, "Mock route start set - long-press the destination");
        });
        menu.addMenuItem(routeStart == null ? "Mock drive here" : "Mock drive here from route start", R.drawable.ic_menu_navigate, i -> drive(ctx, p));
        if (MockGpsService.running) {
            menu.addMenuItem("Stop mock location", R.drawable.ic_menu_mylocation_off, i -> MockGpsService.stop(ctx));
        }
        return menu;
    }

    private static void drive(final Context ctx, final Geopoint dest) {
        final GeoData geo = LocationDataProvider.getInstance().currentGeo();
        if (routeStart == null && geo == GeoData.DUMMY_LOCATION) { // no fix yet: dummy sits at Paris / last map centre
            ViewUtils.showShortToast(ctx, "No current position - set a mock route start first");
            return;
        }
        final Geopoint start = routeStart != null ? routeStart : geo.getCoords();
        routeStart = null;
        final RoutingMode mode = Settings.getRoutingMode();
        final double kmh = mode == RoutingMode.CAR ? 50 : mode == RoutingMode.BIKE ? 15 : 5;
        ViewUtils.showShortToast(ctx, "Building mock route...");
        AndroidRxUtils.andThenOnUi(AndroidRxUtils.networkScheduler,
                () -> route(mode, start, dest),
                path -> MockGpsService.startTrip(ctx, path, kmh));
    }

    /** flattened lat0, lon0, lat1, lon1, ...; never throws, falls back to a straight line */
    static double[] route(final RoutingMode mode, final Geopoint s, final Geopoint d) {
        if (mode != RoutingMode.STRAIGHT && mode != RoutingMode.OFF) {
            try {
                final double[] brouter = brouter(s, d);
                return brouter != null ? brouter : osrm(s, d);
            } catch (final Exception e) {
                Log.w("MockGps: road route failed, using straight line", e);
            }
        }
        return new double[]{s.getLatitude(), s.getLongitude(), d.getLatitude(), d.getLongitude()};
    }

    /** c:geo's own BRouter; null if it only produced its straight-line fallback (no tiles, > threshold, ...) */
    private static double[] brouter(final Geopoint s, final Geopoint d) {
        Routing.connect();
        try {
            // shortcut: 5 s poll, connect callback fires before the binder is set; upgrade if BRouter is often slower
            for (int i = 0; i < 50 && !Routing.isConnected(); i++) {
                SystemClock.sleep(100);
            }
            final Geopoint[] t = Routing.getTrackNoCaching(s, d, null, null);
            if (t.length <= 2) {
                return null;
            }
            final double[] out = new double[t.length * 2];
            for (int i = 0; i < t.length; i++) {
                out[2 * i] = t[i].getLatitude();
                out[2 * i + 1] = t[i].getLongitude();
            }
            return out;
        } finally {
            Routing.disconnect(null);
        }
    }

    /** public OSRM demo server (driving profile, light personal use only) */
    private static double[] osrm(final Geopoint s, final Geopoint d) {
        final String url = String.format(Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                s.getLongitude(), s.getLatitude(), d.getLongitude(), d.getLatitude());
        final JsonNode root = Network.requestJSON(url, null).blockingGet();
        final JsonNode c = root.path("routes").path(0).path("geometry").path("coordinates");
        if (!"Ok".equals(root.path("code").asText()) || c.size() < 2) {
            throw new IllegalStateException("OSRM: " + root.path("code").asText() + " " + root.path("message").asText());
        }
        final double[] out = new double[c.size() * 2];
        for (int i = 0; i < c.size(); i++) {
            out[2 * i] = c.get(i).get(1).asDouble();
            out[2 * i + 1] = c.get(i).get(0).asDouble();
        }
        return out;
    }
}
