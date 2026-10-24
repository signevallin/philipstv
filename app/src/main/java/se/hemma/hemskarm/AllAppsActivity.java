package se.hemma.hemskarm;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Alla installerade appar – behövs när TV:ns vanliga startskärm är avstängd. */
public class AllAppsActivity extends Activity {

    private static final int COLUMNS = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.screen_bg);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int h = Apps.dp(this, 56), v = Apps.dp(this, 32);
        col.setPadding(h, v, h, v);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText(R.string.all_apps);
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setPadding(0, 0, 0, Apps.dp(this, 20));
        col.addView(title);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        col.addView(grid);

        int screenDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int gap = 16;
        int tileW = (screenDp - 2 * 56 - (COLUMNS - 1) * gap) / COLUMNS;
        int tileH = tileW * 9 / 16;

        List<Apps.Installed> apps = Apps.launchable(this);
        for (int i = 0; i < apps.size(); i++) {
            Apps.Installed app = apps.get(i);
            FrameLayout tile = Apps.tile(this, Apps.banner(this, app.packageName),
                    Apps.icon(this, app.packageName), null, app.label, false);
            tile.setOnClickListener(x -> Apps.start(this, app));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Apps.dp(this, tileW);
            lp.height = Apps.dp(this, tileH);
            lp.rightMargin = (i % COLUMNS == COLUMNS - 1) ? 0 : Apps.dp(this, gap);
            lp.bottomMargin = Apps.dp(this, gap);
            grid.addView(tile, lp);
        }

        setContentView(scroll);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }
}
