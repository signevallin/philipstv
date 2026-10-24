package se.hemma.hemskarm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Inställningar. Går att fylla i med fjärrkontrollen, eller i ett svep via adb:
 * adb shell am start -n se.hemma.hemskarm/.SettingsActivity --es name "Signe och Anton"
 *   --es place "Hässelby" --es ics "https://calendar.google.com/calendar/ical/.../basic.ics"
 */
public class SettingsActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText name, place, ics;
    private TextView status;
    private Button save;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs p = new Prefs(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.screen_bg);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int h = Apps.dp(this, 120), v = Apps.dp(this, 36);
        col.setPadding(h, v, h, v);
        scroll.addView(col);

        TextView title = text(getString(R.string.settings), 30, R.color.text);
        col.addView(title);

        col.addView(label(getString(R.string.label_name)));
        name = field(p.name(), getString(R.string.hint_name), false);
        col.addView(name);

        col.addView(label(getString(R.string.label_place)));
        place = field(p.place(), getString(R.string.hint_place), false);
        col.addView(place);

        col.addView(label(getString(R.string.label_ics)));
        ics = field(p.icsRaw(), getString(R.string.hint_ics), true);
        col.addView(ics);
        TextView help = text(getString(R.string.ics_help), 14, R.color.text_dim);
        help.setPadding(0, Apps.dp(this, 6), 0, 0);
        col.addView(help);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Apps.dp(this, 24), 0, 0);
        save = button(getString(R.string.save));
        save.setOnClickListener(x -> save(true));
        buttons.addView(save);
        Button tv = button(getString(R.string.tv_settings));
        tv.setOnClickListener(x -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Hittade inte TV-inställningarna", Toast.LENGTH_SHORT).show();
            }
        });
        buttons.addView(tv);
        col.addView(buttons);

        status = text("", 16, R.color.accent);
        status.setPadding(0, Apps.dp(this, 14), 0, 0);
        col.addView(status);

        setContentView(scroll);
        applyExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyExtras(intent);
    }

    /** Värden skickade med adb fylls i och sparas direkt. */
    private void applyExtras(Intent i) {
        if (i == null || i.getExtras() == null) return;
        boolean any = false;
        if (i.hasExtra("name")) { name.setText(i.getStringExtra("name")); any = true; }
        if (i.hasExtra("place")) { place.setText(i.getStringExtra("place")); any = true; }
        if (i.hasExtra("ics")) { ics.setText(i.getStringExtra("ics")); any = true; }
        if (any) save(false);
    }

    private void save(boolean closeWhenDone) {
        if (saving) return;
        saving = true;
        save.setEnabled(false);
        final Prefs p = new Prefs(this);
        final String newName = name.getText().toString();
        final String newPlace = place.getText().toString().trim();
        final String newIcs = ics.getText().toString();
        p.saveBasics(newName, newIcs);

        if (newPlace.isEmpty()) {
            p.clearLocation();
            done(getString(R.string.saved), closeWhenDone);
            return;
        }
        if (newPlace.equalsIgnoreCase(p.place()) && p.hasLocation()) {
            done(getString(R.string.saved), closeWhenDone);
            return;
        }
        status.setText(R.string.looking_up_place);
        new Thread(() -> {
            String msg;
            boolean ok;
            try {
                Object[] r = Weather.geocode(newPlace);
                if (r == null) {
                    msg = "Hittade ingen ort som heter \"" + newPlace + "\". Resten är sparat.";
                    ok = false;
                } else {
                    p.saveLocation(newPlace, (String) r[0], (Double) r[1], (Double) r[2]);
                    msg = getString(R.string.saved) + " Väder för " + r[0] + ".";
                    ok = true;
                }
            } catch (Exception e) {
                msg = "Kunde inte slå upp orten (ingen internetanslutning?). Resten är sparat.";
                ok = false;
            }
            final String m = msg;
            final boolean close = closeWhenDone && ok;
            ui.post(() -> done(m, close));
        }).start();
    }

    private void done(String msg, boolean close) {
        saving = false;
        save.setEnabled(true);
        status.setText(msg);
        if (close) ui.postDelayed(this::finish, 900);
    }

    // ---- Byggstenar ----

    private TextView text(String s, int sp, int colorRes) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(colorRes));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 16, R.color.accent);
        t.setPadding(0, Apps.dp(this, 22), 0, Apps.dp(this, 6));
        return t;
    }

    private EditText field(String value, String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(getColor(R.color.on_panel));
        e.setHintTextColor(getColor(R.color.text_faint));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setBackgroundResource(R.drawable.field_bg);
        int pad = Apps.dp(this, 14);
        e.setPadding(pad, pad, pad, pad);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(2);
            e.setMaxLines(4);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            e.setSingleLine(true);
        }
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(getColor(R.color.on_panel));
        b.setBackgroundResource(R.drawable.button_bg);
        int ph = Apps.dp(this, 26), pv = Apps.dp(this, 12);
        b.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Apps.dp(this, 14);
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((View view, boolean f) ->
                view.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(120).start());
        return b;
    }
}
