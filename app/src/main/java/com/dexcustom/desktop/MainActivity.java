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
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Meu Dex v0.4 - launcher estilo Samsung DeX para a tela virtual do scrcpy.
 *
 * Novidades da v0.4 (desempenho + janelas):
 *  - Abertura bem mais rapida: os icones sao carregados em segundo plano (uma thread) e guardados como
 *    bitmaps pequenos; antes todos eram carregados na thread da interface antes de mostrar a tela.
 *  - Menu de apps fluido: a grade reaproveita as views (convertView); antes criava uma view nova por rolagem.
 *  - Regex pre-compilados; menos alocacoes. Avisa o PC da area util do desktop (linha "area ...").
 *  - Menu de janela maior: metades, quartos, centralizar, grande e "Tamanho personalizado...".
 *  - Launcher nao e mais redimensionavel (como o DroidUP): nunca vira uma janelinha na tela virtual.
 *
 * Novidades da v0.3:
 *  - Barra de tarefas no layout do DeX: navegacao (recentes/inicio/voltar) a esquerda, busca + apps + favoritos
 *    + apps abertos no centro, bandeja (volume/wifi/bateria/relogio) a direita.
 *  - Menu de apps em painel flutuante (como o DeX) com busca embaixo; digitar em qualquer lugar ja pesquisa.
 *  - Apps abrem em JANELA (freeform) dentro da area acima da barra; maximizar NAO cobre a barra de tarefas.
 *    Modos por app: janela / maximizado / tela cheia. O modo escolhido e lembrado.
 *  - Versao visivel (canto do desktop e rodape do menu de apps).
 *  - Desktop, favoritos da barra e modos dos apps sao enviados ao PC (meudex_apps.json) e restaurados
 *    se o launcher for reinstalado.
 *
 * Canal com o PC: o launcher escreve linhas no logcat (tag "MeuDex") e o PC responde com
 * "am start --es cmd ..." (ver meudex.py). O PC tambem executa as acoes que um app comum nao pode fazer:
 * forcar modo janela, redimensionar uma janela existente e fechar (force-stop) um app.
 */
public class MainActivity extends Activity {

    static final String VERSION = "0.4";

    private static final String TAG = "MeuDex";
    private static final String PREFS = "meudex_prefs";
    private static final String KEY_SHORTCUTS = "shortcuts";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_MODES = "modes";
    private static final String KEY_WALL = "wallpaper";
    private static final long LAUNCH_DEBOUNCE_MS = 300;
    private static final int BAR_H_DP = 52;
    private static final int REQ_WALL = 77;

