package com.dexcustom.desktop;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Meu Dex v0.2 - launcher estilo Samsung DeX para a tela virtual do scrcpy.
 *
 * Técnica central:
 *   ActivityOptions.setLaunchDisplayId(id)  -> abre o app NA tela virtual, não no celular
 *   ActivityOptions.setLaunchBounds(rect)   -> posição/tamanho da "janela" (depende do firmware)
 *
 * Canal com o PC: o launcher escreve linhas no logcat (tag "MeuDex") e o PC responde com
 * "am start --es cmd ..." (ver meudex.py). É assim que o botão Voltar e os atalhos Win/Alt+Tab funcionam.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MeuDex";
    private static final String PREFS = "meudex_prefs";
    private static final String KEY_SHORTCUTS = "shortcuts";
    private static final String KEY_WALL = "wallpaper";
    private static final long LAUNCH_DEBOUNCE_MS = 300;
    private static final int BAR_H_DP = 48;
    private static final int REQ_WALL = 77;

    private static final int[][] PRESETS = {
            {0xFF0B1F4D, 0xFF1D4ED8, 0xFF7C3AED},
            {0xFF0F172A, 0xFF1E293B, 0xFF334155},
            {0xFF042F2E, 0xFF0F766E, 0xFF2DD4BF},
            {0xFF3B0764, 0xFFBE185D, 0xFFF97316},
            {0xFF0A0A0A, 0xFF171717, 0xFF262626}};
    private static final String[] PRESET_NAMES =
            {"Azul (padrão)", "Grafite", "Oceano", "Pôr do sol", "Preto"};

    private static class AppEntry {
        String pkg;
        CharSequence label;
        String norm;
        Drawable icon;
    }

    private PackageManager pm;
    private SharedPreferences prefs;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> filtered = new ArrayList<>();
    private final List<String> shortcuts = new ArrayList<>();
    private final List<String> running = new ArrayList<>();
    private String activePkg;

    private FrameLayout rootFrame, desktopArea, drawer;
    private ImageView wallpaperImage;
    private ColumnFlow shortcutContainer;
    private LinearLayout taskbarStrip;
    private TextView emptyHint, batteryText;
    private Glyph batteryGlyph, wifiGlyph;
    private EditText searchBox;
    private DrawerAdapter drawerAdapter;
    private View hoverCard, recentsPanel, volumePanel;

    private long lastLaunch = 0;
    private int cascade = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable showRunnable = () -> { };
    private final Runnable hideRunnable = this::hideHoverCard;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private final BroadcastReceiver batteryRcv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateBattery(i); }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadApps();
        loadShortcuts();
        setContentView(buildUi());
        applyWallpaper();
        refreshShortcuts();
        refreshTaskbar();
        registerStatus();
        Log.i(TAG, "ready display=" + displayId());
        handleCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(batteryRcv); } catch (Exception ignored) { }
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        if (anyOverlayOpen()) closeOverlays();
    }

    /** Comandos vindos do PC: am start --es cmd start|cycle|home */
    private void handleCommand(Intent i) {
        if (i == null) return;
        String cmd = i.getStringExtra("cmd");
        if (cmd == null) return;
        i.removeExtra("cmd");
        rootFrame.post(() -> {
            switch (cmd) {
                case "start": showDrawer(); break;
                case "cycle": cycleWindows(); break;
                case "home": closeOverlays(); break;
            }
        });
    }

    /** Win (solto sozinho) abre o Iniciar; Alt+Tab troca de janela; Esc fecha painéis. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_META_LEFT || k == KeyEvent.KEYCODE_META_RIGHT) {
            if (e.getAction() == KeyEvent.ACTION_UP) toggleDrawer();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_TAB && e.isAltPressed()) {
            cycleWindows();
            return true;
        }
        if (k == KeyEvent.KEYCODE_ESCAPE && anyOverlayOpen()) {
            if (e.getAction() == KeyEvent.ACTION_UP) closeOverlays();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ dados

    private static String norm(CharSequence s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase();
    }

    private void loadApps() {
        allApps.clear();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = ri.loadLabel(pm);
            e.norm = norm(e.label);
            e.icon = ri.loadIcon(pm);
            allApps.add(e);
        }
        Collections.sort(allApps, (a, b) ->
                a.label.toString().compareToIgnoreCase(b.label.toString()));
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : allApps) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    private void loadShortcuts() {
        shortcuts.clear();
        for (String p : prefs.getString(KEY_SHORTCUTS, "").split(",")) {
            if (!p.isEmpty() && find(p) != null) shortcuts.add(p);
        }
    }

    private void saveShortcuts() {
        prefs.edit().putString(KEY_SHORTCUTS, String.join(",", shortcuts)).apply();
    }

    // ------------------------------------------------------------------ UI base

    private View buildUi() {
        rootFrame = new FrameLayout(this);

        wallpaperImage = new ImageView(this);
        wallpaperImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rootFrame.addView(wallpaperImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Área útil do desktop (acima da barra). Também é a referência para as "janelas".
        desktopArea = new FrameLayout(this);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.bottomMargin = dp(BAR_H_DP);
        rootFrame.addView(desktopArea, dlp);
        desktopArea.setOnClickListener(v -> closeOverlays());
        desktopArea.setOnLongClickListener(v -> { showDesktopMenu(); return true; });

        shortcutContainer = new ColumnFlow(this, dp(96), dp(108));
        desktopArea.addView(shortcutContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyHint = new TextView(this);
        emptyHint.setText("Segure um app no menu de apps para colocá-lo aqui.\nSegure o fundo para trocar o papel de parede.");
        emptyHint.setTextColor(0xAAFFFFFF);
        emptyHint.setTextSize(14);
        emptyHint.setGravity(Gravity.CENTER);
        desktopArea.addView(emptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        buildDrawer();

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_H_DP), Gravity.BOTTOM);
        rootFrame.addView(buildTaskbar(), blp);
        return rootFrame;
    }

    // ------------------------------------------------------------------ barra de tarefas (estilo DeX)

    private View buildTaskbar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xE6101216);

        // Esquerda: apps | busca | recentes, início, voltar
        LinearLayout left = row();
        left.setPadding(dp(10), 0, 0, 0);
        addTool(left, Glyph.GRID, v -> toggleDrawer());
        addTool(left, Glyph.SEARCH, v -> showDrawer());
        View div = new View(this);
        div.setBackgroundColor(0x44FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(20));
        divLp.setMargins(dp(8), 0, dp(8), 0);
        left.addView(div, divLp);
        addTool(left, Glyph.RECENTS, v -> toggleRecents());
        addTool(left, Glyph.HOME, v -> { closeOverlays(); bringLauncherToFront(); });
        addTool(left, Glyph.BACK, v -> {
            if (anyOverlayOpen()) closeOverlays();
            else Log.i(TAG, "cmd=back display=" + displayId()); // o PC injeta o Voltar na tela virtual
        });
        bar.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // Centro: apps abertos
        taskbarStrip = row();
        bar.addView(taskbarStrip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL));

        // Direita: bandeja
        LinearLayout right = row();
        right.setPadding(0, 0, dp(14), 0);
        addTool(right, Glyph.VOLUME, v -> toggleVolume());
        wifiGlyph = addTool(right, Glyph.WIFI, null);
        batteryGlyph = new Glyph(this, Glyph.BATTERY);
        right.addView(batteryGlyph, new LinearLayout.LayoutParams(dp(34), dp(40)));
        batteryText = new TextView(this);
        batteryText.setTextColor(Color.WHITE);
        batteryText.setTextSize(12);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btLp.rightMargin = dp(12);
        right.addView(batteryText, btLp);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(14);
        time.setGravity(Gravity.END);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEE, dd/MM");
        date.setFormat12Hour("EEE, dd/MM");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(10);
        date.setGravity(Gravity.END);
        clockBox.addView(time);
        clockBox.addView(date);
        right.addView(clockBox);
        bar.addView(right, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        return bar;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private Glyph addTool(LinearLayout parent, int type, View.OnClickListener l) {
        Glyph g = new Glyph(this, type);
        hoverBg(g, 0x26FFFFFF, dp(8));
        if (l != null) g.setOnClickListener(l);
        parent.addView(g, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return g;
    }

    private void hoverBg(View v, int color, int radius) {
        v.setOnHoverListener((view, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_HOVER_ENTER) view.setBackground(roundRect(color, radius));
            else if (a == MotionEvent.ACTION_HOVER_EXIT) view.setBackground(null);
            return false;
        });
    }

    private void refreshTaskbar() {
        taskbarStrip.removeAllViews();
        for (String pkg : running) {
            AppEntry e = find(pkg);
            if (e == null) continue;
            boolean act = pkg.equals(activePkg);

            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);

            View ind = new View(this);
            ind.setBackground(roundRect(act ? 0xFFFFFFFF : 0x99FFFFFF, dp(2)));
            slot.addView(ind, new LinearLayout.LayoutParams(dp(act ? 16 : 6), dp(3)));

            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            iv.setPadding(dp(3), dp(3), dp(3), dp(3));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(34), dp(34));
            ilp.topMargin = dp(3);
            slot.addView(iv, ilp);

            slot.setOnClickListener(v -> launch(pkg, null));
            slot.setOnLongClickListener(v -> { showWindowMenu(e); return true; });
            slot.setOnHoverListener((v, ev) -> {
                int a = ev.getAction();
                if (a == MotionEvent.ACTION_HOVER_ENTER) {
                    v.setBackground(roundRect(0x26FFFFFF, dp(8)));
                    handler.removeCallbacks(hideRunnable);
                    handler.removeCallbacks(showRunnable);
                    showRunnable = () -> showHoverCard(v, e);
                    handler.postDelayed(showRunnable, 200);
                } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                    v.setBackground(null);
                    handler.removeCallbacks(showRunnable);
                    scheduleHide();
                }
                return false;
            });
            taskbarStrip.addView(slot, new LinearLayout.LayoutParams(dp(46),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ------------------------------------------------------------------ cartão ao passar o mouse

    private final View.OnHoverListener cardHover = (v, ev) -> {
        int a = ev.getAction();
        if (a == MotionEvent.ACTION_HOVER_ENTER) handler.removeCallbacks(hideRunnable);
        else if (a == MotionEvent.ACTION_HOVER_EXIT) scheduleHide();
        return false;
    };

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, 350);
    }

    private void hideHoverCard() {
        handler.removeCallbacks(showRunnable);
        if (hoverCard != null) {
            rootFrame.removeView(hoverCard);
            hoverCard = null;
        }
    }

    private void showHoverCard(View anchor, AppEntry e) {
        hideHoverCard();
        int cardW = dp(220);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(10));
        card.setBackground(roundRect(0xF2181C26, dp(12)));
        card.setOnHoverListener(cardHover);
        card.setOnClickListener(v -> launch(e.pkg, null));

        LinearLayout head = row();
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(e.icon);
        head.addView(ic, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = new TextView(this);
        t.setText(e.label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(8), 0, dp(4), 0);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextColor(Color.WHITE);
        x.setTextSize(14);
        x.setPadding(dp(8), dp(2), dp(8), dp(2));
        x.setOnHoverListener(cardHover);
        x.setOnClickListener(v -> closeApp(e.pkg));
        head.addView(x);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Obs.: um app normal não consegue capturar a imagem da janela de outro app, então
        // o "preview" mostra o ícone grande. Miniatura real exigiria captura via PC (adb screencap).
        FrameLayout prev = new FrameLayout(this);
        prev.setBackground(roundRect(0xFF0E1118, dp(8)));
        ImageView big = new ImageView(this);
        big.setImageDrawable(e.icon);
        prev.addView(big, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        plp.topMargin = dp(8);
        card.addView(prev, plp);

        int[] a = new int[2], r = new int[2];
        anchor.getLocationOnScreen(a);
        rootFrame.getLocationOnScreen(r);
        int cx = a[0] - r[0] + anchor.getWidth() / 2;
        int left = Math.max(dp(8), Math.min(cx - cardW / 2, rootFrame.getWidth() - cardW - dp(8)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = left;
        lp.bottomMargin = dp(BAR_H_DP + 6);
        hoverCard = card;
        rootFrame.addView(card, lp);
    }

    // ------------------------------------------------------------------ menu de apps + busca

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xE60B0F1A);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        drawer.setOnClickListener(v -> hideDrawer());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(48), dp(28), dp(48), dp(16));

        searchBox = new EditText(this);
        searchBox.setHint("Pesquisar apps");
        searchBox.setHintTextColor(0x99FFFFFF);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setTextSize(16);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setBackground(roundRect(0x33FFFFFF, dp(24)));
        searchBox.setPadding(dp(20), 0, dp(20), 0);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
        });
        searchBox.setOnEditorActionListener((v, action, ev) -> { launchFirstResult(); return true; });
        col.addView(searchBox, new LinearLayout.LayoutParams(dp(420), dp(46)));

        drawerAdapter = new DrawerAdapter();
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(104));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(10));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(drawerAdapter);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = dp(24);
        col.addView(grid, glp);

        drawer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.bottomMargin = dp(BAR_H_DP);
        rootFrame.addView(drawer, lp);
    }

    private void applyFilter(String q) {
        String n = norm(q.trim());
        filtered.clear();
        for (AppEntry e : allApps) if (n.isEmpty() || e.norm.contains(n)) filtered.add(e);
        drawerAdapter.notifyDataSetChanged();
    }

    private void launchFirstResult() {
        if (!filtered.isEmpty()) launch(filtered.get(0).pkg, cascadeBounds());
    }

    private boolean drawerOpen() { return drawer.getVisibility() == View.VISIBLE; }

    private void toggleDrawer() { if (drawerOpen()) hideDrawer(); else showDrawer(); }

    private void showDrawer() {
        closeOverlays();
        searchBox.setText("");
        applyFilter("");
        drawer.setVisibility(View.VISIBLE);
        searchBox.setFocusableInTouchMode(true);
        searchBox.requestFocus();
    }

    private void hideDrawer() {
        if (!drawerOpen()) return;
        drawer.setVisibility(View.GONE);
        searchBox.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ recentes e volume

    private boolean anyOverlayOpen() {
        return drawerOpen() || recentsPanel != null || volumePanel != null;
    }

    private void closeOverlays() {
        hideDrawer();
        hideHoverCard();
        if (recentsPanel != null) { rootFrame.removeView(recentsPanel); recentsPanel = null; }
        if (volumePanel != null) { rootFrame.removeView(volumePanel); volumePanel = null; }
    }

    private void toggleRecents() {
        boolean wasOpen = recentsPanel != null;
        closeOverlays();
        if (wasOpen) return;
        if (running.isEmpty()) {
            Toast.makeText(this, "Nenhum app aberto", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(roundRect(0xF0161A24, dp(16)));
        for (String pkg : new ArrayList<>(running)) {
            AppEntry e = find(pkg);
            if (e == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(8));
            card.setBackground(roundRect(0x22FFFFFF, dp(12)));
            card.setOnClickListener(v -> launch(pkg, null));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            card.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView tv = new TextView(this);
            tv.setText(e.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(12);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            card.addView(tv, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView close = new TextView(this);
            close.setText("Fechar");
            close.setTextColor(0xFFFF8A80);
            close.setTextSize(11);
            close.setPadding(dp(8), dp(6), dp(8), dp(2));
            close.setOnClickListener(v -> closeApp(pkg));
            card.addView(close);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(8);
            panel.addView(card, clp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(BAR_H_DP + 10);
        recentsPanel = panel;
        rootFrame.addView(panel, lp);
    }

    private void toggleVolume() {
        boolean wasOpen = volumePanel != null;
        closeOverlays();
        if (wasOpen) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        LinearLayout pop = row();
        pop.setPadding(dp(14), dp(8), dp(18), dp(8));
        pop.setBackground(roundRect(0xF0161A24, dp(14)));
        pop.addView(new Glyph(this, Glyph.VOLUME), new LinearLayout.LayoutParams(dp(32), dp(32)));
        SeekBar sb = new SeekBar(this);
        sb.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        sb.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        pop.addView(sb, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(150);
        lp.bottomMargin = dp(BAR_H_DP + 8);
        volumePanel = pop;
        rootFrame.addView(pop, lp);
    }

    // ------------------------------------------------------------------ status (bateria / rede)

    private void registerStatus() {
        updateBattery(registerReceiver(batteryRcv, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { updateNet(); }
                @Override public void onLost(Network n) { updateNet(); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { updateNet(); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception ex) {
            Log.w(TAG, "rede: " + ex);
        }
        updateNet();
    }

    private void updateBattery(Intent i) {
        if (i == null) return;
        int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (lv < 0 || sc <= 0) return;
        int pct = lv * 100 / sc;
        batteryText.setText(pct + "%");
        batteryGlyph.setLevel(pct);
    }

    private void updateNet() {
        runOnUiThread(() -> {
            boolean wifi = false;
            try {
                Network n = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
                wifi = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } catch (Exception ignored) { }
            wifiGlyph.setDim(!wifi);
        });
    }

    // ------------------------------------------------------------------ desktop: ícones, papel de parede, arquivos

    private View makeIconCell(AppEntry e) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(6), dp(4), dp(4));
        hoverBg(cell, 0x22FFFFFF, dp(10));

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(e.icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView tv = new TextView(this);
        tv.setText(e.label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        cell.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private void refreshShortcuts() {
        shortcutContainer.removeAllViews();
        for (String pkg : shortcuts) {
            AppEntry e = find(pkg);
            if (e == null) continue;
            View cell = makeIconCell(e);
            cell.setOnClickListener(v -> launch(pkg, cascadeBounds()));
            cell.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(e.label)
                        .setItems(new String[]{"Remover do desktop"}, (d, which) -> {
                            shortcuts.remove(pkg);
                            saveShortcuts();
                            refreshShortcuts();
                        }).show();
                return true;
            });
            shortcutContainer.addView(cell);
        }
        emptyHint.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showDesktopMenu() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Papel de parede…", "Arquivos", "Todos os apps"}, (d, which) -> {
                    if (which == 0) showWallpaperMenu();
                    else if (which == 1) openFiles();
                    else showDrawer();
                }).show();
    }

    private void showWallpaperMenu() {
        String[] items = new String[PRESET_NAMES.length + 1];
        System.arraycopy(PRESET_NAMES, 0, items, 0, PRESET_NAMES.length);
        items[PRESET_NAMES.length] = "Escolher imagem…";
        new AlertDialog.Builder(this)
                .setTitle("Papel de parede")
                .setItems(items, (d, which) -> {
                    if (which < PRESET_NAMES.length) {
                        prefs.edit().putString(KEY_WALL, "preset:" + which).apply();
                        applyWallpaper();
                    } else {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                        try { startActivityForResult(i, REQ_WALL); }
                        catch (Exception ex) { toast("Sem seletor de imagens: " + ex.getMessage()); }
                    }
                }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_WALL || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o2);
            }
            if (bmp == null) { toast("Não consegui ler a imagem"); return; }
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "wallpaper.jpg"))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            prefs.edit().putString(KEY_WALL, "file").apply();
            applyWallpaper();
        } catch (Exception ex) {
            toast("Erro no papel de parede: " + ex.getMessage());
        }
    }

    private void applyWallpaper() {
        String w = prefs.getString(KEY_WALL, "preset:0");
        if (w.equals("file")) {
            File f = new File(getFilesDir(), "wallpaper.jpg");
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            if (b != null) {
                wallpaperImage.setImageBitmap(b);
                wallpaperImage.setVisibility(View.VISIBLE);
                return;
            }
            w = "preset:0";
        }
        int idx = 0;
        try { idx = Integer.parseInt(w.substring(7)); } catch (Exception ignored) { }
        if (idx < 0 || idx >= PRESETS.length) idx = 0;
        wallpaperImage.setVisibility(View.GONE);
        rootFrame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, PRESETS[idx]));
    }

    private void openFiles() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                "vnd.android.document/root");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId());
        try {
            startActivity(i, opts.toBundle());
        } catch (Exception ex) {
            try {
                Intent d = new Intent("android.intent.action.VIEW_DOWNLOADS");
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(d, opts.toBundle());
            } catch (Exception ex2) {
                toast("Não achei um app de arquivos");
            }
        }
    }

    // ------------------------------------------------------------------ janelas

    private void showWindowMenu(AppEntry e) {
        // Controles de janela (mesmos modos do DroidUP: normal, compacto, maximizado, painéis 1/2 e 1/3)
        String[] items = {"Trazer para frente", "Tamanho normal", "Compacto (formato celular)", "Maximizar",
                "Metade esquerda", "Metade direita", "1/3 esquerdo", "1/3 centro", "1/3 direito", "Fechar"};
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: launch(e.pkg, null); break;
                        case 1: launch(e.pkg, centeredBounds(0.62f, 0.78f)); break;
                        case 2: launch(e.pkg, centeredBounds(0.28f, 0.82f)); break;
                        case 3: launch(e.pkg, areaBounds(0f, 1f)); break;
                        case 4: launch(e.pkg, areaBounds(0f, 0.5f)); break;
                        case 5: launch(e.pkg, areaBounds(0.5f, 1f)); break;
                        case 6: launch(e.pkg, areaBounds(0f, 1f / 3f)); break;
                        case 7: launch(e.pkg, areaBounds(1f / 3f, 2f / 3f)); break;
                        case 8: launch(e.pkg, areaBounds(2f / 3f, 1f)); break;
                        case 9: closeApp(e.pkg); break;
                    }
                }).show();
    }

    private void cycleWindows() {
        if (running.isEmpty()) return;
        int next = (running.indexOf(activePkg) + 1) % running.size();
        lastLaunch = 0;
        launch(running.get(next), null);
    }

    // ------------------------------------------------------------------ lançamento

    private int displayId() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.Display d = getDisplay();
                if (d != null) return d.getDisplayId();
            } catch (Exception ignored) { }
        }
        @SuppressWarnings("deprecation")
        int id = getWindowManager().getDefaultDisplay().getDisplayId();
        return id;
    }

    private Rect areaBounds(float from, float to) {
        int[] loc = new int[2];
        desktopArea.getLocationOnScreen(loc);
        int w = desktopArea.getWidth(), h = desktopArea.getHeight();
        return new Rect(loc[0] + (int) (w * from), loc[1], loc[0] + (int) (w * to), loc[1] + h);
    }

    private Rect centeredBounds(float fw, float fh) {
        int[] loc = new int[2];
        desktopArea.getLocationOnScreen(loc);
        int w = desktopArea.getWidth(), h = desktopArea.getHeight();
        int bw = (int) (w * fw), bh = (int) (h * fh);
        int left = loc[0] + (w - bw) / 2, top = loc[1] + (h - bh) / 2;
        return new Rect(left, top, left + bw, top + bh);
    }

    private Rect cascadeBounds() {
        int[] loc = new int[2];
        desktopArea.getLocationOnScreen(loc);
        int w = desktopArea.getWidth(), h = desktopArea.getHeight();
        int off = dp(36) * (cascade++ % 6);
        int left = loc[0] + dp(60) + off;
        int top = loc[1] + dp(24) + off;
        int right = Math.min(left + (int) (w * 0.62f), loc[0] + w);
        int bottom = Math.min(top + (int) (h * 0.78f), loc[1] + h);
        return new Rect(left, top, right, bottom);
    }

    private void launch(String pkg, Rect bounds) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < LAUNCH_DEBOUNCE_MS) return;
        lastLaunch = now;
        closeOverlays();

        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            toast("Não foi possível abrir este app");
            return;
        }
        // Primeira abertura: MULTIPLE_TASK cria uma tarefa NOVA nesta tela, em vez de puxar para cá
        // a tarefa do mesmo app que esteja aberta no celular (mantém celular e desktop separados).
        // Configurações do sistema ficam com tela preta na tela virtual: abre no celular (como o DroidUP).
        boolean onPhone = pkg.equals("com.android.settings");
        boolean first = !running.contains(pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | (first && !onPhone ? Intent.FLAG_ACTIVITY_MULTIPLE_TASK : 0));

        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(onPhone ? 0 : displayId());
        if (bounds != null && !onPhone) opts.setLaunchBounds(bounds);
        if (onPhone) toast("Configurações abrem na tela do celular");

        try {
            startActivity(intent, opts.toBundle());
            if (onPhone) return;
            if (first) running.add(pkg);
            activePkg = pkg;
            refreshTaskbar();
        } catch (Exception ex) {
            toast("Falha ao abrir: " + ex.getMessage());
        }
    }

    private void bringLauncherToFront() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId());
        startActivity(i, opts.toBundle());
    }

    /** killBackgroundProcesses só mata apps em segundo plano: primeiro o launcher vai para frente. */
    private void closeApp(String pkg) {
        closeOverlays();
        bringLauncherToFront();
        handler.postDelayed(() -> {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(pkg);
            running.remove(pkg);
            if (pkg.equals(activePkg)) activePkg = null;
            refreshTaskbar();
        }, 450);
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // ------------------------------------------------------------------ componentes

    private class DrawerAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int i) { return filtered.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry e = filtered.get(position);
            View cell = makeIconCell(e);
            cell.setOnClickListener(v -> launch(e.pkg, cascadeBounds()));
            cell.setOnLongClickListener(v -> {
                if (shortcuts.contains(e.pkg)) {
                    toast("Já está no desktop");
                } else {
                    shortcuts.add(e.pkg);
                    saveShortcuts();
                    refreshShortcuts();
                    toast("Adicionado ao desktop: " + e.label);
                }
                return true;
            });
            return cell;
        }
    }

    /** Ícones do desktop fluem de cima para baixo e quebram para a próxima coluna. */
    private static class ColumnFlow extends ViewGroup {
        private final int cellW, cellH;

        ColumnFlow(Context c, int cellW, int cellH) {
            super(c);
            this.cellW = cellW;
            this.cellH = cellH;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(
                        MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int perColumn = Math.max(1, (b - t) / cellH);
            for (int i = 0; i < getChildCount(); i++) {
                int col = i / perColumn, row = i % perColumn;
                getChildAt(i).layout(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH);
            }
        }
    }

    /** Ícones de linha brancos desenhados à mão (estilo barra do DeX). */
    private static class Glyph extends View {
        static final int GRID = 0, SEARCH = 1, RECENTS = 2, HOME = 3, BACK = 4,
                VOLUME = 5, WIFI = 6, BATTERY = 7;
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private int level = 100;
        private boolean dim = false;

        Glyph(Context c, int type) {
            super(c);
            this.type = type;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setLevel(int l) { level = Math.max(0, Math.min(100, l)); invalidate(); }

        void setDim(boolean d) { dim = d; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 24f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setColor(Color.WHITE);
            p.setAlpha(dim ? 90 : 255);
            p.setStrokeWidth(1.8f * u);
            switch (type) {
                case GRID:
                    p.setStyle(Paint.Style.FILL);
                    for (int i = -1; i <= 1; i++)
                        for (int j = -1; j <= 1; j++)
                            c.drawCircle(cx + i * 5.5f * u, cy + j * 5.5f * u, 1.7f * u, p);
                    break;
                case SEARCH:
                    p.setStyle(Paint.Style.STROKE);
                    c.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6f * u, p);
                    c.drawLine(cx + 2.8f * u, cy + 2.8f * u, cx + 7.5f * u, cy + 7.5f * u, p);
                    break;
                case RECENTS:
                    p.setStyle(Paint.Style.STROKE);
                    for (int i = -1; i <= 1; i++)
                        c.drawLine(cx + i * 5f * u, cy - 6f * u, cx + i * 5f * u, cy + 6f * u, p);
                    break;
                case HOME:
                    p.setStyle(Paint.Style.STROKE);
                    c.drawCircle(cx, cy, 6.5f * u, p);
                    break;
                case BACK:
                    p.setStyle(Paint.Style.STROKE);
                    path.reset();
                    path.moveTo(cx + 3f * u, cy - 6.5f * u);
                    path.lineTo(cx - 3f * u, cy);
                    path.lineTo(cx + 3f * u, cy + 6.5f * u);
                    c.drawPath(path, p);
                    break;
                case VOLUME:
                    p.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx - 9f * u, cy - 3f * u);
                    path.lineTo(cx - 5f * u, cy - 3f * u);
                    path.lineTo(cx, cy - 7f * u);
                    path.lineTo(cx, cy + 7f * u);
                    path.lineTo(cx - 5f * u, cy + 3f * u);
                    path.lineTo(cx - 9f * u, cy + 3f * u);
                    path.close();
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    for (int k = 1; k <= 2; k++) {
                        float rad = 4f * k * u;
                        r.set(cx + u - rad, cy - rad, cx + u + rad, cy + rad);
                        c.drawArc(r, -45, 90, false, p);
                    }
                    break;
                case WIFI:
                    p.setStyle(Paint.Style.STROKE);
                    for (int k = 1; k <= 3; k++) {
                        float rad = 4f * k * u;
                        r.set(cx - rad, cy + 5f * u - rad, cx + rad, cy + 5f * u + rad);
                        c.drawArc(r, 225, 90, false, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy + 5f * u, 1.6f * u, p);
                    break;
                case BATTERY:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.4f * u);
                    r.set(cx - 9f * u, cy - 4.5f * u, cx + 8f * u, cy + 4.5f * u);
                    c.drawRoundRect(r, 2f * u, 2f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRect(cx + 8.8f * u, cy - 2f * u, cx + 10.2f * u, cy + 2f * u, p);
                    float fill = (r.width() - 4f * u) * level / 100f;
                    if (fill > 0)
                        c.drawRect(r.left + 2f * u, cy - 2.5f * u, r.left + 2f * u + fill, cy + 2.5f * u, p);
                    break;
            }
        }
    }
}
