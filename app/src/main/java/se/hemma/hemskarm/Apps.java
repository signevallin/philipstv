package se.hemma.hemskarm;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Appgenvägarna längst ner, plus hjälp för att rita en appruta. */
final class Apps {

    /** En app som ska ligga i raden. Paketnamn provas i ordning, sedan appens namn. */
    static final class Shortcut {
        final String label;
        final String[] packages;
        final String[] names;

        Shortcut(String label, String[] packages, String[] names) {
            this.label = label;
            this.packages = packages;
            this.names = names;
        }
    }

    static final Shortcut[] ROW = {
            new Shortcut("Netflix",
                    new String[]{"com.netflix.ninja"},
                    new String[]{"netflix"}),
            new Shortcut("HBO Max",
                    new String[]{"com.wbd.stream", "com.hbo.hbonow", "com.hbo.android.app", "com.hbo.go"},
                    new String[]{"hbo max", "max", "hbo"}),
            new Shortcut("TV4 Play",
                    new String[]{"se.tv4.tv4playtab", "se.tv4.tv4play", "se.tv4.androidtv"},
                    new String[]{"tv4 play", "tv4"}),
            new Shortcut("SVT Play",
                    new String[]{"se.svt.svtplay", "se.svt.android.svtplay", "se.svt.svtplay.tv"},
                    new String[]{"svt play", "svtplay"}),
            new Shortcut("YouTube",
                    new String[]{"com.google.android.youtube.tv", "com.google.android.youtube.googletv"},
                    new String[]{"youtube"}),
    };

    /** Installerad app som går att starta. */
    static final class Installed {
        final String packageName;
        final String label;
        final Intent launch;

        Installed(String packageName, String label, Intent launch) {
            this.packageName = packageName;
            this.label = label;
            this.launch = launch;
        }
    }

    private Apps() {}

    /** Alla appar som går att starta från en TV-hemskärm, sorterade på namn. */
    static List<Installed> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Installed> byPkg = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || byPkg.containsKey(pkg)) continue;
                Intent launch = launchIntent(pm, pkg);
                if (launch == null) continue;
                byPkg.put(pkg, new Installed(pkg, String.valueOf(ri.loadLabel(pm)), launch));
            }
        }
        List<Installed> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static Installed resolve(Context c, Shortcut s, List<Installed> all) {
        PackageManager pm = c.getPackageManager();
        for (String pkg : s.packages) {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null) return new Installed(pkg, s.label, launch);
        }
        for (String name : s.names) {
            for (Installed app : all) {
                if (normalize(app.label).equals(name)) return app;
            }
        }
        return null;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static void start(Context c, Installed app) {
        try {
            c.startActivity(app.launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "Kunde inte starta " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    static void openStore(Context c, Shortcut s) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + s.packages[0]))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, s.label + " är inte installerad", Toast.LENGTH_SHORT).show();
        }
    }

    /** TV-appar har en liggande "banner" med logga. Saknas den används ikonen. */
    static Drawable banner(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Drawable d = pm.getApplicationBanner(pkg);
            if (d != null) return d;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch != null) {
                d = pm.getActivityBanner(launch);
                if (d != null) return d;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    static Drawable icon(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.loadIcon(c.getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ---- Rutor ----

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /**
     * Skapar en fokuserbar ruta. Med banner fyller loggan rutan; annars visas
     * ikon (eller symbol) med namnet under.
     */
    static FrameLayout tile(Context c, Drawable banner, Drawable icon, String symbol, String label, boolean dimmed) {
        return tile(c, banner, icon, symbol, label, dimmed, true);
    }

    static FrameLayout tile(Context c, Drawable banner, Drawable icon, String symbol, String label,
                            boolean dimmed, boolean showLabel) {
        FrameLayout tile = new FrameLayout(c);
        tile.setBackgroundResource(R.drawable.tile_bg);
        int pad = dp(c, 4);
        tile.setPadding(pad, pad, pad, pad);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(label);

        if (banner != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(banner);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundResource(R.drawable.tile_inner);
            iv.setClipToOutline(true);
            tile.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            if (icon != null) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(icon);
                int size = dp(c, 36);
                col.addView(iv, new LinearLayout.LayoutParams(size, size));
            } else if (symbol != null) {
                TextView sym = new TextView(c);
                sym.setText(symbol);
                sym.setTextColor(c.getColor(R.color.on_panel));
                sym.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                sym.setGravity(Gravity.CENTER);
                col.addView(sym);
            }
            if (!showLabel) {
                tile.addView(col, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                return finish(c, tile, dimmed);
            }
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(c.getColor(R.color.on_panel));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(c, 4);
            col.addView(tv, lp);
            tile.addView(col, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        return finish(c, tile, dimmed);
    }

    private static FrameLayout finish(Context c, FrameLayout tile, boolean dimmed) {
        if (dimmed) tile.setAlpha(0.45f);
        final float baseAlpha = dimmed ? 0.45f : 1f;

        tile.setOnFocusChangeListener((View v, boolean focused) -> {
            v.animate()
                    .scaleX(focused ? 1.08f : 1f)
                    .scaleY(focused ? 1.08f : 1f)
                    .translationZ(focused ? dp(c, 8) : 0)
                    .alpha(focused ? 1f : baseAlpha)
                    .setDuration(140)
                    .start();
        });
        return tile;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
