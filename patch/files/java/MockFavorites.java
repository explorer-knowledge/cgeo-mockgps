package cgeo.geocaching.mockgps;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/** Favourite (bookmarked) mock locations, stored as JSON in SharedPreferences. */
public final class MockFavorites {

    public static final class Fav {
        public final String name;
        public final double lat;
        public final double lon;

        Fav(final String name, final double lat, final double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final String PREFS = "mockgps_favorites";
    private static final String KEY = "items";

    private MockFavorites() {
    }

    public static List<Fav> load(final Context c) {
        final List<Fav> out = new ArrayList<>();
        try {
            final JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                out.add(new Fav(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")));
            }
        } catch (final Exception ignored) {
            // corrupt data: start empty
        }
        return out;
    }

    public static void add(final Context c, final String name, final double lat, final double lon) {
        final List<Fav> all = load(c);
        all.removeIf(f -> f.name.equals(name));
        all.add(new Fav(name, lat, lon));
        save(c, all);
    }

    public static void remove(final Context c, final String name) {
        final List<Fav> all = load(c);
        all.removeIf(f -> f.name.equals(name));
        save(c, all);
    }

    private static void save(final Context c, final List<Fav> all) {
        try {
            final JSONArray a = new JSONArray();
            for (final Fav f : all) {
                a.put(new JSONObject().put("name", f.name).put("lat", f.lat).put("lon", f.lon));
            }
            prefs(c).edit().putString(KEY, a.toString()).apply();
        } catch (final Exception ignored) {
            // cannot happen for plain values
        }
    }

    private static SharedPreferences prefs(final Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
