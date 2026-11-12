package cgeo.geocaching.mockgps;

import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.playservices.GooglePlayServices;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.util.Locale;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

/**
 * Foreground service that feeds fake GPS, network and fused locations to Android's test providers.
 * Modes: fixed position, or a trip along a polyline at constant speed (then holds at the destination).
 * Everything runs on the main looper: one cheap push per second, no thread, no locking.
 * Idea and provider handling taken from warren-bank/Android-Mock-Location (GPL-2.0).
 */
public class MockGpsService extends Service {

    private static final String ACTION_FIXED = "cgeo.geocaching.mockgps.FIXED";
    private static final String ACTION_TRIP = "cgeo.geocaching.mockgps.TRIP";
    private static final String ACTION_STOP = "cgeo.geocaching.mockgps.STOP";
    private static final String ACTION_SPEED = "cgeo.geocaching.mockgps.SPEED";
    private static final String EXTRA_LAT = "lat";
    private static final String EXTRA_LON = "lon";
    private static final String EXTRA_SPEED_KMH = "speed_kmh";
    private static final String CHANNEL_ID = "mockgps";
    private static final int NOTIFICATION_ID = 0x4d47;
    private static final long TICK_MS = 1000;

    /** read by the map menu */
    public static volatile boolean running = false;
    /** polyline handed over in-process (avoids Binder size limits) right before ACTION_TRIP */
    private static volatile double[] pendingPath;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private MockLocationProvider gps;
    private MockLocationProvider net;
    private MockLocationProvider fused;
    private FusedLocationProviderClient gms;

    private double lat;
    private double lon;
    private float bearing = 0f;
    private float speedMps = 0f;

