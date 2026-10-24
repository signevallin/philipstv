package se.hemma.hemskarm;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Sparade inställningar: namn i hälsningen, ort för vädret och kalenderlänkar. */
final class Prefs {
    private static final String FILE = "hemskarm";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String name() { return sp.getString("name", ""); }
    String place() { return sp.getString("place", ""); }
    String placeLabel() { return sp.getString("placeLabel", ""); }
    boolean hasLocation() { return sp.contains("lat") && sp.contains("lon"); }
    double lat() { return Double.longBitsToDouble(sp.getLong("lat", 0)); }
    double lon() { return Double.longBitsToDouble(sp.getLong("lon", 0)); }
    String icsRaw() { return sp.getString("ics", ""); }

    List<String> icsUrls() {
        List<String> out = new ArrayList<>();
        for (String line : icsRaw().split("[\\s,]+")) {
            String u = line.trim();
            if (u.isEmpty()) continue;
            if (u.startsWith("webcal://")) u = "https://" + u.substring("webcal://".length());
            if (u.startsWith("http://") || u.startsWith("https://")) out.add(u);
        }
        return out;
    }

    void saveBasics(String name, String ics) {
        sp.edit().putString("name", name.trim()).putString("ics", ics.trim()).apply();
    }

    void saveLocation(String place, String label, double lat, double lon) {
        sp.edit()
                .putString("place", place.trim())
                .putString("placeLabel", label)
                .putLong("lat", Double.doubleToRawLongBits(lat))
                .putLong("lon", Double.doubleToRawLongBits(lon))
                .apply();
    }

    void clearLocation() {
        sp.edit().remove("place").remove("placeLabel").remove("lat").remove("lon").apply();
    }
}
