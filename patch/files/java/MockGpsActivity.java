package cgeo.geocaching.mockgps;

import cgeo.geocaching.activity.AbstractActionBarActivity;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.maps.routing.Routing;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Simple code-built UI (no XML layout) for the mock GPS feature: fixed position, favourites, route simulation. */
public class MockGpsActivity extends AbstractActionBarActivity {

    private EditText fixedLat;
    private EditText fixedLon;
    private EditText startLat;
    private EditText startLon;
    private EditText destLat;
    private EditText destLon;
    private EditText speedKmh;
    private Spinner routerMode;
    private TextView statusView;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            statusView.setText("Status: " + MockGpsService.status);
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme();
        setTitle("Mock GPS");

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        statusView = text("Status: " + MockGpsService.status, true);
        root.addView(statusView);
        root.addView(text("Android only lets one app fake the location: select c:geo under Developer options > Select mock location app.", false));
        root.addView(button("Open developer settings", v -> openDeveloperSettings()));

        root.addView(heading("Fixed position"));
        fixedLat = field("Latitude (e.g. 23.2599)");
        fixedLon = field("Longitude (e.g. 77.4126)");
        root.addView(fixedLat);
        root.addView(fixedLon);
        root.addView(row(
                button("Paste coords", v -> pasteInto(fixedLat, fixedLon)),
                button("Start fixed", v -> startFixed()),
                button("Stop", v -> stopMock())));
        root.addView(row(
                button("Save favourite", v -> saveFavourite()),
                button("Favourites", v -> showFavourites())));

