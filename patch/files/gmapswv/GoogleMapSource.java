package cgeo.geocaching.unifiedmap.tileproviders;

import cgeo.geocaching.R;
import cgeo.geocaching.utils.LocalizationUtils;

import android.net.Uri;

import androidx.core.util.Pair;

/**
 * "Google Maps" entry of the map source menu. Selecting it opens the restricted Google Maps WebView (see
 * UnifiedMapActivity / gmapswv patch). Underneath it is plain OpenStreetMap, so that an old stored
 * preference pointing at this source still gives a working map.
 */
public class GoogleMapSource extends AbstractMapsforgeOnlineTileProvider {
    GoogleMapSource() {
        super(LocalizationUtils.getPlainString(R.string.map_source_google_map) + " (WebView)", Uri.parse("https://tile.openstreetmap.org"), "/{Z}/{X}/{Y}.png", 2, 18, new Pair<>(LocalizationUtils.getPlainString(R.string.map_attribution_openstreetmap_html), true));
    }
}
