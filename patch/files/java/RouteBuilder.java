package cgeo.geocaching.mockgps;

import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.maps.routing.Routing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/** Builds a track (polyline) between two points. Blocking: call from a background thread. */
public final class RouteBuilder {

    public enum Mode { OSRM, CGEO, STRAIGHT }

    private RouteBuilder() {
    }

    /** @return flattened polyline lat0, lon0, lat1, lon1, ... (at least two points) */
    public static double[] build(final Mode mode, final double sLat, final double sLon, final double dLat, final double dLon) throws IOException {
        switch (mode) {
            case OSRM:
                return osrm(sLat, sLon, dLat, dLon);
            case CGEO:
                return cgeo(sLat, sLon, dLat, dLon);
            default:
                return new double[]{sLat, sLon, dLat, dLon};
        }
    }

    private static double[] cgeo(final double sLat, final double sLon, final double dLat, final double dLon) throws IOException {
        if (!Routing.isConnected()) {
            throw new IOException("c:geo routing (BRouter) is not connected - configure it in c:geo settings or use OSRM");
        }
        final Geopoint[] track = Routing.getTrackNoCaching(new Geopoint(sLat, sLon), new Geopoint(dLat, dLon), null, null);
        final double[] out = new double[track.length * 2];
        for (int i = 0; i < track.length; i++) {
            out[2 * i] = track[i].getLatitude();
            out[2 * i + 1] = track[i].getLongitude();
        }
        return out;
    }

    private static double[] osrm(final double sLat, final double sLon, final double dLat, final double dLon) throws IOException {
        final String url = String.format(Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                sLon, sLat, dLon, dLat);
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "cgeo-mockgps-patch");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            final byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) {
                buf.write(b, 0, r);
            }
            final JSONObject root = new JSONObject(buf.toString("UTF-8"));
            if (!"Ok".equals(root.optString("code"))) {
                throw new IOException("OSRM: " + root.optString("code") + " " + root.optString("message"));
            }
            final JSONArray coords = root.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
            final double[] out = new double[coords.length() * 2];
            for (int i = 0; i < coords.length(); i++) {
                final JSONArray pt = coords.getJSONArray(i);
                out[2 * i] = pt.getDouble(1);
                out[2 * i + 1] = pt.getDouble(0);
            }
            return out;
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("OSRM response error: " + e.getMessage(), e);
        } finally {
            c.disconnect();
        }
    }
}