        root.addView(heading("Route between two points"));
        startLat = field("Start latitude");
        startLon = field("Start longitude");
        destLat = field("Destination latitude");
        destLon = field("Destination longitude");
        root.addView(startLat);
        root.addView(startLon);
        root.addView(row(
                button("Paste start", v -> pasteInto(startLat, startLon)),
                button("Fixed -> start", v -> copy(fixedLat, fixedLon, startLat, startLon))));
        root.addView(destLat);
        root.addView(destLon);
        root.addView(row(
                button("Paste destination", v -> pasteInto(destLat, destLon)),
                button("Fixed -> dest", v -> copy(fixedLat, fixedLon, destLat, destLon))));
        speedKmh = field("Speed km/h");
        speedKmh.setText("50");
        root.addView(speedKmh);
        routerMode = new Spinner(this);
        routerMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Route: OSRM online (roads)", "Route: c:geo BRouter", "Route: straight line"}));
        root.addView(routerMode);
        root.addView(row(
                button("Create route & simulate", v -> startTrip()),
                button("Stop", v -> stopMock())));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        try {
            Routing.connect(this);
        } catch (final Exception ignored) {
            // BRouter mode will report that it is unavailable
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    public void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    // ---------------------------------------------------------------- actions

    private void startFixed() {
        final double[] p = parse(fixedLat, fixedLon);
        if (p == null) {
            return;
        }
        MockGpsService.status = "Starting...";
        startForegroundService(new Intent(this, MockGpsService.class)
                .setAction(MockGpsService.ACTION_FIXED)
                .putExtra(MockGpsService.EXTRA_LAT, p[0])
                .putExtra(MockGpsService.EXTRA_LON, p[1]));
    }

    private void startTrip() {
        final double[] s = parse(startLat, startLon);
        final double[] d = parse(destLat, destLon);
        if (s == null || d == null) {
            return;
        }
        final double speed;
        try {
            speed = Double.parseDouble(speedKmh.getText().toString().trim().replace(',', '.'));
        } catch (final NumberFormatException e) {
            toast("Invalid speed");
            return;
        }
        final RouteBuilder.Mode mode = RouteBuilder.Mode.values()[routerMode.getSelectedItemPosition()];
        MockGpsService.status = "Building route...";
        new Thread(() -> {
            try {
                final double[] path = RouteBuilder.build(mode, s[0], s[1], d[0], d[1]);
                runOnUiThread(() -> {
                    MockGpsService.pendingPath = path;
                    MockGpsService.status = "Starting...";
                    startForegroundService(new Intent(this, MockGpsService.class)
                            .setAction(MockGpsService.ACTION_TRIP)
                            .putExtra(MockGpsService.EXTRA_SPEED_KMH, speed));
                    toast("Route with " + (path.length / 2) + " points");
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    MockGpsService.status = "Route failed";
                    toast("Route failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void stopMock() {
        startService(new Intent(this, MockGpsService.class).setAction(MockGpsService.ACTION_STOP));
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (final Exception e) {
            toast("Open Settings > System > Developer options manually");
        }
    }

    // -------------------------------------------------------------- favourites

    private void saveFavourite() {
        final double[] p = parse(fixedLat, fixedLon);
        if (p == null) {
            return;
        }
        final EditText name = new EditText(this);
        name.setHint("Name");
        new AlertDialog.Builder(this)
                .setTitle("Save favourite")
                .setView(name)
                .setPositiveButton("Save", (dlg, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        toast("Name is empty");
                    } else {
                        MockFavorites.add(this, n, p[0], p[1]);
                        toast("Saved " + n);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showFavourites() {
        final List<MockFavorites.Fav> favs = MockFavorites.load(this);
        if (favs.isEmpty()) {
            toast("No favourites yet");
            return;
        }
        final String[] labels = new String[favs.size()];
        for (int i = 0; i < labels.length; i++) {
            final MockFavorites.Fav f = favs.get(i);
            labels[i] = String.format(Locale.US, "%s  (%.5f, %.5f)", f.name, f.lat, f.lon);
        }
        new AlertDialog.Builder(this)
                .setTitle("Favourites")
                .setItems(labels, (dlg, which) -> favouriteActions(favs.get(which)))
                .show();
    }

    private void favouriteActions(final MockFavorites.Fav f) {
        final String[] actions = {"Use as fixed position", "Use as route start", "Use as route destination", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(actions, (dlg, which) -> {
                    switch (which) {
                        case 0:
                            set(fixedLat, fixedLon, f);
                            break;
                        case 1:
                            set(startLat, startLon, f);
                            break;
                        case 2:
                            set(destLat, destLon, f);
                            break;
                        default:
                            MockFavorites.remove(this, f.name);
                            toast("Deleted " + f.name);
                            break;
                    }
                })
                .show();
    }

    private static void set(final EditText la, final EditText lo, final MockFavorites.Fav f) {
        la.setText(String.format(Locale.US, "%.6f", f.lat));
        lo.setText(String.format(Locale.US, "%.6f", f.lon));
    }

    // ----------------------------------------------------------------- helpers

    private void pasteInto(final EditText la, final EditText lo) {
        final ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            toast("Clipboard is empty");
            return;
        }
        String t = clip.getItemAt(0).getText().toString().trim();
        if (t.startsWith("geo:")) {
            t = t.substring(4).split("\\?")[0];
        }
        try {
            final Geopoint gp = new Geopoint(t.replace(";", ","));
            la.setText(String.format(Locale.US, "%.6f", gp.getLatitude()));
            lo.setText(String.format(Locale.US, "%.6f", gp.getLongitude()));
        } catch (final Exception e) {
            toast("Cannot read coordinates from clipboard");
        }
    }

    private static void copy(final EditText fromLa, final EditText fromLo, final EditText toLa, final EditText toLo) {
        toLa.setText(fromLa.getText());
        toLo.setText(fromLo.getText());
    }

    private double[] parse(final EditText la, final EditText lo) {
        try {
            final double a = Double.parseDouble(la.getText().toString().trim().replace(',', '.'));
            final double b = Double.parseDouble(lo.getText().toString().trim().replace(',', '.'));
            if (Math.abs(a) > 90 || Math.abs(b) > 180) {
                throw new NumberFormatException();
            }
            return new double[]{a, b};
        } catch (final NumberFormatException e) {
            toast("Invalid latitude/longitude");
            return null;
        }
    }

    private void toast(final String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(final int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(final String s, final boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(bold ? 16 : 13);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView heading(final String s) {
        final TextView t = text(s, true);
        t.setTextSize(18);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private EditText field(final String hint) {
        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    private Button button(final String label, final View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(final View... views) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (final View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }
}
