package app.oled.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home screen: pure black background, a clock, and a grid of large 16:9 tiles.
 * Plain framework views only, no AndroidX, so the process stays small.
 *
 * A TV app shows its banner. An app without one shows its icon and name.
 * OK opens an app. Holding OK (or pressing MENU) opens a menu to hide the app
 * or open its system info page. Hidden apps collect in the last tile.
 */
public class MainActivity extends Activity {

    private static final class App {
        String label;
        String pkg;
        ComponentName component;
        Bitmap banner;
        Bitmap icon;
    }

    private static final int COLOR_TEXT = 0xFF8C8C8C;
    private static final int COLOR_CARD = 0xFF141414;
    private static final int COLOR_DIM = 0x8C000000;
    private static final float SCALE_SELECTED = 1.08f;
    private static final int CORNER_DP = 10;
    private static final String PREFS = "launcher";
    private static final String PREF_HIDDEN = "hidden";

    private final List<App> visible = new ArrayList<>();
    private final List<App> hidden = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private GridView grid;
    private Adapter adapter;
    private int iconPx;
    private int loadGeneration;
    private boolean longPressHandled;
    private Drawable.ConstantState cardBackground;
    private Drawable.ConstantState cardForeground;

    private final Runnable reload = new Runnable() {
        @Override public void run() { load(); }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // An install or update sends several broadcasts in a row. Wait for them to settle.
            main.removeCallbacks(reload);
            main.postDelayed(reload, 600);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        iconPx = dp(48);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        // The focused tile grows past its cell. Let it draw outside.
        root.setClipChildren(false);
        // TV overscan: keep content away from the edges.
        root.setPadding(dp(48), dp(28), dp(48), 0);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        root.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(COLOR_TEXT);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = dp(2);
        dateParams.bottomMargin = dp(12);
        root.addView(date, dateParams);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(190));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(6));
        grid.setHorizontalSpacing(dp(22));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        // Room for the focused tile to grow at the top and bottom rows.
        grid.setPadding(0, dp(12), 0, dp(24));
        grid.setFocusable(true);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < visible.size()) open(visible.get(position));
            else showHiddenList();
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showMenuAt(position);
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        GradientDrawable card = new GradientDrawable();
        card.setColor(COLOR_CARD);
        card.setCornerRadius(dp(CORNER_DP));
        cardBackground = card.getConstantState();

        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(CORNER_DP));
        border.setStroke(dp(3), Color.WHITE);
        // A white border alone vanishes on white banners. Dimming the other
        // tiles makes the focused one stand out on any banner.
        StateListDrawable focus = new StateListDrawable();
        focus.addState(new int[] {android.R.attr.state_selected}, border);
        focus.addState(new int[] {android.R.attr.state_pressed}, border);
        focus.addState(new int[0], new ColorDrawable(COLOR_DIM));
        cardForeground = focus.getConstantState();

        setContentView(root);

        // Banners are drawn at the exact card size, which is known only after
        // the grid has measured its columns.
        grid.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        if (grid.getColumnWidth() <= 0) return;
                        grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        load();
                    }
                });

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        grid.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already on the home screen: go back to the first tile.
        if (grid != null) {
            grid.setSelection(0);
            grid.requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // This is the home screen. There is nowhere to go back to.
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() >= 1) {
                if (!longPressHandled) {
                    longPressHandled = true;
                    showMenuAt(grid.getSelectedItemPosition());
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && longPressHandled) {
                longPressHandled = false;
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            showMenuAt(grid.getSelectedItemPosition());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showMenuAt(int position) {
        if (position < 0) return;
        if (position >= visible.size()) {
            showHiddenList();
            return;
        }
        final App app = visible.get(position);
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(app.label)
                .setItems(new CharSequence[] {"Скрыть", "О приложении"}, (dialog, which) -> {
                    if (which == 0) setHidden(app.pkg, true);
                    else showInfo(app);
                })
                .show();
    }

    private void showHiddenList() {
        if (hidden.isEmpty()) return;
        CharSequence[] labels = new CharSequence[hidden.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hidden.get(i).label;
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle("Скрытые · выберите, чтобы вернуть")
                .setItems(labels, (dialog, which) -> setHidden(hidden.get(which).pkg, false))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void setHidden(String pkg, boolean hide) {
        // The stored set must not be modified in place.
        Set<String> packages = new HashSet<>(prefs.getStringSet(PREF_HIDDEN, Collections.<String>emptySet()));
        if (hide) packages.add(pkg);
        else packages.remove(pkg);
        prefs.edit().putStringSet(PREF_HIDDEN, packages).apply();
        load();
    }

    private void open(App app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(app.component)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // The app was removed or disabled between the last scan and the click.
            load();
        }
    }

    private void showInfo(App app) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.pkg, null));
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // Some TV firmware ships without the app details page.
        }
    }

    /** Scans installed apps off the main thread, then swaps the lists in. */
    private void load() {
        // Not laid out yet. The layout listener in onCreate calls back.
        final int cardWidth = grid.getColumnWidth();
        if (cardWidth <= 0) return;
        final int cardHeight = cardWidth * 9 / 16;
        final int corner = dp(CORNER_DP);
        final int generation = ++loadGeneration;
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        final Set<String> hiddenPackages =
                new HashSet<>(prefs.getStringSet(PREF_HIDDEN, Collections.<String>emptySet()));
        new Thread(() -> {
            Map<String, App> byPackage = new LinkedHashMap<>();
            // TV entry points first, so an app with both keeps its TV one.
            String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String category : categories) {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
                for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                    ActivityInfo activity = info.activityInfo;
                    if (activity.packageName.equals(self)) continue;
                    if (byPackage.containsKey(activity.packageName)) continue;
                    App app = new App();
                    app.label = String.valueOf(info.loadLabel(pm));
                    app.pkg = activity.packageName;
                    app.component = new ComponentName(activity.packageName, activity.name);
                    byPackage.put(activity.packageName, app);
                    // A hidden app shows only its name in the list. Skip the pictures.
                    if (hiddenPackages.contains(app.pkg)) continue;
                    Drawable banner = activity.loadBanner(pm);
                    if (banner != null) app.banner = drawBanner(banner, cardWidth, cardHeight, corner);
                    else app.icon = drawIcon(info.loadIcon(pm), iconPx);
                }
            }
            final List<App> shown = new ArrayList<>();
            final List<App> concealed = new ArrayList<>();
            for (App app : byPackage.values()) {
                (hiddenPackages.contains(app.pkg) ? concealed : shown).add(app);
            }
            Comparator<App> byLabel = new Comparator<App>() {
                @Override public int compare(App a, App b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            };
            Collections.sort(shown, byLabel);
            Collections.sort(concealed, byLabel);
            main.post(() -> {
                if (generation != loadGeneration || isDestroyed()) return;
                visible.clear();
                visible.addAll(shown);
                hidden.clear();
                hidden.addAll(concealed);
                adapter.notifyDataSetChanged();
            });
        }, "app-scan").start();
    }

    /**
     * Draws a banner once at the exact card size, so nothing is scaled when
     * the grid is drawn and the full-size original can be freed.
     *
     * The window is rendered without the GPU, where outline clipping does not
     * work, so the rounded corners are drawn into the picture itself. With no
     * transparency left, RGB_565 holds it in half the memory of ARGB_8888.
     */
    private static Bitmap drawBanner(Drawable drawable, int width, int height, int corner) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(COLOR_CARD);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        Path outside = new Path();
        outside.setFillType(Path.FillType.EVEN_ODD);
        outside.addRect(0, 0, width, height, Path.Direction.CW);
        outside.addRoundRect(new RectF(0, 0, width, height), corner, corner, Path.Direction.CW);
        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        canvas.drawPath(outside, black);
        return bitmap;
    }

    /** Icons keep their transparency: they sit on the card colour. */
    private static Bitmap drawIcon(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return visible.size() + (hidden.isEmpty() ? 0 : 1); }
        @Override public Object getItem(int position) { return position < visible.size() ? visible.get(position) : null; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile = (Tile) convertView;
            if (tile == null) tile = new Tile(MainActivity.this);
            tile.position = position;
            tile.applyPivot();
            if (position < visible.size()) tile.bind(visible.get(position));
            else tile.bindHidden(hidden.size());
            return tile;
        }
    }

    /** A 16:9 card with a caption under it. The caption shows only on the focused tile. */
    private final class Tile extends LinearLayout {
        final FrameLayout card;
        final ImageView banner;
        final LinearLayout iconBox;
        final ImageView icon;
        final TextView name;
        final TextView caption;
        int position;

        Tile(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setClipChildren(false);
            setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            card = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = MeasureSpec.getSize(widthSpec);
                    super.onMeasure(widthSpec,
                            MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
                }
            };
            card.setBackground(cardBackground.newDrawable());
            card.setForeground(cardForeground.newDrawable());
            addView(card, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            banner = new ImageView(context);
            banner.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            iconBox = new LinearLayout(context);
            iconBox.setOrientation(VERTICAL);
            iconBox.setGravity(Gravity.CENTER);
            iconBox.setPadding(dp(10), 0, dp(10), 0);
            card.addView(iconBox, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            icon = new ImageView(context);
            iconBox.addView(icon, new LayoutParams(iconPx, iconPx));

            name = new TextView(context);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LayoutParams nameParams = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = dp(8);
            iconBox.addView(name, nameParams);

            caption = new TextView(context);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setGravity(Gravity.CENTER);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            caption.setTextColor(new ColorStateList(
                    new int[][] {{android.R.attr.state_selected}, {}},
                    new int[] {Color.WHITE, Color.TRANSPARENT}));
            LayoutParams captionParams = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            captionParams.topMargin = dp(10);
            addView(caption, captionParams);
        }

        void bind(App app) {
            if (app.banner != null) {
                banner.setImageBitmap(app.banner);
                banner.setVisibility(VISIBLE);
                iconBox.setVisibility(GONE);
                // The banner already carries the name. Show it under the card on focus.
                caption.setText(app.label);
            } else {
                banner.setImageDrawable(null);
                banner.setVisibility(GONE);
                iconBox.setVisibility(VISIBLE);
                icon.setVisibility(VISIBLE);
                icon.setImageBitmap(app.icon);
                name.setText(app.label);
                caption.setText(null);
            }
        }

        void bindHidden(int count) {
            banner.setImageDrawable(null);
            banner.setVisibility(GONE);
            iconBox.setVisibility(VISIBLE);
            icon.setVisibility(GONE);
            name.setText("Скрытые · " + count);
            caption.setText(null);
        }

        /**
         * Edge tiles grow inward, so the focused tile never leaves the grid:
         * the left column grows from its left edge, the right column from its
         * right edge, the top row downward.
         */
        void applyPivot() {
            int columns = Math.max(1, grid.getNumColumns());
            int column = position % columns;
            float x = getWidth() / 2f;
            if (column == 0) x = 0;
            else if (column == columns - 1) x = getWidth();
            setPivotX(x);
            setPivotY(position < columns ? 0 : getHeight() / 2f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            applyPivot();
        }

        @Override
        public void setSelected(boolean selected) {
            boolean changed = selected != isSelected();
            super.setSelected(selected);
            if (!changed) return;
            applyPivot();
            float scale = selected ? SCALE_SELECTED : 1f;
            // No Z lift: software rendering ignores it, and the grown tile
            // stays within the grid spacing, so it never covers a neighbour.
            animate().scaleX(scale).scaleY(scale)
                    .setDuration(140)
                    .start();
        }
    }
}
