package cgeo.geocaching.mockgps;

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
import android.os.HandlerThread;
import android.os.IBinder;

/**
 * Foreground service that feeds fake GPS + network locations to Android's test providers.
 * Modes: fixed position, or a trip along a polyline at constant speed (then holds at the destination).
 * Idea and provider handling taken from warren-bank/Android-Mock-Location (GPL-2.0).
 */
public class MockGpsService extends Service {

    public static final String ACTION_FIXED = "cgeo.geocaching.mockgps.FIXED";
    public static final String ACTION_TRIP = "cgeo.geocaching.mockgps.TRIP";
    public static final String ACTION_STOP = "cgeo.geocaching.mockgps.STOP";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";
    /** flattened polyline lat0, lon0, lat1, lon1, ... handed over in-process (avoids Binder size limits) before ACTION_TRIP */
    public static volatile double[] pendingPath;
    public static final String EXTRA_SPEED_KMH = "speed_kmh";

    /** read by the UI */
    public static volatile boolean running = false;
    public static volatile String status = "Idle";

    private static final String CHANNEL_ID = "mockgps";
    private static final int NOTIFICATION_ID = 0x4d47;
    private static final long TICK_MS = 1000;

    private HandlerThread thread;
    private Handler handler;
    private LocationManager lm;
    private MockLocationProvider gps;
    private MockLocationProvider net;

    private double lat;
    private double lon;
    private float bearing = 0f;
    private float speedMps = 0f;

    private boolean tripMode = false;
    private double[] path;
    private double[] cumMeters;
    private double totalMeters;
    private double tripSpeedMps;
    private long tripStartMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (final Exception e) {
                status = "Error: " + e.getMessage();
                stopEverything();
                return;
            }
            if (handler != null) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

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
        goForeground();
        try {
            ensureProviders();
        } catch (final SecurityException e) {
            status = "c:geo is not selected as mock location app (Developer options > Select mock location app)";
            stopEverything();
            return START_NOT_STICKY;
        }

        if (ACTION_FIXED.equals(intent.getAction())) {
            tripMode = false;
            lat = intent.getDoubleExtra(EXTRA_LAT, 0);
            lon = intent.getDoubleExtra(EXTRA_LON, 0);
            speedMps = 0f;
            status = String.format(java.util.Locale.US, "Fixed at %.6f, %.6f", lat, lon);
        } else if (ACTION_TRIP.equals(intent.getAction())) {
            final double[] p = pendingPath;
            pendingPath = null;
            if (p == null || p.length < 4) {
                status = "No route given";
                stopEverything();
                return START_NOT_STICKY;
            }
            startTrip(p, Math.max(1.0, intent.getDoubleExtra(EXTRA_SPEED_KMH, 50.0)) / 3.6);
        }

        running = true;
        if (thread == null) {
            thread = new HandlerThread("mockgps");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
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
        totalMeters = cumMeters[n - 1];
        tripSpeedMps = mps;
        tripStartMs = System.currentTimeMillis();
        tripMode = true;
        lat = p[0];
        lon = p[1];
    }

    private void step() {
        if (tripMode) {
            final double travelled = (System.currentTimeMillis() - tripStartMs) / 1000.0 * tripSpeedMps;
            final int n = path.length / 2;
            if (travelled >= totalMeters) {
                lat = path[2 * (n - 1)];
                lon = path[2 * (n - 1) + 1];
                speedMps = 0f;
                tripMode = false;
                status = "Arrived - holding destination";
            } else {
                int i = 1;
                while (i < n - 1 && cumMeters[i] < travelled) {
                    i++;
                }
                final double segLen = cumMeters[i] - cumMeters[i - 1];
                final double f = segLen <= 0 ? 1 : (travelled - cumMeters[i - 1]) / segLen;
                final double aLat = path[2 * i - 2];
                final double aLon = path[2 * i - 1];
                final double bLat = path[2 * i];
                final double bLon = path[2 * i + 1];
                lat = aLat + (bLat - aLat) * f;
                lon = aLon + (bLon - aLon) * f;
                bearing = bearing(aLat, aLon, bLat, bLon);
                speedMps = (float) tripSpeedMps;
                status = String.format(java.util.Locale.US, "Trip %.0f%% - %.1f km/h - %.0f of %.0f m",
                        100.0 * travelled / totalMeters, tripSpeedMps * 3.6, travelled, totalMeters);
            }
        }
        push(gps, LocationManager.GPS_PROVIDER);
        push(net, LocationManager.NETWORK_PROVIDER);
    }

    private void push(final MockLocationProvider provider, final String name) {
        if (provider == null) {
            return;
        }
        final Location l = MockLocationProvider.getLocation(name, lat, lon);
        l.setBearing(bearing);
        l.setSpeed(speedMps);
        l.setAccuracy(name.equals(LocationManager.GPS_PROVIDER) ? 3f : 10f);
        lm.setTestProviderLocation(name, l);
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
    }

    private void goForeground() {
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Mock GPS", NotificationManager.IMPORTANCE_LOW));
        final PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, MockGpsService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Mock GPS active")
                .setContentText("c:geo is faking your location")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void stopEverything() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
        if (gps != null) {
            gps.shutdown();
            gps = null;
        }
        if (net != null) {
            net.shutdown();
            net = null;
        }
        if (!status.startsWith("Error") && !status.startsWith("c:geo is not") && !status.startsWith("No route")) {
            status = "Stopped";
        }
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
