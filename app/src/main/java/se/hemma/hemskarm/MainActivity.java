package se.hemma.hemskarm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final Locale SV = new Locale("sv", "SE");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", SV);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", SV);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM", SV);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", SV);

    private static final long CALENDAR_EVERY_MS = 10 * 60 * 1000L;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private static final int TODAY_MAX_ROWS = 5;
    private static final int UPCOMING_MAX_LINES = 6;
    private static final int UPCOMING_DAYS = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView clock, date, greeting, weatherIcon, weatherTemp, weatherDesc;
    private LinearLayout todayList, upcomingList, appRow;

    private long lastCalendar = 0, lastWeather = 0;
    private List<IcsParser.Event> events = new ArrayList<>();
    private String calendarError = null;
    private boolean calendarLoaded = false;
    private LocalDate renderedDay = null;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            if (now - lastCalendar > CALENDAR_EVERY_MS) loadCalendar();
            if (now - lastWeather > WEATHER_EVERY_MS) loadWeather();
            if (!LocalDate.now().equals(renderedDay)) renderCalendar(); // ny dag vid midnatt
            ui.postDelayed(this, 1000 - (now % 1000));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        greeting = findViewById(R.id.greeting);
        weatherIcon = findViewById(R.id.weather_icon);
        weatherTemp = findViewById(R.id.weather_temp);
        weatherDesc = findViewById(R.id.weather_desc);
        todayList = findViewById(R.id.today_list);
        upcomingList = findViewById(R.id.upcoming_list);
        appRow = findViewById(R.id.app_row);
        renderCalendar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildAppRow();
        // Hämta nytt när man kommer tillbaka från en app eller inställningarna.
        lastCalendar = 0;
        lastWeather = 0;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // Hemskärmen är längst bak – tillbaka-knappen ska inte stänga den.
    }

    // ---- Klocka och hälsning ----

    private void updateClock() {
        LocalDateTime now = LocalDateTime.now();
        clock.setText(now.format(CLOCK));
        date.setText(capitalize(now.format(DATE)));

        int h = now.getHour();
        String hello;
        if (h >= 5 && h < 10) hello = "God morgon";
        else if (h >= 18 && h < 23) hello = "God kväll";
        else if (h >= 23 || h < 5) hello = "God natt";
        else hello = "Hej";
        String name = new Prefs(this).name();
        greeting.setText(name.isEmpty() ? hello + "!" : hello + ", " + name + "!");
    }

    // ---- Väder ----

    private void loadWeather() {
        lastWeather = System.currentTimeMillis();
        Prefs p = new Prefs(this);
        if (!p.hasLocation()) {
            weatherIcon.setText("");
            weatherTemp.setText("");
            weatherDesc.setText(R.string.weather_missing);
            return;
        }
        final double lat = p.lat(), lon = p.lon();
        final String label = p.placeLabel();
        io.execute(() -> {
            try {
                Weather w = Weather.fetch(lat, lon);
                ui.post(() -> {
                    weatherIcon.setText(w.icon);
                    weatherTemp.setText(w.temp + "°");
                    StringBuilder d = new StringBuilder();
                    if (!w.description.isEmpty()) d.append(w.description).append(" · ");
                    d.append(w.min).append("° / ").append(w.max).append("°");
                    if (!label.isEmpty()) d.append(" · ").append(label);
                    weatherDesc.setText(d);
                });
            } catch (Exception e) {
                // Behåll senaste vädret; försök igen om fem minuter.
                ui.post(() -> lastWeather = System.currentTimeMillis() - WEATHER_EVERY_MS + 5 * 60 * 1000L);
            }
        });
    }

    // ---- Kalender ----

    private void loadCalendar() {
        lastCalendar = System.currentTimeMillis();
        final List<String> urls = new Prefs(this).icsUrls();
        if (urls.isEmpty()) {
            events = new ArrayList<>();
            calendarError = null;
            calendarLoaded = true;
            renderCalendar();
            return;
        }
        io.execute(() -> {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime from = LocalDate.now(zone).atStartOfDay(zone);
            ZonedDateTime to = from.plusDays(UPCOMING_DAYS + 1);
            List<IcsParser.Event> all = new ArrayList<>();
            int failures = 0;
            for (String url : urls) {
                try {
                    all.addAll(IcsParser.parse(Net.get(url), zone, from, to));
                } catch (Exception e) {
                    failures++;
                }
            }
            java.util.Collections.sort(all);
            final int failed = failures;
            ui.post(() -> {
                if (failed == urls.size()) {
                    // Behåll det vi visade senast och försök igen om två minuter.
                    calendarError = getString(R.string.calendar_error);
                    lastCalendar = System.currentTimeMillis() - CALENDAR_EVERY_MS + 2 * 60 * 1000L;
                } else {
                    events = all;
                    calendarError = null;
                }
                calendarLoaded = true;
                renderCalendar();
            });
        });
    }

    private void renderCalendar() {
        LocalDate today = LocalDate.now();
        renderedDay = today;
        todayList.removeAllViews();
        upcomingList.removeAllViews();

        if (new Prefs(this).icsUrls().isEmpty()) {
            todayList.addView(note(getString(R.string.calendar_missing)));
            return;
        }
        if (!calendarLoaded) {
            todayList.addView(note(getString(R.string.loading)));
            return;
        }
        if (calendarError != null && events.isEmpty()) {
            todayList.addView(note(calendarError));
            return;
        }

        // Idag
        List<IcsParser.Event> todays = new ArrayList<>();
        for (IcsParser.Event e : events) {
            if (!e.firstDay().isAfter(today) && !e.lastDay().isBefore(today)) todays.add(e);
        }
        if (todays.isEmpty()) {
            todayList.addView(note(getString(R.string.nothing_today)));
        } else {
            ZonedDateTime now = ZonedDateTime.now();
            int shown = 0;
            for (IcsParser.Event e : todays) {
                if (shown == TODAY_MAX_ROWS - 1 && todays.size() > TODAY_MAX_ROWS) {
                    todayList.addView(note("+ " + (todays.size() - shown) + " till"));
                    break;
                }
                boolean past = !e.allDay && e.end.isBefore(now);
                todayList.addView(eventRow(timeText(e, today), e.title, past, true));
                shown++;
            }
        }

        // Kommande dagar – varje händelse visas en gång, på första dagen efter idag.
        LocalDate first = today.plusDays(1);
        LocalDate last = today.plusDays(UPCOMING_DAYS);
        int lines = 0;
        LocalDate currentDay = null;
        int remaining = 0;
        for (IcsParser.Event e : events) {
            if (e.lastDay().isBefore(first) || e.firstDay().isAfter(last)) continue;
            LocalDate day = e.firstDay().isBefore(first) ? first : e.firstDay();
            boolean needsHeader = !day.equals(currentDay);
            int cost = needsHeader ? 2 : 1;
            if (lines + cost > UPCOMING_MAX_LINES) {
                remaining++;
                continue;
            }
            if (needsHeader) {
                upcomingList.addView(dayHeader(day, today, currentDay == null));
                currentDay = day;
            }
            upcomingList.addView(eventRow(timeText(e, day), e.title, false, false));
            lines += cost;
        }
        if (remaining > 0) upcomingList.addView(note("+ " + remaining + " till"));
        if (upcomingList.getChildCount() == 0) upcomingList.addView(note(getString(R.string.nothing_upcoming)));
        if (calendarError != null) upcomingList.addView(note(calendarError));
    }

    private String timeText(IcsParser.Event e, LocalDate day) {
        if (e.allDay) return "Heldag";
        boolean startsEarlier = e.firstDay().isBefore(day);
        boolean endsLater = e.lastDay().isAfter(day);
        if (startsEarlier && endsLater) return "Heldag";
        if (startsEarlier) return "Till " + e.end.format(TIME);
        if (!e.end.isAfter(e.start)) return e.start.format(TIME);
        if (endsLater) return "Från " + e.start.format(TIME);
        return e.start.format(TIME) + "–" + e.end.format(TIME);
    }

    private View eventRow(String time, String title, boolean past, boolean large) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = Apps.dp(this, large ? 6 : 4);
        row.setPadding(0, vpad, 0, vpad);

        View dot = new View(this);
        dot.setBackgroundResource(R.drawable.dot);
        int ds = Apps.dp(this, 8);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dlp.rightMargin = Apps.dp(this, 12);
        row.addView(dot, dlp);

        TextView t = new TextView(this);
        t.setText(time);
        t.setTextColor(getColor(R.color.text));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 20 : 17);
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(Apps.dp(this, large ? 132 : 112),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 21 : 17);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (past) row.setAlpha(0.4f);
        return row;
    }

    private View dayHeader(LocalDate day, LocalDate today, boolean firstHeader) {
        TextView t = new TextView(this);
        String text = day.equals(today.plusDays(1)) ? "Imorgon · " + capitalize(day.format(DateTimeFormatter.ofPattern("EEEE", SV)))
                : capitalize(day.format(DAY));
        t.setText(text);
        t.setTextColor(getColor(R.color.accent));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, Apps.dp(this, firstHeader ? 0 : 8), 0, Apps.dp(this, 2));
        return t;
    }

    private View note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setPadding(0, Apps.dp(this, 4), 0, Apps.dp(this, 4));
        return t;
    }

    // ---- Appraden ----

    private void buildAppRow() {
        View focused = appRow.findFocus();
        int focusIndex = focused == null ? 0 : Math.max(0, appRow.indexOfChild(focused));

        appRow.removeAllViews();
        List<Apps.Installed> all = Apps.launchable(this);
        int gap = Apps.dp(this, 14);

        for (Apps.Shortcut s : Apps.ROW) {
            Apps.Installed app = Apps.resolve(this, s, all);
            FrameLayout tile;
            if (app != null) {
                tile = Apps.tile(this, Apps.banner(this, app.packageName), Apps.icon(this, app.packageName),
                        null, s.label, false);
                tile.setOnClickListener(v -> Apps.start(this, app));
            } else {
                tile = Apps.tile(this, null, null, "＋", s.label, true);
                tile.setOnClickListener(v -> Apps.openStore(this, s));
            }
            addTile(tile, 2f, gap);
        }

        FrameLayout allApps = Apps.tile(this, null, null, null, getString(R.string.all_apps), false);
        allApps.setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));
        addTile(allApps, 1.5f, gap);

        FrameLayout settings = Apps.tile(this, null, null, "⚙", getString(R.string.settings), false, false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        addTile(settings, 0.9f, 0);

        View target = appRow.getChildAt(Math.min(focusIndex, appRow.getChildCount() - 1));
        if (target != null) target.requestFocus();
    }

    private void addTile(View tile, float weight, int rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.rightMargin = rightGap;
        appRow.addView(tile, lp);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(SV) + s.substring(1);
    }
}