    private boolean tripMode = false;
    private double[] path;
    private double[] cumMeters;
    private int seg;
    private double tripSpeedMps;
    private long tripStartMs;
    /** metres already driven at tripStartMs (non-zero after a speed change) */
    private double tripBaseMeters;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (final Exception e) {
                fail("Mock location error: " + e.getMessage());
                return;
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------ entry points

    public static void startFixed(final Context ctx, final Geopoint p) {
        start(ctx, new Intent(ctx, MockGpsService.class).setAction(ACTION_FIXED)
                .putExtra(EXTRA_LAT, p.getLatitude()).putExtra(EXTRA_LON, p.getLongitude()));
    }

    public static void startTrip(final Context ctx, final double[] path, final double kmh) {
        pendingPath = path;
        start(ctx, new Intent(ctx, MockGpsService.class).setAction(ACTION_TRIP).putExtra(EXTRA_SPEED_KMH, kmh));
    }

    public static void setSpeed(final Context ctx, final double kmh) {
        ctx.startService(new Intent(ctx, MockGpsService.class).setAction(ACTION_SPEED).putExtra(EXTRA_SPEED_KMH, kmh));
    }

    public static void stop(final Context ctx) {
        ctx.startService(new Intent(ctx, MockGpsService.class).setAction(ACTION_STOP));
    }

    private static void start(final Context ctx, final Intent intent) {
        try {
            ctx.startForegroundService(intent);
        } catch (final RuntimeException e) { // e.g. ForegroundServiceStartNotAllowedException when c:geo is in background
            Toast.makeText(ctx, "Cannot start mock location: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- service

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_SPEED.equals(intent.getAction())) {
            if (!running) { // raced with a stop: don't leave an idle started service behind
                stopSelf();
            } else if (tripMode) { // re-base so the position does not jump
                tripBaseMeters = travelled();
                tripStartMs = System.currentTimeMillis();
                tripSpeedMps = Math.max(1.0, intent.getDoubleExtra(EXTRA_SPEED_KMH, 50.0)) / 3.6;
            }
            return START_NOT_STICKY;
        }
        final String text;
        if (ACTION_TRIP.equals(intent.getAction())) {
            final double[] p = pendingPath;
            pendingPath = null;
            if (p == null || p.length < 4) {
                if (goForegroundOrFail("No route")) { // startForegroundService() demands startForeground() even when bailing out
                    fail("Mock route failed: no route");
                }
                return START_NOT_STICKY;
            }
            startTrip(p, Math.max(1.0, intent.getDoubleExtra(EXTRA_SPEED_KMH, 50.0)) / 3.6);
            text = String.format(Locale.US, "Driving %.1f km at %.0f km/h", cumMeters[cumMeters.length - 1] / 1000, tripSpeedMps * 3.6);
        } else {
            tripMode = false;
            lat = intent.getDoubleExtra(EXTRA_LAT, 0);
            lon = intent.getDoubleExtra(EXTRA_LON, 0);
            speedMps = 0f;
            text = String.format(Locale.US, "Fixed at %.6f, %.6f", lat, lon);
        }
        if (!goForegroundOrFail(text)) {
            return START_NOT_STICKY;
        }
        try {
            ensureProviders();
        } catch (final SecurityException e) {
            fail("Select c:geo under Developer options > Select mock location app");
            return START_NOT_STICKY;
        }
        running = true;
        Toast.makeText(this, "Mock location: " + text, Toast.LENGTH_SHORT).show();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private void startTrip(final double[] p, final double mps) {
        path = p;
        final int n = p.length / 2;
        cumMeters = new double[n];
        final float[] r = new float[1];
        for (int i = 1; i < n; i++) {
            Location.distanceBetween(p[2 * i - 2], p[2 * i - 1], p[2 * i], p[2 * i + 1], r);
            cumMeters[i] = cumMeters[i - 1] + r[0];
        }
        seg = 1;
        tripBaseMeters = 0;
        tripSpeedMps = mps;
        tripStartMs = System.currentTimeMillis();
        tripMode = true;
        lat = p[0];
        lon = p[1];
    }

    private void step() {
        if (tripMode) {
            final double travelled = travelled();
            final int n = path.length / 2;
            if (travelled >= cumMeters[n - 1]) {
                lat = path[2 * (n - 1)];
                lon = path[2 * (n - 1) + 1];
                speedMps = 0f;
                tripMode = false;
            } else {
                while (seg < n - 1 && cumMeters[seg] < travelled) { // travelled only grows, so resume from last segment
                    seg++;
                }
                final double segLen = cumMeters[seg] - cumMeters[seg - 1];
                final double f = segLen <= 0 ? 1 : (travelled - cumMeters[seg - 1]) / segLen;
                final double aLat = path[2 * seg - 2];
                final double aLon = path[2 * seg - 1];
                final double bLat = path[2 * seg];
                final double bLon = path[2 * seg + 1];
                lat = aLat + (bLat - aLat) * f;
                lon = aLon + (bLon - aLon) * f;
                bearing = bearing(aLat, aLon, bLat, bLon);
                speedMps = (float) tripSpeedMps;
            }
        }
        push(gps, LocationManager.GPS_PROVIDER, 3f);
        push(net, LocationManager.NETWORK_PROVIDER, 10f);
        final Location f = push(fused, "fused", 3f); // built even below API 31: GMS in mock mode needs it or freezes
        if (gms != null) {
            gms.setMockLocation(f);
        }
    }

    private double travelled() {
        return tripBaseMeters + (System.currentTimeMillis() - tripStartMs) / 1000.0 * tripSpeedMps;
    }

    private Location push(final MockLocationProvider provider, final String name, final float accuracy) {
        final Location l = MockLocationProvider.getLocation(name, lat, lon);
        l.setBearing(bearing);
        l.setSpeed(speedMps);
        l.setAccuracy(accuracy);
        if (provider != null) {
            lm.setTestProviderLocation(name, l);
        }
        return l;
    }

    private static float bearing(final double lat1, final double lon1, final double lat2, final double lon2) {
        final double p1 = Math.toRadians(lat1);
        final double p2 = Math.toRadians(lat2);
        final double dl = Math.toRadians(lon2 - lon1);
        final double y = Math.sin(dl) * Math.cos(p2);
        final double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0);
    }

    private void ensureProviders() {
        if (lm == null) {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }
        if (gps == null) {
            gps = new MockLocationProvider(LocationManager.GPS_PROVIDER, this);
        }
        if (net == null) {
            net = new MockLocationProvider(LocationManager.NETWORK_PROVIDER, this);
        }
        if (fused == null && Build.VERSION.SDK_INT >= 31) {
            fused = new MockLocationProvider("fused", this);
        }
        // c:geo uses Google's fused location by default; without this it mixes real Wi-Fi/cell fixes into the mock
        if (gms == null && GooglePlayServices.isAvailable()) {
            gms = LocationServices.getFusedLocationProviderClient(this);
            gms.setMockMode(true);
        }
    }

    private boolean goForegroundOrFail(final String text) {
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Mock GPS", NotificationManager.IMPORTANCE_LOW));
        final PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, MockGpsService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Mock location active")
                .setContentText(text)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (final RuntimeException e) { // API 34+: location-type FGS needs the location permission granted
            fail("Mock location needs c:geo's location permission");
            return false;
        }
    }

    private void fail(final String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        stopEverything();
    }

    private void stopEverything() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (gms != null) {
            gms.setMockMode(false); // otherwise fused location stays frozen at the last mock fix
            gms = null;
        }
        // Last fix stamped 10 min old: c:geo prefers any GPS fix younger than 30 s over network fixes, and Android keeps
        // the last fix as "last known" - a fresh mock fix would block the real position after stopping.
        final String[] names = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"};
        final MockLocationProvider[] providers = {gps, net, fused};
        for (int i = 0; i < providers.length; i++) {
            if (providers[i] != null) {
                try {
                    final Location l = MockLocationProvider.getLocation(names[i], lat, lon);
                    l.setTime(System.currentTimeMillis() - 10 * 60 * 1000L);
                    l.setAccuracy(5000f);
                    lm.setTestProviderLocation(names[i], l);
                } catch (final RuntimeException ignored) {
                    // provider already gone - nothing to clear
                }
                providers[i].shutdown();
            }
        }
        gps = null;
        net = null;
        fused = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) {
            stopEverything();
        }
        super.onDestroy();
    }
}
