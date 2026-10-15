package de.htmlapk.builder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Hauptmenü: alle Projekte als Kacheln mit Icon und Name, großer Plus-Button für ein neues. */
public class ProjectsActivity extends Activity {

    private final List<Project> projects = new ArrayList<>();
    private ProjectAdapter adapter;
    private TextView emptyView;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF212121);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("Meine Projekte");
        toolbar.setTitleTextColor(Color.WHITE);
        column.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setActionBar(toolbar);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(0xFFFAFAFA);
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(150));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(12), dp(12), dp(12), dp(112)); // Platz für den Plus-Button
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new ProjectAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> openProject(projects.get(pos).id));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            confirmDelete(projects.get(pos));
            return true;
        });
        content.addView(grid);

        emptyView = new TextView(this);
        emptyView.setText("Noch keine Projekte.\n\nTippe auf ＋, um deine erste App zu bauen.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(0xFF757575);
        emptyView.setTextSize(16);
        emptyView.setPadding(dp(32), 0, dp(32), dp(80));
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Großer Plus-Button
        Button plus = new Button(this);
        plus.setText("+");
        plus.setTextSize(44);
        plus.setTextColor(Color.WHITE);
        plus.setTypeface(Typeface.DEFAULT_BOLD);
        plus.setPadding(0, 0, 0, dp(6));
        plus.setContentDescription("Neues Projekt");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFE8622C);
        plus.setBackground(circle);
        plus.setElevation(dp(8));
        plus.setStateListAnimator(null);
        plus.setOnClickListener(v -> openProject(Project.create(this).id));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(80), dp(80),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(24), dp(24));
        content.addView(plus, lp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        projects.clear();
        projects.addAll(Project.list(this));
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(projects.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Info")
                .setIcon(R.drawable.ic_info)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, InfoActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openProject(String id) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_PROJECT_ID, id);
        startActivity(i);
    }

    private void confirmDelete(Project p) {
        new AlertDialog.Builder(this)
                .setTitle("„" + p.displayName() + "“ löschen?")
                .setMessage("Das Projekt mit allen gespeicherten Dateien wird entfernt. "
                        + "Bereits installierte Apps bleiben auf dem Handy.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen", (dlg, w) -> {
                    p.delete();
                    onResume();
                })
                .show();
    }

    private int dp(int v) {
        return Math.round(v * d);
    }

    // ------------------------------------------------------------- Kacheln

    private class ProjectAdapter extends BaseAdapter {
        private final SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);

        @Override public int getCount() { return projects.size(); }
        @Override public Object getItem(int pos) { return projects.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout card;
            ImageView icon;
            TextView title, subtitle;
            if (convert instanceof LinearLayout) {
                card = (LinearLayout) convert;
                icon = (ImageView) card.getChildAt(0);
                title = (TextView) card.getChildAt(1);
                subtitle = (TextView) card.getChildAt(2);
            } else {
                card = new LinearLayout(ProjectsActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setPadding(dp(12), dp(16), dp(12), dp(14));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.WHITE);
                bg.setCornerRadius(dp(16));
                bg.setStroke(dp(1), 0xFFE0E0E0);
                card.setBackground(bg);
                card.setElevation(dp(2));

                icon = new ImageView(ProjectsActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                card.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

                title = new TextView(ProjectsActivity.this);
                title.setTextSize(16);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setTextColor(0xFF212121);
                title.setGravity(Gravity.CENTER);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setPadding(0, dp(10), 0, 0);
                card.addView(title);

                subtitle = new TextView(ProjectsActivity.this);
                subtitle.setTextSize(12);
                subtitle.setTextColor(0xFF757575);
                subtitle.setGravity(Gravity.CENTER);
                subtitle.setPadding(0, dp(4), 0, 0);
                card.addView(subtitle);
            }

            Project p = projects.get(pos);
            Bitmap bmp = p.hasIcon() ? BitmapFactory.decodeFile(p.iconFile().getPath()) : null;
            if (bmp != null) icon.setImageBitmap(bmp);
            else icon.setImageResource(R.drawable.default_app_icon);
            title.setText(p.displayName());
            subtitle.setText(p.lastBuiltCode > 0
                    ? "Version " + p.lastBuiltName + " · " + date.format(new Date(p.lastBuiltAt))
                    : "Noch nicht gebaut");
            return card;
        }
    }
}
