package se.hemma.hemskarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/** Väder från Open-Meteo (gratis, ingen nyckel behövs). */
final class Weather {
    final int temp;
    final int min;
    final int max;
    final String description;
    final String icon;

    private Weather(int temp, int min, int max, String description, String icon) {
        this.temp = temp;
        this.min = min;
        this.max = max;
        this.description = description;
        this.icon = icon;
    }

    static Weather fetch(double lat, double lon) throws Exception {
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                lat, lon);
        JSONObject root = new JSONObject(Net.get(url));
        JSONObject cur = root.getJSONObject("current");
        JSONObject daily = root.getJSONObject("daily");
        int code = cur.optInt("weather_code", 0);
        boolean day = cur.optInt("is_day", 1) == 1;
        return new Weather(
                (int) Math.round(cur.getDouble("temperature_2m")),
                (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                describe(code),
                icon(code, day));
    }

    /** Slår upp en ort. Returnerar {namn, lat, lon} eller null. */
    static Object[] geocode(String place) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=sv&format=json&name="
                + URLEncoder.encode(place.trim(), "UTF-8");
        JSONObject root = new JSONObject(Net.get(url));
        JSONArray results = root.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject r = results.getJSONObject(0);
        return new Object[]{r.optString("name", place), r.getDouble("latitude"), r.getDouble("longitude")};
    }

    private static String describe(int code) {
        switch (code) {
            case 0: return "Klart";
            case 1: return "Mestadels klart";
            case 2: return "Halvklart";
            case 3: return "Mulet";
            case 45: case 48: return "Dimma";
            case 51: case 53: case 55: return "Duggregn";
            case 56: case 57: return "Underkylt duggregn";
            case 61: return "Lätt regn";
            case 63: return "Regn";
            case 65: return "Kraftigt regn";
            case 66: case 67: return "Underkylt regn";
            case 71: return "Lätt snöfall";
            case 73: return "Snöfall";
            case 75: return "Kraftigt snöfall";
            case 77: return "Snökorn";
            case 80: case 81: return "Regnskurar";
            case 82: return "Kraftiga regnskurar";
            case 85: case 86: return "Snöbyar";
            case 95: return "Åska";
            case 96: case 99: return "Åska med hagel";
            default: return "";
        }
    }

    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1 || code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }
}
