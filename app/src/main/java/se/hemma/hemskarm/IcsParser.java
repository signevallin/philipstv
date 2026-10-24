package se.hemma.hemskarm;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Läser en iCal-fil (t.ex. Google Kalenders "hemliga adress i iCal-format")
 * och returnerar händelser inom ett tidsfönster. Klarar återkommande
 * händelser (RRULE), undantag (EXDATE) och ändrade enskilda tillfällen
 * (RECURRENCE-ID). Ren Java utan Android-beroenden så den kan testas fristående.
 */
public final class IcsParser {

    public static final class Event implements Comparable<Event> {
        public final String title;
        public final ZonedDateTime start;
        public final ZonedDateTime end; // exklusivt slut
        public final boolean allDay;

        Event(String title, ZonedDateTime start, ZonedDateTime end, boolean allDay) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
        }

        /** Första dagen händelsen syns på. */
        public LocalDate firstDay() {
            return start.toLocalDate();
        }

        /** Sista dagen händelsen syns på (inklusive). */
        public LocalDate lastDay() {
            if (!end.isAfter(start)) return start.toLocalDate();
            return end.minusNanos(1).toLocalDate();
        }

        @Override
        public int compareTo(Event o) {
            int c = start.toLocalDate().compareTo(o.start.toLocalDate());
            if (c != 0) return c;
            if (allDay != o.allDay) return allDay ? -1 : 1;
            c = start.compareTo(o.start);
            if (c != 0) return c;
            return title.compareToIgnoreCase(o.title);
        }

