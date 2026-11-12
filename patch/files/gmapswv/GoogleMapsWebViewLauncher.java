package cgeo.geocaching.gmapswv;

import cgeo.geocaching.location.Geopoint;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Opens the restricted Google Maps WebView, optionally centered on the current c:geo map position. */
public final class GoogleMapsWebViewLauncher {

    private GoogleMapsWebViewLauncher() {
    }

    public static void open(final Context context, @Nullable final Geopoint center, final int zoom) {
        String url = "https://www.google.com/maps";
        if (center != null) {
            url = String.format(Locale.US, "https://www.google.com/maps/@%.6f,%.6f,%dz",
                    center.getLatitude(), center.getLongitude(), Math.max(3, Math.min(21, zoom)));
        }
        context.startActivity(new Intent(context, GoogleMapsWebViewActivity.class).setData(Uri.parse(url)));
    }
}