    // Modo de abertura de cada app
    private static final int MODE_WINDOW = 0, MODE_MAX = 1, MODE_FULL = 2;
    // Windowing modes do Android (WindowConfiguration)
    private static final int WM_FULLSCREEN = 1, WM_FREEFORM = 5;

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
        ResolveInfo ri;
        volatile Drawable icon;   // carregado em segundo plano (null = ainda nao carregou)
    }

    private static final Pattern PKG_PATTERN = Pattern.compile("[A-Za-z0-9._]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
    private static final Pattern WALL_PATTERN = Pattern.compile("[A-Za-z0-9:_]+");
    private static final Drawable PLACEHOLDER = new ColorDrawable(0x22FFFFFF);
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed = false;
    private String lastArea = "";

    private PackageManager pm;
    private SharedPreferences prefs;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> filtered = new ArrayList<>();
    private final List<String> shortcuts = new ArrayList<>();   // ícones do desktop
    private final List<String> pinned = new ArrayList<>();      // favoritos da barra de tarefas
    private final Map<String, Integer> modes = new HashMap<>(); // modo de abertura lembrado por app
    private final List<String> running = new ArrayList<>();
    private String activePkg;
    private boolean desktopShown = false;

    private FrameLayout rootFrame, desktopArea, startScrim;
    private LinearLayout startPanel;
    private ImageView wallpaperImage;
    private BottomFlow iconFlow;
    private LinearLayout taskbarStrip;
    private TextView emptyHint, batteryBadge;
    private Glyph wifiGlyph;
    private EditText searchBox;
    private GridView startGrid;
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
        loadState();
        setContentView(buildUi());
        applyWallpaper();
        refreshDesktopIcons();
        refreshTaskbar();
        registerStatus();
        // O PC le esta linha: id da tela virtual + versao do launcher instalado.
        Log.i(TAG, "ready display=" + displayId() + " version=" + VERSION);
        desktopArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> logArea());
        preloadIcons();
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
        destroyed = true;
        bg.shutdownNow();
        handler.removeCallbacksAndMessages(null);
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

    /** Comandos vindos do PC: am start --es cmd start|cycle|home|closeactive|import [--es data ...] */
    private void handleCommand(Intent i) {
        if (i == null) return;
        final String cmd = i.getStringExtra("cmd");
        final String data = i.getStringExtra("data");
        if (cmd == null) return;
        i.removeExtra("cmd");
        i.removeExtra("data");
        rootFrame.post(() -> {
            switch (cmd) {
                case "start": toggleDrawer(); break;
                case "cycle": cycleWindows(); break;
                case "home": showDesktop(); break;
                case "closeactive": if (activePkg != null) closeApp(activePkg); break;
                case "import": if (data != null) importState(data); break;
                default: break;
            }
        });
    }

    /** Win abre o Iniciar; Alt+Tab troca de janela; Esc fecha painéis; digitar já pesquisa apps. */
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
        // Digitar com o desktop em foco abre o menu de apps já com o texto na busca.
        if (e.getAction() == KeyEvent.ACTION_DOWN && !drawerOpen()
                && !e.isCtrlPressed() && !e.isAltPressed() && !e.isMetaPressed()) {
            int u = e.getUnicodeChar();
            if (u > 32 && !Character.isISOControl(u)) {
                showDrawer();
                String s = new String(Character.toChars(u));
                searchBox.setText(s);
                searchBox.setSelection(s.length());
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ dados

    private static String norm(CharSequence s) {
        return DIACRITICS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase();
    }

    private static boolean safePkg(String p) {
        return PKG_PATTERN.matcher(p).matches();
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
            e.ri = ri;
            allApps.add(e);
        }
        Collections.sort(allApps, (a, b) ->
                a.label.toString().compareToIgnoreCase(b.label.toString()));
    }

    private Drawable iconOf(AppEntry e) {
        Drawable d = e.icon;
        return d != null ? d : PLACEHOLDER;
    }

    private Drawable toBitmapIcon(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(c);
        return new BitmapDrawable(getResources(), b);
    }

    /** Carrega os icones fora da thread da interface: primeiro desktop e barra, depois o resto. */
    private void preloadIcons() {
        final List<AppEntry> order = new ArrayList<>();
        for (String p : shortcuts) { AppEntry e = find(p); if (e != null && !order.contains(e)) order.add(e); }
        for (String p : pinned) { AppEntry e = find(p); if (e != null && !order.contains(e)) order.add(e); }
        final int first = order.size();
        for (AppEntry e : allApps) if (!order.contains(e)) order.add(e);
        final int total = order.size();
        bg.execute(() -> {
            int n = 0;
            for (AppEntry e : order) {
                if (destroyed) return;
                if (e.icon == null && e.ri != null) {
                    try { e.icon = toBitmapIcon(e.ri.loadIcon(pm), 128); }
                    catch (Throwable ignored) { }
                }
                n++;
                if (n == first || n % 40 == 0 || n == total) handler.post(this::onIconsLoaded);
            }
        });
    }

    private void onIconsLoaded() {
        if (destroyed) return;
        drawerAdapter.notifyDataSetChanged();
        refreshDesktopIcons();
        refreshTaskbar();
    }

    /** Avisa o PC da area util (acima da barra) para ele limitar mover/redimensionar janelas. */
    private void logArea() {
        if (desktopArea == null || desktopArea.getWidth() == 0) return;
        int[] loc = new int[2];
        desktopArea.getLocationOnScreen(loc);
        String a = "area display=" + displayId() + " b=" + loc[0] + "," + loc[1] + ","
                + (loc[0] + desktopArea.getWidth()) + "," + (loc[1] + desktopArea.getHeight())
                + " size=" + rootFrame.getWidth() + "x" + rootFrame.getHeight();
        if (a.equals(lastArea)) return;
        lastArea = a;
        Log.i(TAG, a);
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : allApps) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    // ---- estado salvo (desktop, favoritos, modos) ----

    private static void fillList(List<String> out, String csv) {
        out.clear();
        for (String p : csv.split(",")) {
            p = p.trim();
            if (!p.isEmpty() && safePkg(p) && !out.contains(p)) out.add(p);
        }
    }

    private void fillModes(String csv) {
        modes.clear();
        for (String s : csv.split(",")) {
            int i = s.lastIndexOf(':');
            if (i <= 0) continue;
            String pkg = s.substring(0, i);
            if (!safePkg(pkg)) continue;
            try { modes.put(pkg, Integer.parseInt(s.substring(i + 1))); }
            catch (NumberFormatException ignored) { }
        }
    }

    private String modesCsv() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> en : modes.entrySet())
            if (en.getValue() != MODE_WINDOW) parts.add(en.getKey() + ":" + en.getValue());
        return TextUtils.join(",", parts);
    }

    private void loadState() {
        fillList(shortcuts, prefs.getString(KEY_SHORTCUTS, ""));
        fillList(pinned, prefs.getString(KEY_PINNED, ""));
        fillModes(prefs.getString(KEY_MODES, ""));
    }

    /** Formato simples (só caracteres seguros para o shell do adb): d=a,b@p=c@m=x:1@w=preset:0 */
    private String encodeState() {
        String w = prefs.getString(KEY_WALL, "preset:0");
        if (!WALL_PATTERN.matcher(w).matches()) w = "preset:0";
        return "d=" + TextUtils.join(",", shortcuts) + "@p=" + TextUtils.join(",", pinned)
                + "@m=" + modesCsv() + "@w=" + w;
    }

    private void logState() {
        Log.i(TAG, "state " + encodeState());
    }

    private void saveState() {
        prefs.edit()
                .putString(KEY_SHORTCUTS, TextUtils.join(",", shortcuts))
                .putString(KEY_PINNED, TextUtils.join(",", pinned))
                .putString(KEY_MODES, modesCsv())
                .apply();
        logState(); // o PC guarda uma cópia em meudex_apps.json
    }

    /** Restaura o que o PC guardou (útil depois de reinstalar o launcher). */
    private void importState(String s) {
        for (String part : s.split("@")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            String k = part.substring(0, i), v = part.substring(i + 1);
            switch (k) {
                case "d": fillList(shortcuts, v); break;
                case "p": fillList(pinned, v); break;
                case "m": fillModes(v); break;
                case "w":
                    if (v.matches("preset:\\d+")) prefs.edit().putString(KEY_WALL, v).apply();
                    break;
                default: break;
            }
        }
        prefs.edit()
                .putString(KEY_SHORTCUTS, TextUtils.join(",", shortcuts))
                .putString(KEY_PINNED, TextUtils.join(",", pinned))
                .putString(KEY_MODES, modesCsv())
                .apply();
        applyWallpaper();
        refreshDesktopIcons();
        refreshTaskbar();
        Log.i(TAG, "imported d=" + shortcuts.size() + " p=" + pinned.size());
    }

    // ------------------------------------------------------------------ UI base

    private View buildUi() {
        rootFrame = new FrameLayout(this);

        wallpaperImage = new ImageView(this);
        wallpaperImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rootFrame.addView(wallpaperImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Área útil do desktop (acima da barra). Também é a referência para as "janelas":
        // maximizar usa exatamente esta área, então a barra de tarefas nunca fica coberta.
        desktopArea = new FrameLayout(this);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.bottomMargin = dp(BAR_H_DP);
        rootFrame.addView(desktopArea, dlp);
        desktopArea.setOnClickListener(v -> closeOverlays());
        desktopArea.setOnLongClickListener(v -> { showDesktopMenu(); return true; });

        // Ícones do desktop: fileira centralizada na parte de baixo, como no DeX.
        iconFlow = new BottomFlow(this, dp(100), dp(108), dp(14));
        desktopArea.addView(iconFlow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyHint = new TextView(this);
        emptyHint.setText("Segure um app no menu de apps para colocá-lo aqui.\nSegure o fundo para trocar o papel de parede.");
        emptyHint.setTextColor(0xAAFFFFFF);
        emptyHint.setTextSize(14);
        emptyHint.setGravity(Gravity.CENTER);
        desktopArea.addView(emptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Marca de versão no canto: confirma de relance que o APK novo está instalado.
        TextView ver = new TextView(this);
        ver.setText("Meu Dex v" + VERSION);
        ver.setTextColor(0x99FFFFFF);
        ver.setTextSize(11);
        ver.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        vlp.topMargin = dp(8);
        vlp.rightMargin = dp(14);
        desktopArea.addView(ver, vlp);

        buildStart();

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_H_DP), Gravity.BOTTOM);
        rootFrame.addView(buildTaskbar(), blp);
        return rootFrame;
    }

    // ------------------------------------------------------------------ barra de tarefas (estilo DeX)

    private View buildTaskbar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xF0121417);

        // Esquerda: navegação (recentes, início, voltar)
        LinearLayout left = row();
        left.setPadding(dp(14), 0, 0, 0);
        addTool(left, Glyph.RECENTS, v -> toggleRecents());
        addTool(left, Glyph.HOME, v -> showDesktop());
        addTool(left, Glyph.BACK, v -> {
            if (anyOverlayOpen()) closeOverlays();
            else Log.i(TAG, "cmd=back display=" + displayId()); // o PC injeta o Voltar na tela virtual
        });
        bar.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // Centro: busca | apps | favoritos + apps abertos
        LinearLayout center = row();

        LinearLayout pill = row();
        pill.setPadding(dp(10), 0, dp(14), 0);
        pill.setBackground(roundRect(0x26FFFFFF, dp(20)));
        pill.addView(new Glyph(this, Glyph.SEARCH), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView pillTxt = new TextView(this);
        pillTxt.setText("Pesquisar apps");
        pillTxt.setTextColor(0xCCFFFFFF);
        pillTxt.setTextSize(13);
        pillTxt.setPadding(dp(6), 0, 0, 0);
        pill.addView(pillTxt);
        pill.setOnClickListener(v -> showDrawer());
        hoverBg(pill, 0x3AFFFFFF, dp(20));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(dp(176), dp(36));
        pillLp.rightMargin = dp(8);
        center.addView(pill, pillLp);

        addTool(center, Glyph.GRID, v -> toggleDrawer());

        View div = new View(this);
        div.setBackgroundColor(0x44FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(22));
        divLp.setMargins(dp(8), 0, dp(8), 0);
        center.addView(div, divLp);

        taskbarStrip = row();
        center.addView(taskbarStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL));

        // Direita: bandeja
        LinearLayout right = row();
        right.setPadding(0, 0, dp(16), 0);
        addTool(right, Glyph.VOLUME, v -> toggleVolume());
        wifiGlyph = addTool(right, Glyph.WIFI, null);

        batteryBadge = new TextView(this);
        batteryBadge.setTextColor(0xFF111111);
        batteryBadge.setTextSize(11);
        batteryBadge.setTypeface(null, Typeface.BOLD);
        batteryBadge.setGravity(Gravity.CENTER);
        batteryBadge.setBackground(roundRect(0xFFFFFFFF, dp(13)));
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(dp(30), dp(24));
        bbLp.setMargins(dp(6), 0, dp(14), 0);
        right.addView(batteryBadge, bbLp);

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

    /** Favoritos (fixos) primeiro, depois os apps abertos que não são favoritos. */
    private void refreshTaskbar() {
        taskbarStrip.removeAllViews();
        List<String> items = new ArrayList<>(pinned);
        for (String p : running) if (!items.contains(p)) items.add(p);
        for (String pkg : items) {
            final AppEntry e = find(pkg);
            if (e == null) continue;
            final boolean isRun = running.contains(pkg);
            final boolean act = isRun && pkg.equals(activePkg) && !desktopShown;

            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);

            View ind = new View(this);
            ind.setBackground(roundRect(act ? 0xFFFFFFFF : 0x99FFFFFF, dp(2)));
            ind.setVisibility(isRun ? View.VISIBLE : View.INVISIBLE);
            slot.addView(ind, new LinearLayout.LayoutParams(dp(act ? 16 : 6), dp(3)));

            ImageView iv = new ImageView(this);
            iv.setImageDrawable(iconOf(e));
            iv.setPadding(dp(3), dp(3), dp(3), dp(3));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
            ilp.topMargin = dp(3);
            slot.addView(iv, ilp);

            slot.setOnClickListener(v -> {
                if (act) showDesktop();           // clicar no app ativo = minimizar (mostra o desktop)
                else launchApp(pkg);
            });
            slot.setOnLongClickListener(v -> {
                if (isRun) showWindowMenu(e); else showAppMenu(e);
                return true;
            });
            slot.setOnHoverListener((v, ev) -> {
                int a = ev.getAction();
                if (a == MotionEvent.ACTION_HOVER_ENTER) {
                    v.setBackground(roundRect(0x26FFFFFF, dp(8)));
                    if (isRun) {
                        handler.removeCallbacks(hideRunnable);
                        handler.removeCallbacks(showRunnable);
                        showRunnable = () -> showHoverCard(v, e);
                        handler.postDelayed(showRunnable, 200);
                    }
                } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                    v.setBackground(null);
                    handler.removeCallbacks(showRunnable);
                    scheduleHide();
                }
                return false;
            });
            taskbarStrip.addView(slot, new LinearLayout.LayoutParams(dp(48),
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
        card.setOnClickListener(v -> launchApp(e.pkg));

        LinearLayout head = row();
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(iconOf(e));
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

        // Um app normal não consegue capturar a janela de outro app: o "preview" mostra o ícone grande.
        FrameLayout prev = new FrameLayout(this);
        prev.setBackground(roundRect(0xFF0E1118, dp(8)));
        ImageView big = new ImageView(this);
        big.setImageDrawable(iconOf(e));
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

    // ------------------------------------------------------------------ menu de apps (painel do DeX) + busca

    private void buildStart() {
        startScrim = new FrameLayout(this);
        startScrim.setBackgroundColor(0x55000000);
        startScrim.setVisibility(View.GONE);
        startScrim.setClickable(true);
        startScrim.setOnClickListener(v -> hideDrawer());

        startPanel = new LinearLayout(this);
        startPanel.setOrientation(LinearLayout.VERTICAL);
        startPanel.setPadding(dp(22), dp(22), dp(22), dp(16));
        startPanel.setBackground(roundRect(0xF01C1F24, dp(30)));
        startPanel.setClickable(true); // engole cliques para não fechar o painel

        drawerAdapter = new DrawerAdapter();
        startGrid = new GridView(this);
        startGrid.setNumColumns(8);
        startGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        startGrid.setVerticalSpacing(dp(6));
        startGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        startGrid.setAdapter(drawerAdapter);
        startPanel.addView(startGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Barra de pesquisa embaixo, como no DeX
        LinearLayout sRow = row();
        sRow.setBackground(roundRect(0x2EFFFFFF, dp(24)));
        sRow.setPadding(dp(14), 0, dp(16), 0);
        sRow.addView(new Glyph(this, Glyph.SEARCH), new LinearLayout.LayoutParams(dp(26), dp(26)));

        searchBox = new EditText(this);
        searchBox.setHint("Pesquisar");
        searchBox.setHintTextColor(0x99FFFFFF);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setBackground(null);
        searchBox.setPadding(dp(10), 0, dp(10), 0);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
        });
        searchBox.setOnEditorActionListener((v, action, ev) -> { launchFirstResult(); return true; });
        sRow.addView(searchBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView verTxt = new TextView(this);
        verTxt.setText("v" + VERSION);
        verTxt.setTextColor(0x88FFFFFF);
        verTxt.setTextSize(11);
        sRow.addView(verTxt);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(14);
        startPanel.addView(sRow, slp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(860), dp(600), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = dp(12);
        startScrim.addView(startPanel, plp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.bottomMargin = dp(BAR_H_DP);
        rootFrame.addView(startScrim, lp);
    }

    private void applyFilter(String q) {
        String n = norm(q.trim());
        filtered.clear();
        for (AppEntry e : allApps) if (n.isEmpty() || e.norm.contains(n)) filtered.add(e);
        // Quem começa com o texto vem primeiro
        if (!n.isEmpty()) {
            final String fn = n;
            Collections.sort(filtered, (a, b) -> {
                boolean sa = a.norm.startsWith(fn), sb = b.norm.startsWith(fn);
                if (sa != sb) return sa ? -1 : 1;
                return a.label.toString().compareToIgnoreCase(b.label.toString());
            });
        }
        drawerAdapter.notifyDataSetChanged();
    }

    private void launchFirstResult() {
        if (!filtered.isEmpty()) launchApp(filtered.get(0).pkg);
    }

    private boolean drawerOpen() { return startScrim.getVisibility() == View.VISIBLE; }

    private void toggleDrawer() { if (drawerOpen()) hideDrawer(); else showDrawer(); }

    private void showDrawer() {
        closeOverlays();
        int w = rootFrame.getWidth() > 0 ? Math.min(dp(860), rootFrame.getWidth() - dp(48)) : dp(860);
        int h = desktopArea.getHeight() > 0 ? Math.min(dp(600), desktopArea.getHeight() - dp(24)) : dp(600);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) startPanel.getLayoutParams();
        lp.width = w;
        lp.height = h;
        startPanel.setLayoutParams(lp);
        startGrid.setNumColumns(Math.max(3, (w - dp(44)) / dp(98)));
        searchBox.setText("");
        applyFilter("");
        startScrim.setVisibility(View.VISIBLE);
        searchBox.setFocusableInTouchMode(true);
        searchBox.requestFocus();
    }

    private void hideDrawer() {
        if (!drawerOpen()) return;
        startScrim.setVisibility(View.GONE);
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
            final String p = pkg;
            AppEntry e = find(p);
            if (e == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(8));
            card.setBackground(roundRect(0x22FFFFFF, dp(12)));
            card.setOnClickListener(v -> launchApp(p));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(iconOf(e));
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
            close.setOnClickListener(v -> closeApp(p));
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
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                || st == BatteryManager.BATTERY_STATUS_FULL;
        batteryBadge.setText(String.valueOf(pct));
        batteryBadge.setBackground(roundRect(
                charging ? 0xFFA5D6A7 : (pct <= 15 ? 0xFFFF8A80 : 0xFFFFFFFF), dp(13)));
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

    private View makeIconCellEmpty() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(6), dp(4), dp(4));
        hoverBg(cell, 0x22FFFFFF, dp(10));

        ImageView iv = new ImageView(this);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        cell.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setTag(new View[]{iv, tv});
        return cell;
    }

    private void bindIconCell(View cell, AppEntry e) {
        View[] h = (View[]) cell.getTag();
        ((ImageView) h[0]).setImageDrawable(iconOf(e));
        ((TextView) h[1]).setText(e.label);
    }

    private View makeIconCell(AppEntry e) {
        View cell = makeIconCellEmpty();
        bindIconCell(cell, e);
        return cell;
    }

    private void refreshDesktopIcons() {
        iconFlow.removeAllViews();
        int shown = 0;
        for (String pkg : shortcuts) {
            final AppEntry e = find(pkg);
            if (e == null) continue;
            shown++;
            View cell = makeIconCell(e);
            cell.setOnClickListener(v -> launchApp(e.pkg));
            cell.setOnLongClickListener(v -> { showAppMenu(e); return true; });
            iconFlow.addView(cell);
        }
        emptyHint.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private void showDesktopMenu() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Papel de parede…", "Arquivos", "Todos os apps", "Sobre / versão"},
                        (d, which) -> {
                            if (which == 0) showWallpaperMenu();
                            else if (which == 1) openFiles();
                            else if (which == 2) showDrawer();
                            else showAbout();
                        }).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Meu Dex v" + VERSION)
                .setMessage("Launcher (APK) versão " + VERSION + "\n"
                        + "Tela virtual: " + displayId() + "\n"
                        + "Apps no desktop: " + shortcuts.size() + "  |  Fixados na barra: " + pinned.size())
                .setPositiveButton("OK", null)
                .show();
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
                        logState();
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

    // ------------------------------------------------------------------ menus de app / janela

    /** Menu de um app que NÃO está aberto (menu de apps, desktop, barra). */
    private void showAppMenu(AppEntry e) {
        final String pkg = e.pkg;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Abrir"); acts.add(() -> launchApp(pkg));
        labels.add("Abrir em janela");
        acts.add(() -> place(pkg, cascadeBounds(), MODE_WINDOW));
        labels.add("Abrir maximizado");
        acts.add(() -> place(pkg, areaBounds(0f, 1f), MODE_MAX));
        labels.add("Abrir em tela cheia (jogos)");
        acts.add(() -> place(pkg, null, MODE_FULL));
        if (shortcuts.contains(pkg)) {
            labels.add("Remover do desktop");
            acts.add(() -> { shortcuts.remove(pkg); saveState(); refreshDesktopIcons(); });
        } else {
            labels.add("Adicionar ao desktop");
            acts.add(() -> { shortcuts.add(pkg); saveState(); refreshDesktopIcons(); toast("Adicionado ao desktop"); });
        }
        labels.add(pinned.contains(pkg) ? "Desafixar da barra de tarefas" : "Fixar na barra de tarefas");
        acts.add(() -> togglePin(pkg));
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    /** Menu de uma janela aberta (segurar o ícone na barra). */
    private void showWindowMenu(AppEntry e) {
        final String pkg = e.pkg;
        String[] items = {"Trazer para frente", "Janela (tamanho normal)", "Grande (85%)", "Compacto (formato celular)",
                "Maximizar", "Metade esquerda", "Metade direita", "Metade de cima", "Metade de baixo",
                "Quarto superior esquerdo", "Quarto superior direito", "Quarto inferior esquerdo", "Quarto inferior direito",
                "Centralizar", "Tamanho personalizado…", "Tela cheia (cobre a barra)",
                pinned.contains(pkg) ? "Desafixar da barra" : "Fixar na barra", "Fechar"};
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: launchApp(pkg); break;
                        case 1: place(pkg, centeredBounds(0.62f, 0.78f), MODE_WINDOW); break;
                        case 2: place(pkg, centeredBounds(0.85f, 0.88f), MODE_WINDOW); break;
                        case 3: place(pkg, centeredBounds(0.28f, 0.82f), MODE_WINDOW); break;
                        case 4: place(pkg, areaBounds(0f, 1f), MODE_MAX); break;
                        case 5: place(pkg, areaFrac(0f, 0f, 0.5f, 1f), MODE_WINDOW); break;
                        case 6: place(pkg, areaFrac(0.5f, 0f, 1f, 1f), MODE_WINDOW); break;
                        case 7: place(pkg, areaFrac(0f, 0f, 1f, 0.5f), MODE_WINDOW); break;
                        case 8: place(pkg, areaFrac(0f, 0.5f, 1f, 1f), MODE_WINDOW); break;
                        case 9: place(pkg, areaFrac(0f, 0f, 0.5f, 0.5f), MODE_WINDOW); break;
                        case 10: place(pkg, areaFrac(0.5f, 0f, 1f, 0.5f), MODE_WINDOW); break;
                        case 11: place(pkg, areaFrac(0f, 0.5f, 0.5f, 1f), MODE_WINDOW); break;
                        case 12: place(pkg, areaFrac(0.5f, 0.5f, 1f, 1f), MODE_WINDOW); break;
                        case 13: place(pkg, centeredBounds(0.62f, 0.78f), MODE_WINDOW); break;
                        case 14: showCustomSize(e); break;
                        case 15: place(pkg, null, MODE_FULL); break;
                        case 16: togglePin(pkg); break;
                        case 17: closeApp(pkg); break;
                    }
                }).show();
    }

    /** Dialogo com duas barras (largura e altura em % da area util) para o tamanho que voce quiser. */
    private void showCustomSize(AppEntry e) {
        final String pkg = e.pkg;
        final int[] pct = {60, 75};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(4));
        final TextView wl = new TextView(this), hl = new TextView(this);
        SeekBar ws = new SeekBar(this), hs = new SeekBar(this);
        ws.setMax(80); hs.setMax(80);              // 20%..100%
        ws.setProgress(pct[0] - 20); hs.setProgress(pct[1] - 20);
        wl.setText("Largura: " + pct[0] + "%");
        hl.setText("Altura: " + pct[1] + "%");
        ws.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { pct[0] = p + 20; wl.setText("Largura: " + pct[0] + "%"); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        hs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { pct[1] = p + 20; hl.setText("Altura: " + pct[1] + "%"); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(wl); box.addView(ws); box.addView(hl); box.addView(hs);
        new AlertDialog.Builder(this)
                .setTitle(e.label + " - tamanho")
                .setView(box)
                .setPositiveButton("Aplicar", (d, w) ->
                        place(pkg, centeredBounds(pct[0] / 100f, pct[1] / 100f), MODE_WINDOW))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void togglePin(String pkg) {
        if (pinned.contains(pkg)) pinned.remove(pkg); else pinned.add(pkg);
        saveState();
        refreshTaskbar();
    }

    // ------------------------------------------------------------------ janelas e lançamento

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
        return areaFrac(from, 0f, to, 1f);
    }

    /** Retangulo dentro da area util (acima da barra) em fracoes 0..1. */
    private Rect areaFrac(float l, float t, float r, float b) {
        int[] loc = new int[2];
        desktopArea.getLocationOnScreen(loc);
        int w = desktopArea.getWidth(), h = desktopArea.getHeight();
        return new Rect(loc[0] + (int) (w * l), loc[1] + (int) (h * t),
                loc[0] + (int) (w * r), loc[1] + (int) (h * b));
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

    private int modeOf(String pkg) {
        Integer m = modes.get(pkg);
        return m == null ? MODE_WINDOW : m;
    }

    /** Abrir com o modo lembrado. App já aberto: só traz a janela para frente (mantém tamanho e posição). */
    private void launchApp(String pkg) {
        boolean isRunning = running.contains(pkg);
        int mode = modeOf(pkg);
        Rect b = mode == MODE_MAX ? areaBounds(0f, 1f) : (mode == MODE_WINDOW ? cascadeBounds() : null);
        open(pkg, isRunning ? null : b, mode, !isRunning);
    }

    /** Escolhe modo/tamanho (e lembra o modo). Vale para app aberto ou fechado. */
    private void place(String pkg, Rect bounds, int mode) {
        modes.put(pkg, mode);
        saveState();
        lastLaunch = 0;
        open(pkg, bounds, mode, true);
    }

    private void cycleWindows() {
        if (running.isEmpty()) return;
        int next = (running.indexOf(activePkg) + 1) % running.size();
        lastLaunch = 0;
        launchApp(running.get(next));
    }

    /** "Mostrar desktop" = minimizar tudo: o launcher vai para frente, os apps continuam abertos. */
    private void showDesktop() {
        closeOverlays();
        desktopShown = true;
        refreshTaskbar();
        bringLauncherToFront();
    }

    private void bringLauncherToFront() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId());
        try { startActivity(i, opts.toBundle()); } catch (Exception ignored) { }
    }

    /**
     * apply=true  -> abre/recoloca a janela com bounds + modo de janela.
     * apply=false -> só traz o app para frente.
     */
    private void open(String pkg, Rect bounds, int mode, boolean apply) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < LAUNCH_DEBOUNCE_MS) return;
        lastLaunch = now;
        closeOverlays();

        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            toast("Não foi possível abrir este app");
            return;
        }
        // Primeira abertura: MULTIPLE_TASK cria uma tarefa NOVA nesta tela (mantém celular e desktop separados).
        // Configurações do sistema ficam com tela preta na tela virtual: abre no celular (como o DroidUP).
        boolean onPhone = pkg.equals("com.android.settings");
        boolean first = !running.contains(pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | (first && !onPhone ? Intent.FLAG_ACTIVITY_MULTIPLE_TASK : 0));
        if (onPhone) toast("Configurações abrem na tela do celular");

        int wm = 0;
        if (apply && !onPhone) wm = (mode == MODE_FULL) ? WM_FULLSCREEN : WM_FREEFORM;
        boolean ok = tryStart(intent, onPhone ? 0 : displayId(),
                (apply && !onPhone && mode != MODE_FULL) ? bounds : null, wm);
        if (!ok || onPhone) return;

        if (first) running.add(pkg);
        activePkg = pkg;
        desktopShown = false;
        refreshTaskbar();
        if (apply) logFit(pkg, bounds, mode);
    }

    private boolean tryStart(Intent intent, int display, Rect bounds, int windowingMode) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(display);
                if (bounds != null) o.setLaunchBounds(bounds);
                // Na 1ª tentativa também pede o modo de janela (API oculta; se o sistema recusar, tenta sem).
                if (attempt == 0 && windowingMode != 0) setWindowingMode(o, windowingMode);
                startActivity(intent, o.toBundle());
                return true;
            } catch (Exception ex) {
                if (attempt == 1) toast("Falha ao abrir: " + ex.getMessage());
            }
        }
        return false;
    }

    private static void setWindowingMode(ActivityOptions o, int mode) {
        try {
            ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, mode);
        } catch (Throwable ignored) { /* API oculta: o PC reforça o modo depois (cmd=fit) */ }
    }

    /** Avisa o PC: confira se a janela está no modo certo e ajuste o tamanho (am task resize). */
    private void logFit(String pkg, Rect b, int mode) {
        Rect r = b != null ? b : new Rect(0, 0, 0, 0);
        Log.i(TAG, "cmd=fit pkg=" + pkg + " b=" + r.left + "," + r.top + "," + r.right + "," + r.bottom
                + " mode=" + mode + " display=" + displayId());
    }

    /** O PC faz o force-stop (é o único jeito de fechar um app que está em primeiro plano). */
    private void closeApp(String pkg) {
        closeOverlays();
        Log.i(TAG, "cmd=close pkg=" + pkg + " display=" + displayId());
        running.remove(pkg);
        if (pkg.equals(activePkg)) activePkg = null;
        refreshTaskbar();
        // Plano B sem PC: encerra se o app estiver em segundo plano.
        handler.postDelayed(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                am.killBackgroundProcesses(pkg);
            } catch (Exception ignored) { }
        }, 1500);
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
            final AppEntry e = filtered.get(position);
            View cell = convertView != null ? convertView : makeIconCellEmpty();
            bindIconCell(cell, e);
            cell.setOnClickListener(v -> launchApp(e.pkg));
            cell.setOnLongClickListener(v -> { showAppMenu(e); return true; });
            return cell;
        }
    }

    /** Ícones do desktop: fileira centralizada embaixo (como o DeX); se não couber, sobe para a próxima fileira. */
    private static class BottomFlow extends ViewGroup {
        private final int cellW, cellH, margin;

        BottomFlow(Context c, int cellW, int cellH, int margin) {
            super(c);
            this.cellW = cellW;
            this.cellH = cellH;
            this.margin = margin;
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
            int w = r - l, h = b - t;
            int perRow = Math.max(1, w / cellW);
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                int row = i / perRow, col = i % perRow;
                int inRow = Math.min(perRow, n - row * perRow);
                int x0 = (w - inRow * cellW) / 2;
                int left = x0 + col * cellW;
                int top = Math.max(0, h - margin - (row + 1) * cellH);
                getChildAt(i).layout(left, top, left + cellW, top + cellH);
            }
        }
    }

    /** Ícones de linha brancos desenhados à mão (estilo barra do DeX). */
    private static class Glyph extends View {
        static final int GRID = 0, SEARCH = 1, RECENTS = 2, HOME = 3, BACK = 4,
                VOLUME = 5, WIFI = 6;
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private boolean dim = false;

        Glyph(Context c, int type) {
            super(c);
            this.type = type;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

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
            }
        }
    }
}