        @Override
        public String toString() {
            return (allDay ? "[heldag] " : "") + start + " -> " + end + " " + title;
        }
    }

    private static final class Prop {
        final String name;
        final Map<String, String> params;
        final String value;

        Prop(String name, Map<String, String> params, String value) {
            this.name = name;
            this.params = params;
            this.value = value;
        }
    }

    private static final class When {
        final ZonedDateTime time; // i händelsens egen tidszon
        final boolean allDay;

        When(ZonedDateTime time, boolean allDay) {
            this.time = time;
            this.allDay = allDay;
        }
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int MAX_ITERATIONS = 20000;

    private IcsParser() {}

    public static List<Event> parse(String ics, ZoneId zone, ZonedDateTime from, ZonedDateTime to) {
        List<List<Prop>> vevents = splitEvents(unfold(ics));

        // Först: samla ändrade enskilda tillfällen per UID så att mastern hoppar över dem.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (List<Prop> ev : vevents) {
            Prop rid = first(ev, "RECURRENCE-ID");
            Prop uid = first(ev, "UID");
            if (rid == null || uid == null) continue;
            When w = parseWhen(rid, zone);
            if (w == null) continue;
            overridden.computeIfAbsent(uid.value, k -> new HashSet<>()).add(w.time.toEpochSecond());
        }

        List<Event> out = new ArrayList<>();
        for (List<Prop> ev : vevents) {
            try {
                expand(ev, zone, from, to, overridden, out);
            } catch (RuntimeException ignored) {
                // En trasig händelse ska inte stoppa resten av kalendern.
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void expand(List<Prop> ev, ZoneId zone, ZonedDateTime from, ZonedDateTime to,
                               Map<String, Set<Long>> overridden, List<Event> out) {
        Prop status = first(ev, "STATUS");
        if (status != null && "CANCELLED".equalsIgnoreCase(status.value.trim())) return;

        Prop dtStart = first(ev, "DTSTART");
        if (dtStart == null) return;
        When start = parseWhen(dtStart, zone);
        if (start == null) return;

        Prop summary = first(ev, "SUMMARY");
        String title = summary == null ? "(utan titel)" : unescape(summary.value).trim();
        if (title.isEmpty()) title = "(utan titel)";

        // Längd
        Duration length;
        int allDayLengthDays = 1;
        Prop dtEnd = first(ev, "DTEND");
        Prop dur = first(ev, "DURATION");
        if (dtEnd != null) {
            When end = parseWhen(dtEnd, zone);
            if (end == null) return;
            length = Duration.between(start.time, end.time);
            if (start.allDay) {
                allDayLengthDays = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                        start.time.toLocalDate(), end.time.toLocalDate()));
            }
        } else if (dur != null) {
            length = parseDuration(dur.value);
            if (start.allDay) allDayLengthDays = (int) Math.max(1, length.toDays());
        } else {
            length = start.allDay ? Duration.ofDays(1) : Duration.ZERO;
        }
        if (length.isNegative()) length = Duration.ZERO;

        // Undantag
        Set<Long> exdates = new HashSet<>();
        for (Prop p : ev) {
            if (!"EXDATE".equals(p.name)) continue;
            for (String part : p.value.split(",")) {
                When w = parseWhen(new Prop(p.name, p.params, part.trim()), zone);
                if (w != null) exdates.add(w.time.toEpochSecond());
            }
        }
        Prop uid = first(ev, "UID");
        Set<Long> skip = uid == null ? null : overridden.get(uid.value);
        boolean isOverride = first(ev, "RECURRENCE-ID") != null;

        Prop rrule = first(ev, "RRULE");
        List<ZonedDateTime> starts;
        if (rrule == null || isOverride) {
            starts = Collections.singletonList(start.time);
        } else {
            ZonedDateTime windowStart = from.minus(length).minusDays(allDayLengthDays + 1);
            starts = occurrences(start, parseRule(rrule.value), zone, windowStart, to);
        }

        for (ZonedDateTime s : starts) {
            long epoch = s.toEpochSecond();
            if (exdates.contains(epoch)) continue;
            if (!isOverride && skip != null && rrule != null && skip.contains(epoch)) continue;

            ZonedDateTime e;
            if (start.allDay) {
                e = s.toLocalDate().plusDays(allDayLengthDays).atStartOfDay(s.getZone());
            } else {
                e = s.plus(length);
            }
            ZonedDateTime sOut = start.allDay ? s.toLocalDate().atStartOfDay(zone) : s.withZoneSameInstant(zone);
            ZonedDateTime eOut = start.allDay ? e.toLocalDate().atStartOfDay(zone) : e.withZoneSameInstant(zone);

            boolean overlaps = eOut.isAfter(from) || (!eOut.isAfter(sOut) && !sOut.isBefore(from));
            if (overlaps && sOut.isBefore(to)) {
                out.add(new Event(title, sOut, eOut, start.allDay));
            }
        }
    }

    // ---- Återkommande händelser ----

    private static Map<String, String> parseRule(String value) {
        Map<String, String> r = new HashMap<>();
        for (String part : value.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) r.put(part.substring(0, i).trim().toUpperCase(Locale.ROOT), part.substring(i + 1).trim());
        }
        return r;
    }

    private static List<ZonedDateTime> occurrences(When start, Map<String, String> rule, ZoneId zone,
                                                   ZonedDateTime windowStart, ZonedDateTime to) {
        List<ZonedDateTime> out = new ArrayList<>();
        String freq = rule.getOrDefault("FREQ", "");
        int interval = parseInt(rule.get("INTERVAL"), 1);
        if (interval < 1) interval = 1;
        int count = parseInt(rule.get("COUNT"), -1);
        ZonedDateTime until = null;
        if (rule.containsKey("UNTIL")) {
            String u = rule.get("UNTIL");
            When w = parseWhen(new Prop("UNTIL", new HashMap<>(), u), start.time.getZone());
            if (w != null) {
                until = u.length() == 8 ? w.time.toLocalDate().plusDays(1).atStartOfDay(start.time.getZone()).minusNanos(1) : w.time;
            }
        }
        List<String> byDay = new ArrayList<>();
        if (rule.containsKey("BYDAY")) {
            for (String d : rule.get("BYDAY").split(",")) if (!d.trim().isEmpty()) byDay.add(d.trim().toUpperCase(Locale.ROOT));
        }
        List<Integer> byMonthDay = new ArrayList<>();
        if (rule.containsKey("BYMONTHDAY")) {
            for (String d : rule.get("BYMONTHDAY").split(",")) {
                int v = parseInt(d.trim(), 0);
                if (v != 0) byMonthDay.add(v);
            }
        }

        ZonedDateTime s0 = start.time;
        LocalTime time = s0.toLocalTime();
        ZoneId evZone = s0.getZone();
        int produced = 0;

        for (int k = 0; k < MAX_ITERATIONS; k++) {
            List<ZonedDateTime> candidates = new ArrayList<>();
            switch (freq) {
                case "DAILY":
                    candidates.add(s0.plusDays((long) k * interval));
                    break;
                case "WEEKLY": {
                    LocalDate weekStart = s0.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .plusWeeks((long) k * interval);
                    List<DayOfWeek> days = new ArrayList<>();
                    if (byDay.isEmpty()) days.add(s0.getDayOfWeek());
                    else for (String d : byDay) {
                        DayOfWeek dow = dayOf(d);
                        if (dow != null && !days.contains(dow)) days.add(dow);
                    }
                    Collections.sort(days);
                    for (DayOfWeek dow : days) {
                        candidates.add(ZonedDateTime.of(weekStart.plusDays(dow.getValue() - 1), time, evZone));
                    }
                    break;
                }
                case "MONTHLY": {
                    LocalDate month = s0.toLocalDate().withDayOfMonth(1).plusMonths((long) k * interval);
                    List<LocalDate> dates = new ArrayList<>();
                    if (!byDay.isEmpty()) {
                        for (String d : byDay) {
                            LocalDate nth = nthWeekday(month, d);
                            if (nth != null) dates.add(nth);
                            else {
                                DayOfWeek dow = dayOf(d);
                                if (dow != null) { // alla t.ex. tisdagar i månaden
                                    LocalDate x = month.with(TemporalAdjusters.firstInMonth(dow));
                                    while (x.getMonth() == month.getMonth()) { dates.add(x); x = x.plusWeeks(1); }
                                }
                            }
                        }
                    } else {
                        List<Integer> mds = byMonthDay.isEmpty()
                                ? Collections.singletonList(s0.getDayOfMonth()) : byMonthDay;
                        for (int md : mds) {
                            int len = month.lengthOfMonth();
                            int day = md > 0 ? md : len + md + 1;
                            if (day >= 1 && day <= len) dates.add(month.withDayOfMonth(day));
                        }
                    }
                    Collections.sort(dates);
                    for (LocalDate d : dates) candidates.add(ZonedDateTime.of(d, time, evZone));
                    break;
                }
                case "YEARLY": {
                    LocalDate d0 = s0.toLocalDate();
                    int year = d0.getYear() + k * interval;
                    if (d0.getMonthValue() == 2 && d0.getDayOfMonth() == 29 && !java.time.Year.isLeap(year)) break;
                    candidates.add(ZonedDateTime.of(d0.withYear(year), time, evZone));
                    break;
                }
                default:
                    out.add(s0);
                    return out;
            }

            for (ZonedDateTime c : candidates) {
                if (c.isBefore(s0)) continue;
                if (until != null && c.isAfter(until)) return out;
                if (count > 0 && produced >= count) return out;
                produced++;
                if (!c.isBefore(windowStart) && c.isBefore(to)) out.add(c);
            }
            if (count > 0 && produced >= count) return out;
            // Stoppa när vi passerat fönstret (kandidaterna växer alltid framåt).
            ZonedDateTime periodStart;
            switch (freq) {
                case "DAILY": periodStart = s0.plusDays((long) k * interval); break;
                case "WEEKLY": periodStart = ZonedDateTime.of(s0.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks((long) k * interval), LocalTime.MIDNIGHT, evZone); break;
                case "MONTHLY": periodStart = ZonedDateTime.of(s0.toLocalDate().withDayOfMonth(1)
                        .plusMonths((long) k * interval), LocalTime.MIDNIGHT, evZone); break;
                default: periodStart = s0.plusYears((long) k * interval).minusDays(1); break;
            }
            if (!periodStart.isBefore(to)) return out;
        }
        return out;
    }

    private static DayOfWeek dayOf(String code) {
        String c = code.length() >= 2 ? code.substring(code.length() - 2) : code;
        switch (c) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    /** "2TU" = andra tisdagen, "-1FR" = sista fredagen. Null om koden saknar ordningstal. */
    private static LocalDate nthWeekday(LocalDate month, String code) {
        if (code.length() <= 2) return null;
        int n = parseInt(code.substring(0, code.length() - 2), 0);
        DayOfWeek dow = dayOf(code);
        if (n == 0 || dow == null) return null;
        LocalDate d;
        if (n > 0) {
            d = month.with(TemporalAdjusters.firstInMonth(dow)).plusWeeks(n - 1);
        } else {
            d = month.with(TemporalAdjusters.lastInMonth(dow)).minusWeeks(-n - 1);
        }
        return d.getMonth() == month.getMonth() ? d : null;
    }

    // ---- Datum och tid ----

    private static When parseWhen(Prop p, ZoneId fallback) {
        String v = p.value.trim();
        try {
            if (v.length() == 8 || "DATE".equalsIgnoreCase(p.params.get("VALUE"))) {
                LocalDate d = LocalDate.parse(v.substring(0, 8), D);
                return new When(d.atStartOfDay(fallback), true);
            }
            if (v.endsWith("Z") || v.endsWith("z")) {
                LocalDateTime ldt = LocalDateTime.parse(v.substring(0, 15), DT);
                return new When(ldt.atZone(ZoneOffset.UTC), false);
            }
            LocalDateTime ldt = LocalDateTime.parse(v.substring(0, 15), DT);
            ZoneId z = fallback;
            String tzid = p.params.get("TZID");
            if (tzid != null) {
                try {
                    z = ZoneId.of(tzid.replace("\"", "").trim());
                } catch (RuntimeException ignored) {
                    // Okänd tidszon (t.ex. Windows-namn) – använd TV:ns.
                }
            }
            return new When(ldt.atZone(z), false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration parseDuration(String v) {
        String s = v.trim().toUpperCase(Locale.ROOT);
        boolean neg = s.startsWith("-");
        if (s.startsWith("-") || s.startsWith("+")) s = s.substring(1);
        Duration d;
        try {
            if (s.endsWith("W")) {
                d = Duration.ofDays(7L * Long.parseLong(s.substring(1, s.length() - 1)));
            } else {
                d = Duration.parse(s);
            }
        } catch (RuntimeException e) {
            d = Duration.ZERO;
        }
        return neg ? d.negated() : d;
    }

    // ---- Textformat ----

    private static List<String> unfold(String ics) {
        String[] raw = ics.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !lines.isEmpty()) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + line.substring(1));
            } else if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static List<List<Prop>> splitEvents(List<String> lines) {
        List<List<Prop>> events = new ArrayList<>();
        List<Prop> current = null;
        int nested = 0;
        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.equals("BEGIN:VEVENT")) {
                current = new ArrayList<>();
                nested = 0;
                continue;
            }
            if (current == null) continue;
            if (upper.equals("END:VEVENT")) {
                events.add(current);
                current = null;
                continue;
            }
            if (upper.startsWith("BEGIN:")) { nested++; continue; }
            if (upper.startsWith("END:")) { if (nested > 0) nested--; continue; }
            if (nested > 0) continue; // t.ex. VALARM
            Prop p = parseLine(line);
            if (p != null) current.add(p);
        }
        return events;
    }

    private static Prop parseLine(String line) {
        boolean quoted = false;
        int colon = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) { colon = i; break; }
        }
        if (colon <= 0) return null;
        String head = line.substring(0, colon);
        String value = line.substring(colon + 1);
        String[] parts = head.split(";");
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) params.put(parts[i].substring(0, eq).toUpperCase(Locale.ROOT), parts[i].substring(eq + 1));
        }
        return new Prop(parts[0].toUpperCase(Locale.ROOT), params, value);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n' || n == 'N') b.append(' ');
                else b.append(n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static Prop first(List<Prop> props, String name) {
        for (Prop p : props) if (p.name.equals(name)) return p;
        return null;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim().replace("+", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
