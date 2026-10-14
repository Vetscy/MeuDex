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

import android.Manifest;
import android.content.ComponentName;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.widget.ScrollView;
import java.util.Locale;
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

    static final String VERSION = "0.6.0";

    private static final String TAG = "MeuDex";
    private static final String PREFS = "meudex_prefs";
    private static final String KEY_SHORTCUTS = "shortcuts";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_MODES = "modes";
    private static final String KEY_WALL = "wallpaper";
    private static final String KEY_POS = "iconpos";     // posição (coluna:linha) dos ícones do desktop
    private static final String KEY_FREE = "iconfree";   // true = pode arrastar os ícones
    private static final long LAUNCH_DEBOUNCE_MS = 300;
    private static final String KEY_UI = "uimode";         // "dex" | "win"
    private static final String KEY_AUTOSEEN = "autoseen"; // navegadores/jogos que já foram oferecidos ao desktop
    private static final String KEY_AUTOADD = "autoadd";   // adicionar navegadores/jogos sozinho
    private static final int REQ_LOC = 78;
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
            {0xFF0A0A0A, 0xFF171717, 0xFF262626},
            {0xFF0B3D91, 0xFF2D7FF9, 0xFF9CCBFF}};
    private static final String[] PRESET_NAMES =
            {"Azul (padrão)", "Grafite", "Oceano", "Pôr do sol", "Preto", "Azul claro (Windows)"};

    private static final String[] KNOWN_BROWSERS = {
            "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
            "org.mozilla.focus", "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
            "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
            "com.vivaldi.browser", "com.kiwibrowser.browser", "com.ecosia.android",
            "org.torproject.torbrowser", "com.android.browser", "com.UCMobile.intl",
            "com.yandex.browser", "mark.via.gp", "org.chromium.chrome", "com.mi.globalbrowser"};

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
    private boolean winMode = false;     // false = modo DeX, true = modo Windows
    private boolean autoAdd = true;
    private final List<String> autoSeen = new ArrayList<>();
    private final java.util.HashSet<String> maxed = new java.util.HashSet<>();   // janelas maximizadas por nós
    private final Map<String, Rect> restoreBounds = new HashMap<>();             // tamanho antes de maximizar
    private boolean phoneMode = false;   // aberto na tela do celular (não na tela virtual): só mostra um aviso, não executa nada

    private FrameLayout rootFrame, desktopArea, startScrim;
    private LinearLayout startPanel;
    private ImageView wallpaperImage;
    private IconGrid iconFlow;
    private final List<String> gridPkgs = new ArrayList<>();      // app de cada ícone do desktop (mesma ordem da grade)
    private final Map<String, int[]> iconPos = new HashMap<>();   // pkg -> {coluna, linha}
    private boolean iconsFree = true;
    private LinearLayout taskbarStrip;
    private TextView emptyHint;
    private Glyph wifiGlyph, volGlyph, tbBattery, chipPlay;
    private TextView tbBatteryText, chipTitle, startHeader, startToggle;
    private LinearLayout mediaChip;
    private boolean showAll = false;      // menu Iniciar (Windows): false = fixados, true = todos os apps
    private int batteryPct = -1;
    private boolean batteryCharging = false, wifiOn = false;
    // mídia (Spotify etc.)
    private MediaSessionManager msm;
    private MediaController mediaCtl;
    private boolean mediaAllowed = false, qpSeeking = false;
    private View quickPanel, windowPanel;
    private TextView qpSrc, qpTitle, qpArtist, qpT1, qpT2, qpPct, qpBattState, qpWifiStatus;
    private ImageView qpArt;
    private Glyph qpPlay, qpBatt;
    private SeekBar qpBar;
    private LinearLayout qpWifiList;
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
        // Na tela do celular (display 0) o launcher NÃO deve rodar: tela cheia escura, comandos indo para o celular e,
        // principalmente, avisar o PC "display=0" faria o PC mexer nas janelas do celular.
        if (displayId() == 0) {
            phoneMode = true;
            setContentView(buildPhoneNotice());
            return;
        }
        loadApps();
        loadState();
        autoAddApps(false);
        setContentView(buildUi());
        applyWallpaper();
        refreshDesktopIcons();
        refreshTaskbar();
        registerStatus();
        initMedia();
        // O PC le esta linha: id da tela virtual + versao do launcher instalado.
        Log.i(TAG, "ready display=" + displayId() + " version=" + VERSION);
        preloadIcons();
        logLabels();
        // Primeira vez: escolher a interface (modo DeX ou modo Windows).
        if (!prefs.contains(KEY_UI)) rootFrame.post(() -> showUiModeChooser(true));
        handleCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (phoneMode) return;
        setIntent(intent);
        handleCommand(intent);
    }

    /** Aviso simples para quando o app é aberto no celular. Sai sozinho ao ir para segundo plano (não fica tarefa parada). */
    private View buildPhoneNotice() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setBackgroundColor(0xFF101418);
        TextView t = new TextView(this);
        t.setText("Meu Dex roda na tela virtual do PC.\n\nAbra o programa do PC e clique em INICIAR.\nNada é executado no celular.");
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        android.widget.Button b = new android.widget.Button(this);
        b.setText("Fechar");
        b.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        box.addView(b, lp);
        return box;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (phoneMode) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        bg.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        try { if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCb); } catch (Exception ignored) { }
        try { if (msm != null && mediaAllowed) msm.removeOnActiveSessionsChangedListener(sessListener); } catch (Exception ignored) { }
        try { unregisterReceiver(batteryRcv); } catch (Exception ignored) { }
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !phoneMode) {
            enterFullscreen();
            if (!mediaAllowed) initMedia();   // o usuário pode ter liberado o acesso nas configurações do celular
        }
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
        if (phoneMode) { finish(); return; }
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
                case "front": if (data != null && safePkg(data)) { lastLaunch = 0; launchApp(data); } break;
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
        if (phoneMode) return super.dispatchKeyEvent(e);
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
    /** Manda ao PC o nome de cada app (o Alt+Tab do PC mostra o nome, não o pacote). Feito fora da thread da interface. */
    private void logLabels() {
        final List<AppEntry> copy = new ArrayList<>(allApps);
        bg.execute(() -> {
            for (AppEntry e : copy) {
                if (destroyed) return;
                Log.i(TAG, "label pkg=" + e.pkg + " text=" + Uri.encode(String.valueOf(e.label)));
            }
        });
    }

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

    /** Navegadores (quem atende http://) + jogos (categoria do Android ou lista conhecida), só os que têm ícone de app. */
    private List<String> autoCandidates() {
        List<String> web = new ArrayList<>();
        List<String> games = new ArrayList<>();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://"));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo ri : pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                String p = ri.activityInfo.packageName;
                if (find(p) != null && !web.contains(p)) web.add(p);
            }
        } catch (Throwable ignored) { }
        for (String p : KNOWN_BROWSERS) if (find(p) != null && !web.contains(p)) web.add(p);
        for (AppEntry e : allApps) if (isGame(e.pkg) && !web.contains(e.pkg)) games.add(e.pkg);
        List<String> out = new ArrayList<>(web);
        out.addAll(games);
        return out;
    }

    /**
     * Coloca navegadores e jogos instalados no desktop. Cada app é oferecido UMA vez (lista "autoSeen"):
     * se você tirar o ícone do desktop, ele não volta sozinho. force=true readiciona tudo (pedido explícito).
     */
    private int autoAddApps(boolean force) {
        if (!autoAdd && !force) return 0;
        int added = 0;
        for (String p : autoCandidates()) {
            boolean seen = autoSeen.contains(p);
            if (seen && !force) continue;
            if (!seen) autoSeen.add(p);
            if (!shortcuts.contains(p)) { shortcuts.add(p); added++; }
        }
        prefs.edit().putString(KEY_AUTOSEEN, TextUtils.join(",", autoSeen)).apply();
        if (added > 0) saveState();
        return added;
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

    private void fillPos(String csv) {
        iconPos.clear();
        for (String s : csv.split(",")) {
            String[] a = s.split(":");
            if (a.length != 3 || !safePkg(a[0])) continue;
            try { iconPos.put(a[0], new int[]{Math.max(0, Integer.parseInt(a[1])), Math.max(0, Integer.parseInt(a[2]))}); }
            catch (NumberFormatException ignored) { }
        }
    }

    private String posCsv() {
        List<String> parts = new ArrayList<>();
        for (String pkg : shortcuts) {
            int[] c = iconPos.get(pkg);
            if (c != null) parts.add(pkg + ":" + c[0] + ":" + c[1]);
        }
        return TextUtils.join(",", parts);
    }

    private void loadState() {
        iconsFree = prefs.getBoolean(KEY_FREE, true);
        fillPos(prefs.getString(KEY_POS, ""));
        fillList(shortcuts, prefs.getString(KEY_SHORTCUTS, ""));
        fillList(pinned, prefs.getString(KEY_PINNED, ""));
        fillModes(prefs.getString(KEY_MODES, ""));
        winMode = "win".equals(prefs.getString(KEY_UI, "dex"));
        autoAdd = prefs.getBoolean(KEY_AUTOADD, true);
        fillList(autoSeen, prefs.getString(KEY_AUTOSEEN, ""));
    }

    /** Formato simples (só caracteres seguros para o shell do adb): d=a,b@p=c@m=x:1@w=preset:0 */
    private String encodeState() {
        String w = prefs.getString(KEY_WALL, "preset:0");
        if (!WALL_PATTERN.matcher(w).matches()) w = "preset:0";
        return "d=" + TextUtils.join(",", shortcuts) + "@p=" + TextUtils.join(",", pinned)
                + "@m=" + modesCsv() + "@w=" + w + "@i=" + posCsv() + "@u=" + (winMode ? "win" : "dex");
    }

    private void logState() {
        Log.i(TAG, "state " + encodeState());
    }

    private void saveState() {
        prefs.edit()
                .putString(KEY_SHORTCUTS, TextUtils.join(",", shortcuts))
                .putString(KEY_PINNED, TextUtils.join(",", pinned))
                .putString(KEY_MODES, modesCsv())
                .putString(KEY_POS, posCsv())
                .apply();
        logState(); // o PC guarda uma cópia em meudex_apps.json
    }

    /** Restaura o que o PC guardou (útil depois de reinstalar o launcher). */
    private void importState(String s) {
        boolean rebuild = false;
        for (String part : s.split("@")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            String k = part.substring(0, i), v = part.substring(i + 1);
            switch (k) {
                case "d": fillList(shortcuts, v); break;
                case "p": fillList(pinned, v); break;
                case "m": fillModes(v); break;
                case "i": fillPos(v); break;
                case "u":
                    if (v.equals("win") || v.equals("dex")) {
                        rebuild = rebuild || (v.equals("win")) != winMode;
                        winMode = v.equals("win");
                        prefs.edit().putString(KEY_UI, v).apply();
                    }
                    break;
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
                .putString(KEY_POS, posCsv())
                .apply();
        // O que foi restaurado já conta como "visto": não readiciona navegadores/jogos que você tirou do desktop.
        for (String p : autoCandidates()) if (!autoSeen.contains(p)) autoSeen.add(p);
        prefs.edit().putString(KEY_AUTOSEEN, TextUtils.join(",", autoSeen)).apply();
        if (rebuild) {
            applyUiMode();
        } else {
            applyWallpaper();
            refreshDesktopIcons();
            refreshTaskbar();
        }
        Log.i(TAG, "imported d=" + shortcuts.size() + " p=" + pinned.size());
    }

    // ------------------------------------------------------------------ UI base

    private int barH() { return winMode ? 48 : 52; }

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
        dlp.bottomMargin = dp(barH());
        rootFrame.addView(desktopArea, dlp);
        desktopArea.setOnClickListener(v -> closeOverlays());
        desktopArea.setOnLongClickListener(v -> { showDesktopMenu(); return true; });

        // Ícones do desktop: grade no canto superior esquerdo (colunas de cima para baixo), como no Windows.
        iconFlow = winMode ? new IconGrid(this, dp(88), dp(98), dp(8))
                           : new IconGrid(this, dp(96), dp(104), dp(10));
        iconFlow.onMoved = this::onIconsMoved;
        desktopArea.addView(iconFlow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyHint = new TextView(this);
        emptyHint.setText("Segure um app no menu de apps para colocá-lo aqui.\nSegure o fundo para papel de parede e organização dos ícones.");
        emptyHint.setTextColor(0xAAFFFFFF);
        emptyHint.setTextSize(14);
        emptyHint.setGravity(Gravity.CENTER);
        desktopArea.addView(emptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Marca de versão no canto: confirma de relance que o APK novo está instalado.
        TextView ver = new TextView(this);
        ver.setText("Meu Dex v" + VERSION + (winMode ? "  •  Windows" : "  •  DeX"));
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(barH()), Gravity.BOTTOM);
        rootFrame.addView(buildTaskbar(), blp);

        desktopArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> logArea());
        return rootFrame;
    }

    // ------------------------------------------------------------------ barra de tarefas (DeX ou Windows)

    private View buildTaskbar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(winMode ? 0xF01C1C20 : 0xF2101216);

        LinearLayout left = row();
        LinearLayout center = row();
        taskbarStrip = row();

        if (winMode) {
            // Windows 11: botão Iniciar + pesquisa + apps, tudo centralizado. O "voltar" do Android fica à esquerda.
            left.setPadding(dp(8), 0, 0, 0);
            addTool(left, Glyph.BACK, v -> {
                if (anyOverlayOpen()) closeOverlays();
                else Log.i(TAG, "cmd=back display=" + displayId());
            });
            addTool(center, Glyph.WINLOGO, v -> toggleDrawer());
            addTool(center, Glyph.SEARCH, v -> showDrawer());
            addTool(center, Glyph.RECENTS, v -> toggleRecents());
            View div = new View(this);
            div.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(20));
            divLp.setMargins(dp(6), 0, dp(6), 0);
            center.addView(div, divLp);
        } else {
            // DeX: grade de apps | pesquisa, recentes, início, voltar à esquerda; apps no centro.
            left.setPadding(dp(12), 0, 0, 0);
            addTool(left, Glyph.GRID, v -> toggleDrawer());
            View div = new View(this);
            div.setBackgroundColor(0x44FFFFFF);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(22));
            divLp.setMargins(dp(6), 0, dp(6), 0);
            left.addView(div, divLp);
            addTool(left, Glyph.SEARCH, v -> showDrawer());
            addTool(left, Glyph.RECENTS, v -> toggleRecents());
            addTool(left, Glyph.HOME, v -> showDesktop());
            addTool(left, Glyph.BACK, v -> {
                if (anyOverlayOpen()) closeOverlays();
                else Log.i(TAG, "cmd=back display=" + displayId()); // o PC injeta o Voltar na tela virtual
            });
        }
        bar.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        center.addView(taskbarStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL));

        // Direita: bandeja (mídia, volume, Wi-Fi, bateria, relógio)
        LinearLayout right = row();
        right.setPadding(0, 0, winMode ? 0 : dp(12), 0);

        buildMediaChip();
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        chipLp.rightMargin = dp(8);
        right.addView(mediaChip, chipLp);

        volGlyph = addTool(right, Glyph.VOLUME, v -> toggleVolume(v));
        wifiGlyph = addTool(right, Glyph.WIFI, v -> toggleQuick());

        LinearLayout batt = row();
        batt.setPadding(dp(6), 0, dp(8), 0);
        hoverBg(batt, 0x26FFFFFF, dp(8));
        batt.setOnClickListener(v -> toggleQuick());
        tbBattery = new Glyph(this, Glyph.BATTERY);
        batt.addView(tbBattery, new LinearLayout.LayoutParams(dp(28), dp(28)));
        if (!winMode) {
            tbBatteryText = new TextView(this);
            tbBatteryText.setTextColor(Color.WHITE);
            tbBatteryText.setTextSize(12);
            tbBatteryText.setPadding(dp(2), 0, 0, 0);
            batt.addView(tbBatteryText);
        } else {
            tbBatteryText = null;
        }
        right.addView(batt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        clockBox.setPadding(dp(8), 0, dp(8), 0);
        hoverBg(clockBox, 0x26FFFFFF, dp(8));
        clockBox.setOnClickListener(v -> toggleQuick());
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(winMode ? 12 : 14);
        time.setGravity(Gravity.END);
        TextClock date = new TextClock(this);
        String dfmt = winMode ? "dd/MM/yyyy" : "EEE, dd/MM";
        date.setFormat24Hour(dfmt);
        date.setFormat12Hour(dfmt);
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(10);
        date.setGravity(Gravity.END);
        clockBox.addView(time);
        clockBox.addView(date);
        right.addView(clockBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (winMode) {
            // Faixa fina no canto direito: "Mostrar desktop", como no Windows.
            View sliver = new View(this);
            sliver.setBackgroundColor(0x22FFFFFF);
            sliver.setOnClickListener(v -> showDesktop());
            LinearLayout.LayoutParams slLp = new LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT);
            slLp.leftMargin = dp(4);
            right.addView(sliver, slLp);
        }
        bar.addView(right, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        updateBatteryViews();
        updateVolumeGlyph();
        return bar;
    }

    /** Pílula "tocando agora" da bandeja (aparece quando há música/podcast em uma sessão de mídia, ex.: Spotify). */
    private void buildMediaChip() {
        mediaChip = row();
        mediaChip.setPadding(dp(10), 0, dp(4), 0);
        mediaChip.setBackground(roundRect(0x2EFFFFFF, dp(17)));
        mediaChip.setVisibility(View.GONE);
        mediaChip.setOnClickListener(v -> toggleQuick());
        mediaChip.addView(new Glyph(this, Glyph.MUSIC), new LinearLayout.LayoutParams(dp(18), dp(18)));
        chipTitle = new TextView(this);
        chipTitle.setTextColor(Color.WHITE);
        chipTitle.setTextSize(12);
        chipTitle.setSingleLine(true);
        chipTitle.setEllipsize(TextUtils.TruncateAt.END);
        chipTitle.setMaxWidth(dp(150));
        chipTitle.setPadding(dp(8), 0, dp(4), 0);
        mediaChip.addView(chipTitle);
        chipPlay = new Glyph(this, Glyph.PLAY);
        hoverBg(chipPlay, 0x33FFFFFF, dp(14));
        chipPlay.setOnClickListener(v -> mediaToggle());
        mediaChip.addView(chipPlay, new LinearLayout.LayoutParams(dp(30), dp(30)));
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

    private TextView tv(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView textBtn(String text, View.OnClickListener l) {
        TextView t = tv(text, 12, Color.WHITE, false);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(roundRect(0x26FFFFFF, dp(8)));
        t.setOnClickListener(l);
        return t;
    }

    /** Botão de ícone das barras de janela (voltar, fixar, minimizar, maximizar, fechar). */
    private Glyph wctl(int type, int level, View.OnClickListener l, final boolean keepCard) {
        final Glyph g = new Glyph(this, type);
        g.setLevel(level);
        g.setOnClickListener(l);
        g.setOnHoverListener((v, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_HOVER_ENTER) {
                v.setBackground(roundRect(0x33FFFFFF, dp(8)));
                if (keepCard) handler.removeCallbacks(hideRunnable);
            } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                v.setBackground(null);
                if (keepCard) scheduleHide();
            }
            return false;
        });
        return g;
    }

    /** Favoritos (fixos) primeiro, depois os apps abertos que não são favoritos. */
    private void refreshTaskbar() {
        taskbarStrip.removeAllViews();
        List<String> items = new ArrayList<>(pinned);
        for (String p : running) if (!items.contains(p)) items.add(p);
        final int accent = winMode ? 0xFF60CDFF : 0xFFFFFFFF;
        for (String pkg : items) {
            final AppEntry e = find(pkg);
            if (e == null) continue;
            final boolean isRun = running.contains(pkg);
            final boolean act = isRun && pkg.equals(activePkg) && !desktopShown;
            final int idleBg = (winMode && act) ? 0x2EFFFFFF : 0;

            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            if (idleBg != 0) slot.setBackground(roundRect(idleBg, dp(8)));

            View ind = new View(this);
            ind.setBackground(roundRect(act ? accent : 0x99FFFFFF, dp(2)));
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
                if (isRun) showWindowPanel(v, e); else showAppMenu(e);
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
                    v.setBackground(idleBg != 0 ? roundRect(idleBg, dp(8)) : null);
                    handler.removeCallbacks(showRunnable);
                    scheduleHide();
                }
                return false;
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(winMode ? 46 : 48),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            if (winMode) { slp.topMargin = dp(3); slp.bottomMargin = dp(3); }
            taskbarStrip.addView(slot, slp);
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
        int cardW = dp(250);
        final String pkg = e.pkg;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(6), dp(8), dp(10));
        card.setBackground(roundRect(0xF2181C26, dp(12)));
        card.setOnHoverListener(cardHover);
        card.setOnClickListener(v -> launchApp(pkg));

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
        // Controles no estilo da janela do DeX: minimizar, maximizar/restaurar, fechar
        head.addView(wctl(Glyph.MINIMIZE, -1, v -> { hideHoverCard(); showDesktop(); }, true),
                new LinearLayout.LayoutParams(dp(30), dp(30)));
        head.addView(wctl(maxed.contains(pkg) ? Glyph.RESTORE : Glyph.MAXIMIZE, -1,
                v -> { hideHoverCard(); toggleMaximize(pkg); }, true),
                new LinearLayout.LayoutParams(dp(30), dp(30)));
        head.addView(wctl(Glyph.CLOSE, -1, v -> closeApp(pkg), true),
                new LinearLayout.LayoutParams(dp(30), dp(30)));
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
        plp.topMargin = dp(6);
        card.addView(prev, plp);

        int[] a = new int[2], r = new int[2];
        anchor.getLocationOnScreen(a);
        rootFrame.getLocationOnScreen(r);
        int cx = a[0] - r[0] + anchor.getWidth() / 2;
        int left = Math.max(dp(8), Math.min(cx - cardW / 2, rootFrame.getWidth() - cardW - dp(8)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = left;
        lp.bottomMargin = dp(barH() + 6);
        hoverCard = card;
        rootFrame.addView(card, lp);
    }

    // ------------------------------------------------------------------ menu de apps (painel do DeX) + busca

    private void buildStart() {
        startScrim = new FrameLayout(this);
        startScrim.setBackgroundColor(winMode ? 0x22000000 : 0xE60B1020);
        startScrim.setVisibility(View.GONE);
        startScrim.setClickable(true);
        startScrim.setOnClickListener(v -> hideDrawer());

        startPanel = new LinearLayout(this);
        startPanel.setOrientation(LinearLayout.VERTICAL);

        drawerAdapter = new DrawerAdapter();
        startGrid = new GridView(this);
        startGrid.setNumColumns(winMode ? 6 : 8);
        startGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        startGrid.setVerticalSpacing(dp(winMode ? 4 : 14));
        startGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        startGrid.setAdapter(drawerAdapter);
        View searchRow = buildSearchRow();

        FrameLayout.LayoutParams plp;
        if (winMode) {
            // Menu Iniciar do Windows 11: pesquisa em cima, "Fixados" / "Todos os apps", rodapé.
            startPanel.setPadding(dp(26), dp(22), dp(26), dp(14));
            GradientDrawable bg = roundRect(0xF2262629, dp(16));
            bg.setStroke(dp(1), 0x33FFFFFF);
            startPanel.setBackground(bg);
            startPanel.setClickable(true); // engole cliques para não fechar o painel

            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            slp.bottomMargin = dp(18);
            startPanel.addView(searchRow, slp);

            LinearLayout head = row();
            startHeader = tv("Fixados", 14, Color.WHITE, true);
            head.addView(startHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            startToggle = textBtn("Todos os apps  ›", v -> {
                showAll = !showAll;
                applyFilter(searchBox.getText().toString());
            });
            head.addView(startToggle);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.bottomMargin = dp(10);
            startPanel.addView(head, hlp);

            startPanel.addView(startGrid, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            View div = new View(this);
            div.setBackgroundColor(0x22FFFFFF);
            LinearLayout.LayoutParams dvl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dvl.topMargin = dp(10);
            dvl.bottomMargin = dp(10);
            startPanel.addView(div, dvl);

            LinearLayout foot = row();
            foot.addView(tv("Meu Dex  •  v" + VERSION, 12, 0xCCFFFFFF, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            foot.addView(textBtn("Personalizar", v -> { hideDrawer(); showDesktopMenu(); }));
            startPanel.addView(foot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            plp = new FrameLayout.LayoutParams(dp(640), dp(640), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            plp.bottomMargin = dp(12);
        } else {
            // Menu de apps do DeX: tela cheia escurecida, pesquisa no canto superior direito, grade de ícones.
            startPanel.setPadding(dp(72), dp(28), dp(72), dp(20));
            startPanel.setOnClickListener(v -> hideDrawer());

            LinearLayout top = row();
            top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            top.addView(searchRow, new LinearLayout.LayoutParams(dp(300), dp(40)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = dp(18);
            startPanel.addView(top, tlp);

            startPanel.addView(startGrid, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        startScrim.addView(startPanel, plp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.bottomMargin = dp(barH());
        rootFrame.addView(startScrim, lp);
    }

    private View buildSearchRow() {
        LinearLayout sRow = row();
        sRow.setBackground(roundRect(winMode ? 0x33FFFFFF : 0x40FFFFFF, dp(22)));
        sRow.setPadding(dp(14), 0, dp(14), 0);
        sRow.addView(new Glyph(this, Glyph.SEARCH), new LinearLayout.LayoutParams(dp(22), dp(22)));

        searchBox = new EditText(this);
        searchBox.setHint(winMode ? "Digite aqui para pesquisar" : "Pesquisar");
        searchBox.setHintTextColor(0x99FFFFFF);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setTextSize(14);
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
        return sRow;
    }

    private void applyFilter(String q) {
        String n = norm(q.trim());
        filtered.clear();
        boolean pinnedView = winMode && n.isEmpty() && !showAll;
        if (pinnedView) {
            // Windows: "Fixados" = favoritos da barra + ícones do desktop (até 18); sem nenhum, os primeiros apps.
            for (String p : pinned) { AppEntry e = find(p); if (e != null && !filtered.contains(e)) filtered.add(e); }
            for (String p : shortcuts) {
                if (filtered.size() >= 18) break;
                AppEntry e = find(p);
                if (e != null && !filtered.contains(e)) filtered.add(e);
            }
            if (filtered.isEmpty()) {
                for (AppEntry e : allApps) { if (filtered.size() >= 18) break; filtered.add(e); }
            }
        } else {
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
        }
        if (winMode && startHeader != null && startToggle != null) {
            startHeader.setText(pinnedView ? "Fixados" : (n.isEmpty() ? "Todos os apps" : "Resultados"));
            startToggle.setText(showAll ? "‹  Fixados" : "Todos os apps  ›");
            startToggle.setVisibility(n.isEmpty() ? View.VISIBLE : View.GONE);
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
        showAll = false;
        if (winMode) {
            int w = rootFrame.getWidth() > 0 ? Math.min(dp(640), rootFrame.getWidth() - dp(48)) : dp(640);
            int h = desktopArea.getHeight() > 0 ? Math.min(dp(640), desktopArea.getHeight() - dp(24)) : dp(640);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) startPanel.getLayoutParams();
            lp.width = w;
            lp.height = h;
            startPanel.setLayoutParams(lp);
            startGrid.setNumColumns(Math.max(3, (w - dp(52)) / dp(92)));
        } else {
            int w = rootFrame.getWidth() > 0 ? rootFrame.getWidth() : dp(1000);
            startGrid.setNumColumns(Math.max(4, (w - dp(144)) / dp(124)));
        }
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
        return drawerOpen() || recentsPanel != null || volumePanel != null
                || quickPanel != null || windowPanel != null;
    }

    private void closeOverlays() {
        hideDrawer();
        hideHoverCard();
        if (recentsPanel != null) { rootFrame.removeView(recentsPanel); recentsPanel = null; }
        if (volumePanel != null) { rootFrame.removeView(volumePanel); volumePanel = null; }
        if (windowPanel != null) { rootFrame.removeView(windowPanel); windowPanel = null; }
        if (quickPanel != null) {
            rootFrame.removeView(quickPanel);
            quickPanel = null;
            qpSrc = qpTitle = qpArtist = qpT1 = qpT2 = qpPct = qpBattState = qpWifiStatus = null;
            qpArt = null;
            qpPlay = null;
            qpBatt = null;
            qpBar = null;
            qpWifiList = null;
            handler.removeCallbacks(tick);
        }
        updateVolumeGlyph();
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
        lp.bottomMargin = dp(barH() + 10);
        recentsPanel = panel;
        rootFrame.addView(panel, lp);
    }

    private void toggleVolume(View anchor) {
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
                if (fromUser) { am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0); updateVolumeGlyph(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        pop.addView(sb, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        int popW = dp(14 + 32 + 200 + 18);
        int[] a = new int[2], r = new int[2];
        anchor.getLocationOnScreen(a);
        rootFrame.getLocationOnScreen(r);
        int cx = a[0] - r[0] + anchor.getWidth() / 2;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.max(dp(8), Math.min(cx - popW / 2, rootFrame.getWidth() - popW - dp(8)));
        lp.bottomMargin = dp(barH() + 8);
        volumePanel = pop;
        rootFrame.addView(pop, lp);
    }

    private void updateVolumeGlyph() {
        if (volGlyph == null) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            volGlyph.setType(am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ? Glyph.MUTE : Glyph.VOLUME);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ painel rápido (Wi-Fi + bateria/hora + mídia)

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (quickPanel == null) return;
            updateMediaProgress();
            handler.postDelayed(this, 1000);
        }
    };

    private void toggleQuick() {
        boolean wasOpen = quickPanel != null;
        closeOverlays();
        if (wasOpen) return;
        if (!mediaAllowed) initMedia();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(12), dp(12), dp(12));
        col.setBackground(roundRect(0xF2161A22, dp(22)));
        col.setClickable(true);

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(532), ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.bottomMargin = dp(10);
        col.addView(buildMediaCard(), mlp);

        // Wi-Fi à esquerda; ao lado: bateria, horário e data
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.HORIZONTAL);
        both.addView(buildWifiCard(), new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(192), ViewGroup.LayoutParams.MATCH_PARENT);
        ilp.leftMargin = dp(10);
        both.addView(buildInfoCard(), ilp);
        col.addView(both, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(barH() + 8);
        quickPanel = col;
        rootFrame.addView(col, lp);

        refreshMedia();
        updateMediaProgress();
        updateBatteryViews();
        refreshWifiList();
        if (hasLocPerm()) scanWifi(false);
        handler.postDelayed(tick, 1000);
    }

    private View buildMediaCard() {
        LinearLayout card = row();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(0x22FFFFFF, dp(16)));
        if (!mediaAllowed) {
            TextView t = tv("Controle de mídia desativado.\nToque aqui para permitir o acesso às notificações "
                    + "(abre no celular) e ver o que está tocando no Spotify.", 12, 0xCCFFFFFF, false);
            card.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.setOnClickListener(v -> openPhoneSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return card;
        }
        qpArt = new ImageView(this);
        qpArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        qpArt.setBackground(roundRect(0x33FFFFFF, dp(10)));
        qpArt.setClipToOutline(true);
        card.addView(qpArt, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        qpSrc = tv("", 11, 0x99FFFFFF, false);
        qpTitle = tv("Nada tocando", 14, Color.WHITE, true);
        qpTitle.setSingleLine(true);
        qpTitle.setEllipsize(TextUtils.TruncateAt.END);
        qpArtist = tv("", 12, 0xAAFFFFFF, false);
        qpArtist.setSingleLine(true);
        qpArtist.setEllipsize(TextUtils.TruncateAt.END);
        qpBar = new SeekBar(this);
        qpBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && qpT1 != null) qpT1.setText(fmtTime(p * 1000L));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { qpSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                qpSeeking = false;
                if (mediaCtl != null) mediaCtl.getTransportControls().seekTo(sb.getProgress() * 1000L);
            }
        });
        LinearLayout times = row();
        qpT1 = tv("0:00", 10, 0x99FFFFFF, false);
        qpT2 = tv("--:--", 10, 0x99FFFFFF, false);
        times.addView(qpT1);
        times.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        times.addView(qpT2);
        mid.addView(qpSrc);
        mid.addView(qpTitle);
        mid.addView(qpArtist);
        mid.addView(qpBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mid.addView(times, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(12);
        card.addView(mid, midLp);

        LinearLayout ctrls = row();
        Glyph prev = new Glyph(this, Glyph.PREV);
        hoverBg(prev, 0x33FFFFFF, dp(20));
        prev.setOnClickListener(v -> mediaPrev());
        qpPlay = new Glyph(this, Glyph.PLAY);
        hoverBg(qpPlay, 0x33FFFFFF, dp(22));
        qpPlay.setOnClickListener(v -> mediaToggle());
        Glyph next = new Glyph(this, Glyph.NEXT);
        hoverBg(next, 0x33FFFFFF, dp(20));
        next.setOnClickListener(v -> mediaNext());
        ctrls.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ctrls.addView(qpPlay, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ctrls.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        card.addView(ctrls, clp);
        return card;
    }

    private View buildWifiCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(0x22FFFFFF, dp(16)));

        LinearLayout head = row();
        head.addView(new Glyph(this, Glyph.WIFI), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = tv("Wi-Fi", 15, Color.WHITE, true);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        qpWifiStatus = tv("", 12, 0xAAFFFFFF, false);
        head.addView(qpWifiStatus);
        card.addView(head);

        qpWifiList = new LinearLayout(this);
        qpWifiList.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(qpWifiList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        slp.topMargin = dp(8);
        card.addView(sv, slp);

        LinearLayout btns = row();
        btns.addView(textBtn("Atualizar", v -> scanWifi(true)));
        TextView cfg = textBtn("Configurações de Wi-Fi", v -> openPhoneSettings(Settings.ACTION_WIFI_SETTINGS));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = dp(8);
        btns.addView(cfg, cl);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        card.addView(btns, blp);
        return card;
    }

    private View buildInfoCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(0x22FFFFFF, dp(16)));

        LinearLayout b = row();
        qpBatt = new Glyph(this, Glyph.BATTERY);
        b.addView(qpBatt, new LinearLayout.LayoutParams(dp(36), dp(36)));
        qpPct = tv("--%", 26, Color.WHITE, true);
        qpPct.setPadding(dp(8), 0, 0, 0);
        b.addView(qpPct);
        card.addView(b);
        qpBattState = tv("", 12, 0xAAFFFFFF, false);
        card.addView(qpBattState);

        View div = new View(this);
        div.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dl.topMargin = dp(14);
        dl.bottomMargin = dp(12);
        card.addView(div, dl);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(34);
        TextClock wday = new TextClock(this);
        wday.setFormat24Hour("EEEE");
        wday.setFormat12Hour("EEEE");
        wday.setTextColor(0xDDFFFFFF);
        wday.setTextSize(14);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("d 'de' MMMM 'de' yyyy");
        date.setFormat12Hour("d 'de' MMMM 'de' yyyy");
        date.setTextColor(0xAAFFFFFF);
        date.setTextSize(12);
        card.addView(time);
        card.addView(wday);
        card.addView(date);
        return card;
    }

    // ---- Wi-Fi

    private boolean hasLocPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOC && quickPanel != null) {
            scanWifi(false);
            refreshWifiList();
        }
    }

    @SuppressWarnings("deprecation")
    private void scanWifi(boolean toastIt) {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wm.startScan();
        } catch (Throwable ignored) { }
        if (toastIt) toast("Procurando redes…");
        handler.postDelayed(this::refreshWifiList, 2500);
    }

    @SuppressWarnings("deprecation")
    private String currentSsid(WifiManager wm) {
        try {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return null;
            String n = wi.getSSID();
            if (n == null || n.equals("<unknown ssid>") || n.equals("0x")) return null;
            if (n.length() >= 2 && n.startsWith("\"") && n.endsWith("\"")) n = n.substring(1, n.length() - 1);
            return n;
        } catch (Throwable t) { return null; }
    }

    @SuppressWarnings("deprecation")
    private int wifiLevel() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
        } catch (Throwable t) { return 3; }
    }

    private void addWifiRow(String ssid, int level, boolean secure, boolean current) {
        LinearLayout item = row();
        item.setPadding(dp(8), dp(7), dp(8), dp(7));
        hoverBg(item, 0x22FFFFFF, dp(8));
        Glyph g = new Glyph(this, Glyph.WIFI);
        g.setLevel(Math.max(1, Math.min(3, level)));
        item.addView(g, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = tv(ssid, 13, Color.WHITE, current);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(name);
        if (current) txt.addView(tv("Conectado", 11, 0xFF8AB4FF, false));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        item.addView(txt, tlp);
        if (secure) item.addView(new Glyph(this, Glyph.LOCK), new LinearLayout.LayoutParams(dp(18), dp(18)));
        // O Android não deixa um app comum entrar numa rede: a escolha da rede é feita nas configurações do celular.
        item.setOnClickListener(v -> openPhoneSettings(Settings.ACTION_WIFI_SETTINGS));
        qpWifiList.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @SuppressWarnings("deprecation")
    private void refreshWifiList() {
        if (qpWifiList == null || qpWifiStatus == null) return;
        qpWifiList.removeAllViews();
        WifiManager wm = null;
        try { wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE); }
        catch (Throwable ignored) { }
        boolean enabled = wm != null && wm.isWifiEnabled();
        qpWifiStatus.setText(!enabled ? "Desligado" : (wifiOn ? "Conectado" : "Sem conexão"));
        String cur = (wm != null && wifiOn) ? currentSsid(wm) : null;
        if (wifiOn) addWifiRow(cur != null ? cur : "Rede Wi-Fi atual", wifiLevel(), false, true);

        if (!hasLocPerm()) {
            TextView hint = tv("Para listar as redes próximas, o Android exige a permissão de localização.",
                    12, 0xAAFFFFFF, false);
            hint.setPadding(dp(4), dp(8), dp(4), dp(8));
            qpWifiList.addView(hint);
            qpWifiList.addView(textBtn("Permitir",
                    v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC)));
            return;
        }
        List<ScanResult> found = new ArrayList<>();
        try { if (wm != null) found.addAll(wm.getScanResults()); } catch (Throwable ignored) { }
        Collections.sort(found, (x, y) -> y.level - x.level);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (cur != null) seen.add(cur);
        int shown = 0;
        for (ScanResult sr : found) {
            String name = sr.SSID;
            if (name == null || name.isEmpty() || !seen.add(name)) continue;
            String cap = sr.capabilities == null ? "" : sr.capabilities;
            boolean sec = cap.contains("WPA") || cap.contains("WEP") || cap.contains("SAE");
            addWifiRow(name, WifiManager.calculateSignalLevel(sr.level, 4), sec, false);
            shown++;
        }
        if (shown == 0 && !wifiOn) {
            TextView hint = tv("Nenhuma rede encontrada. Toque em Atualizar e confira se a localização do celular está ligada.",
                    12, 0xAAFFFFFF, false);
            hint.setPadding(dp(4), dp(8), dp(4), dp(8));
            qpWifiList.addView(hint);
        }
    }

    /** Abre uma tela de configurações na tela do CELULAR (nas configurações o Android não funciona na tela virtual). */
    private void openPhoneSettings(String action) {
        closeOverlays();
        Intent i = new Intent(action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(i, 0, null, 0)) toast("Aberto na tela do celular");
    }

    // ------------------------------------------------------------------ mídia (Spotify, YouTube Music, podcasts...)

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState st) { refreshMedia(); }
        @Override public void onMetadataChanged(MediaMetadata md) { refreshMedia(); }
        @Override public void onSessionDestroyed() {
            mediaCtl = null;
            initMedia();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessListener = this::pickController;

    private final Runnable mediaRetry = this::initMedia;

    /**
     * Ler as sessões de mídia de outros apps exige o acesso às notificações (Configurações > Notificações >
     * Acesso a notificações > Meu Dex). Sem isso o Android não deixa. Tenta de novo a cada poucos segundos
     * até o usuário liberar.
     */
    private void initMedia() {
        handler.removeCallbacks(mediaRetry);
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, MediaListener.class);
            List<MediaController> l = msm.getActiveSessions(cn);   // SecurityException se não houver acesso
            if (!mediaAllowed) msm.addOnActiveSessionsChangedListener(sessListener, cn, handler);
            mediaAllowed = true;
            pickController(l);
        } catch (Throwable t) {
            mediaAllowed = false;
        }
        if (!mediaAllowed && !destroyed) handler.postDelayed(mediaRetry, 6000);
        refreshMedia();
    }

    private void pickController(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null) best = c;
            }
        }
        try { if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCb); } catch (Throwable ignored) { }
        mediaCtl = best;
        try { if (best != null) best.registerCallback(mediaCb, handler); } catch (Throwable ignored) { }
        refreshMedia();
    }

    private static String fmtTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private void refreshMedia() {
        if (destroyed) return;
        MediaMetadata md = null;
        PlaybackState st = null;
        try {
            if (mediaCtl != null) { md = mediaCtl.getMetadata(); st = mediaCtl.getPlaybackState(); }
        } catch (Throwable ignored) { }
        String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null && md != null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        int state = st == null ? PlaybackState.STATE_NONE : st.getState();
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        boolean active = title != null && (playing || state == PlaybackState.STATE_PAUSED);

        if (mediaChip != null) {
            mediaChip.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) {
                chipTitle.setText(artist != null && !artist.isEmpty() ? title + " • " + artist : title);
                chipPlay.setType(playing ? Glyph.PAUSE : Glyph.PLAY);
            }
        }
        if (qpTitle != null) {
            qpTitle.setText(active ? title : "Nada tocando");
            qpArtist.setText(active && artist != null ? artist : "");
            qpPlay.setType(playing ? Glyph.PAUSE : Glyph.PLAY);
            String src = "";
            try {
                if (mediaCtl != null)
                    src = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(mediaCtl.getPackageName(), 0)));
            } catch (Throwable ignored) { }
            qpSrc.setText(active ? src : "");
            Bitmap art = null;
            if (md != null) {
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            qpArt.setImageBitmap(active ? art : null);
            updateMediaProgress();
        }
    }

    private void updateMediaProgress() {
        if (qpBar == null || qpT1 == null || qpT2 == null) return;
        long dur = 0, pos = 0;
        try {
            MediaMetadata md = mediaCtl == null ? null : mediaCtl.getMetadata();
            PlaybackState st = mediaCtl == null ? null : mediaCtl.getPlaybackState();
            if (md != null) dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (st != null) {
                pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING) {
                    long dt = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
                    pos += (long) (dt * st.getPlaybackSpeed());
                }
            }
        } catch (Throwable ignored) { }
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        if (!qpSeeking) {
            qpBar.setMax((int) (dur / 1000));
            qpBar.setProgress((int) (pos / 1000));
            qpT1.setText(fmtTime(pos));
        }
        qpT2.setText(dur > 0 ? fmtTime(dur) : "--:--");
    }

    private void mediaPrev() {
        try { if (mediaCtl != null) mediaCtl.getTransportControls().skipToPrevious(); } catch (Throwable ignored) { }
    }

    private void mediaNext() {
        try { if (mediaCtl != null) mediaCtl.getTransportControls().skipToNext(); } catch (Throwable ignored) { }
    }

    private void mediaToggle() {
        if (mediaCtl == null) return;
        try {
            PlaybackState st = mediaCtl.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            if (playing) mediaCtl.getTransportControls().pause();
            else mediaCtl.getTransportControls().play();
        } catch (Throwable ignored) { }
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
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        batteryPct = lv * 100 / sc;
        batteryCharging = st == BatteryManager.BATTERY_STATUS_CHARGING
                || st == BatteryManager.BATTERY_STATUS_FULL;
        updateBatteryViews();
    }

    private String batteryStateText() {
        if (batteryCharging) return batteryPct >= 100 ? "Carregada" : "Carregando";
        return batteryPct <= 15 ? "Bateria fraca" : "Usando bateria";
    }

    private void updateBatteryViews() {
        if (batteryPct < 0) return;
        if (tbBattery != null) tbBattery.setLevel(batteryPct, batteryCharging);
        if (tbBatteryText != null) tbBatteryText.setText(batteryPct + "%");
        if (qpBatt != null) qpBatt.setLevel(batteryPct, batteryCharging);
        if (qpPct != null) qpPct.setText(batteryPct + "%");
        if (qpBattState != null) qpBattState.setText(batteryStateText());
    }

    private void updateNet() {
        runOnUiThread(() -> {
            boolean wifi = false;
            try {
                Network n = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
                wifi = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } catch (Exception ignored) { }
            wifiOn = wifi;
            if (wifiGlyph != null) {
                wifiGlyph.setDim(!wifi);
                wifiGlyph.setLevel(wifi ? Math.max(1, wifiLevel()) : -1);
            }
            if (quickPanel != null) refreshWifiList();
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
        iconFlow.clearIcons();
        gridPkgs.clear();
        int shown = 0;
        for (String pkg : shortcuts) {
            final AppEntry e = find(pkg);
            if (e == null) continue;
            shown++;
            View cell = makeIconCell(e);
            attachIconTouch(cell, e);
            iconFlow.addIcon(cell, iconsFree ? iconPos.get(pkg) : null);
            gridPkgs.add(pkg);
        }
        emptyHint.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    /** Toque: abre. Segurar parado: menu. Segurar e arrastar: move o ícone (se "arrastar ícones" estiver ligado). */
    private void attachIconTouch(final View cell, final AppEntry e) {
        final float[] down = new float[2];
        final boolean[] st = new boolean[2];   // st[0] = arrastando, st[1] = menu já aberto
        final int slop = dp(8);
        final Runnable longPress = () -> { if (!st[0]) { st[1] = true; showAppMenu(e); } };
        cell.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX(); down[1] = ev.getRawY();
                    st[0] = false; st[1] = false;
                    handler.postDelayed(longPress, 450);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - down[0], dy = ev.getRawY() - down[1];
                    if (!st[0] && !st[1] && iconsFree && Math.hypot(dx, dy) > slop) {
                        st[0] = true;
                        handler.removeCallbacks(longPress);
                        v.bringToFront();
                        v.setAlpha(0.75f);
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (st[0]) { v.setTranslationX(dx); v.setTranslationY(dy); }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (st[0]) iconFlow.drop(v);
                    else if (!st[1]) launchApp(e.pkg);
                    st[0] = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    if (st[0]) { v.setTranslationX(0); v.setTranslationY(0); v.setAlpha(1f); }
                    st[0] = false;
                    return true;
                default:
                    return true;
            }
        });
    }

    /** A grade avisa que um ícone foi solto: guarda a posição de todos (nada "pula" depois). */
    private void onIconsMoved() {
        for (int i = 0; i < gridPkgs.size(); i++) {
            int[] c = iconFlow.cellOf(i);
            if (c != null) iconPos.put(gridPkgs.get(i), c);
        }
        saveState();
    }

    private void showDesktopMenu() {
        final String freeTxt = iconsFree ? "Arrastar ícones: ligado (tocar para desligar)"
                                         : "Arrastar ícones: desligado (tocar para ligar)";
        final String autoTxt = autoAdd ? "Navegadores/jogos novos no desktop: automático (tocar para desligar)"
                                       : "Navegadores/jogos novos no desktop: desligado (tocar para ligar)";
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Interface: modo DeX / modo Windows…", "Papel de parede…", "Arquivos",
                                "Todos os apps", "Adicionar navegadores e jogos ao desktop", autoTxt,
                                "Organizar ícones automaticamente", freeTxt, "Sobre / versão"},
                        (d, which) -> {
                            switch (which) {
                                case 0: showUiModeChooser(false); break;
                                case 1: showWallpaperMenu(); break;
                                case 2: openFiles(); break;
                                case 3: showDrawer(); break;
                                case 4: {
                                    int n = autoAddApps(true);
                                    refreshDesktopIcons();
                                    toast(n > 0 ? n + " app(s) adicionados ao desktop" : "Navegadores e jogos já estão no desktop");
                                    break;
                                }
                                case 5:
                                    autoAdd = !autoAdd;
                                    prefs.edit().putBoolean(KEY_AUTOADD, autoAdd).apply();
                                    toast(autoAdd ? "Novos navegadores/jogos entram sozinhos no desktop"
                                                  : "Novos navegadores/jogos não entram mais sozinhos");
                                    break;
                                case 6:
                                    iconPos.clear();
                                    saveState();
                                    refreshDesktopIcons();
                                    toast("Ícones organizados");
                                    break;
                                case 7:
                                    iconsFree = !iconsFree;
                                    prefs.edit().putBoolean(KEY_FREE, iconsFree).apply();
                                    refreshDesktopIcons();
                                    toast(iconsFree ? "Arrastar ícones: ligado" : "Arrastar ícones: desligado (organização automática)");
                                    break;
                                default: showAbout(); break;
                            }
                        }).show();
    }

    // ---- escolha da interface

    /** Primeira abertura (ou pelo menu do desktop): "Modo DeX" ou "Modo Windows". */
    private void showUiModeChooser(final boolean firstRun) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackgroundColor(0xFF1C1F24);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int k = 0; k < 2; k++) {
            final boolean win = k == 1;
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(8), dp(8), dp(8), dp(10));
            boolean current = prefs.contains(KEY_UI) && win == winMode;
            c.setBackground(roundRect(current ? 0x3358A6FF : 0x22FFFFFF, dp(12)));
            c.addView(new ModePreview(this, win), new LinearLayout.LayoutParams(dp(230), dp(128)));
            TextView t = tv((win ? "Modo Windows" : "Modo DeX") + (current ? "  (atual)" : ""), 16, Color.WHITE, true);
            t.setPadding(dp(4), dp(10), dp(4), dp(2));
            c.addView(t);
            TextView d = tv(win
                    ? "Barra de tarefas centralizada com botão Iniciar, menu Iniciar com apps fixados, hora e data no canto."
                    : "Visual do Samsung DeX: atalhos à esquerda, apps no centro, menu de apps em tela cheia e bandeja completa.",
                    12, 0xBBFFFFFF, false);
            d.setPadding(dp(4), 0, dp(4), 0);
            c.addView(d, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT));
            c.setOnClickListener(v -> {
                setUiMode(win);
                if (dlg[0] != null) dlg[0].dismiss();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = k == 0 ? dp(12) : 0;
            box.addView(c, clp);
        }
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Como você quer o seu desktop?" : "Interface")
                .setView(box)
                .setCancelable(!firstRun)
                .create();
        dlg[0].show();
    }

    private void setUiMode(boolean win) {
        boolean changed = win != winMode;
        winMode = win;
        SharedPreferences.Editor ed = prefs.edit().putString(KEY_UI, win ? "win" : "dex");
        // Sem papel de parede escolhido: no modo Windows usa o azul claro.
        if (win && !prefs.contains(KEY_WALL)) ed.putString(KEY_WALL, "preset:5");
        ed.apply();
        if (changed) applyUiMode(); else logState();
        toast(win ? "Modo Windows" : "Modo DeX");
    }

    /** Reconstrói a interface (barra, menu Iniciar, ícones) sem reiniciar o app; as janelas abertas continuam. */
    private void applyUiMode() {
        closeOverlays();
        lastArea = "";
        setContentView(buildUi());
        applyWallpaper();
        refreshDesktopIcons();
        refreshTaskbar();
        updateBatteryViews();
        updateNet();
        updateVolumeGlyph();
        refreshMedia();
        logState();
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

    /**
     * Painel da janela (segurar o ícone na barra): linha de controles igual à janela do DeX
     * (voltar, fixar, minimizar, maximizar/restaurar, fechar) + layouts de encaixe com miniaturas.
     */
    private void showWindowPanel(View anchor, final AppEntry e) {
        closeOverlays();
        final String pkg = e.pkg;
        final int panelW = dp(372);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackground(roundRect(0xF2181C26, dp(14)));
        panel.setClickable(true);

        LinearLayout head = row();
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(iconOf(e));
        head.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = tv(String.valueOf(e.label), 13, Color.WHITE, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(8), 0, dp(6), 0);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int sz = dp(34);
        head.addView(wctl(Glyph.BACK, -1, v -> { closeOverlays(); backIn(pkg); }, false),
                new LinearLayout.LayoutParams(sz, sz));
        head.addView(wctl(Glyph.PIN, pinned.contains(pkg) ? 1 : 0,
                v -> { closeOverlays(); togglePin(pkg); toast(pinned.contains(pkg) ? "Fixado na barra" : "Desafixado da barra"); }, false),
                new LinearLayout.LayoutParams(sz, sz));
        head.addView(wctl(Glyph.MINIMIZE, -1, v -> { closeOverlays(); showDesktop(); }, false),
                new LinearLayout.LayoutParams(sz, sz));
        head.addView(wctl(maxed.contains(pkg) ? Glyph.RESTORE : Glyph.MAXIMIZE, -1,
                v -> { closeOverlays(); toggleMaximize(pkg); }, false),
                new LinearLayout.LayoutParams(sz, sz));
        head.addView(wctl(Glyph.CLOSE, -1, v -> closeApp(pkg), false),
                new LinearLayout.LayoutParams(sz, sz));
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lbl = tv("Organizar janela", 11, 0x99FFFFFF, false);
        lbl.setPadding(dp(2), dp(12), 0, dp(6));
        panel.addView(lbl);

        // l, t, r, b em frações da área útil: metades, quartos, centro, grande, compacto (formato celular), maximizar
        final float[][] L = {
                {0f, 0f, .5f, 1f}, {.5f, 0f, 1f, 1f}, {0f, 0f, 1f, .5f}, {0f, .5f, 1f, 1f},
                {0f, 0f, .5f, .5f}, {.5f, 0f, 1f, .5f}, {0f, .5f, .5f, 1f}, {.5f, .5f, 1f, 1f},
                {.19f, .11f, .81f, .89f}, {.075f, .06f, .925f, .94f}, {.36f, .09f, .64f, .91f}, {0f, 0f, 1f, 1f}};
        LinearLayout gridRow = null;
        for (int i = 0; i < L.length; i++) {
            if (i % 6 == 0) {
                gridRow = row();
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                glp.bottomMargin = dp(6);
                panel.addView(gridRow, glp);
            }
            final int idx = i;
            SnapIcon si = new SnapIcon(this, L[i]);
            si.setOnHoverListener((v, ev) -> {
                int a = ev.getAction();
                if (a == MotionEvent.ACTION_HOVER_ENTER) v.setBackground(roundRect(0x33FFFFFF, dp(6)));
                else if (a == MotionEvent.ACTION_HOVER_EXIT) v.setBackground(null);
                return false;
            });
            si.setOnClickListener(v -> {
                closeOverlays();
                float[] f = L[idx];
                if (idx == L.length - 1) place(pkg, areaBounds(0f, 1f), MODE_MAX);
                else place(pkg, areaFrac(f[0], f[1], f[2], f[3]), MODE_WINDOW);
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(52), dp(38));
            slp.rightMargin = dp(4);
            gridRow.addView(si, slp);
        }

        LinearLayout foot = row();
        foot.addView(textBtn("Tamanho personalizado…", v -> { closeOverlays(); showCustomSize(e); }));
        TextView full = textBtn("Tela cheia", v -> { closeOverlays(); place(pkg, null, MODE_FULL); });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.leftMargin = dp(8);
        foot.addView(full, fl);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(4);
        panel.addView(foot, footLp);

        int[] a = new int[2], r = new int[2];
        anchor.getLocationOnScreen(a);
        rootFrame.getLocationOnScreen(r);
        int cx = a[0] - r[0] + anchor.getWidth() / 2;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.max(dp(8), Math.min(cx - panelW / 2, rootFrame.getWidth() - panelW - dp(8)));
        lp.bottomMargin = dp(barH() + 6);
        windowPanel = panel;
        rootFrame.addView(panel, lp);
    }

    /** Maximiza (área acima da barra) ou restaura o tamanho de antes. */
    private void toggleMaximize(String pkg) {
        if (maxed.contains(pkg)) {
            Rect r = restoreBounds.get(pkg);
            place(pkg, r != null ? r : centeredBounds(0.62f, 0.78f), MODE_WINDOW);
        } else {
            place(pkg, areaBounds(0f, 1f), MODE_MAX);
        }
    }

    /** "Voltar" dentro de um app: traz o app para frente e manda o Voltar para a tela virtual. */
    private void backIn(String pkg) {
        if (!pkg.equals(activePkg) || desktopShown) { lastLaunch = 0; launchApp(pkg); }
        handler.postDelayed(() -> Log.i(TAG, "cmd=back display=" + displayId()), 500);
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
        // Jogos sem modo escolhido por você abrem em TELA CHEIA: abrir um jogo em janela com tamanho forçado
        // recria a superfície dele (tela preta/travamento).
        return m == null ? (isGame(pkg) ? MODE_FULL : MODE_WINDOW) : m;
    }

    private final java.util.HashMap<String, Boolean> gameCache = new java.util.HashMap<>();

    /** Jogo = marcado como jogo pelo Android (categoria/flag) ou um dos jogos conhecidos. */
    private boolean isGame(String pkg) {
        Boolean c = gameCache.get(pkg);
        if (c != null) return c;
        boolean g = false;
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            g = ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                    || (ai.flags & android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (Throwable ignored) { }
        if (!g) {
            g = pkg.equals("com.axlebolt.standoff2") || pkg.equals("com.activision.callofduty.shooter")
                    || pkg.equals("com.garena.game.codm") || pkg.equals("com.tencent.tmgp.cod")
                    || pkg.equals("com.rockstargames.gtasa");
        }
        gameCache.put(pkg, g);
        return g;
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
        if (apply) {
            if (mode == MODE_MAX) maxed.add(pkg); else maxed.remove(pkg);
            if (mode == MODE_WINDOW && bounds != null) restoreBounds.put(pkg, new Rect(bounds));
        }
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
        maxed.remove(pkg);
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

    /**
     * Ícones do desktop numa grade a partir do canto superior esquerdo (colunas de cima para baixo, como no Windows).
     * Cada ícone pode ter uma célula pedida; se ela não existir mais (tela menor) ou estiver ocupada, o ícone vai
     * para a primeira célula livre. Assim nada fica fora da tela nem sobreposto, em qualquer resolução.
     */
    private static class IconGrid extends ViewGroup {
        private final int cellW, cellH, pad;
        private int cols = 1, rows = 1;
        private final List<int[]> want = new ArrayList<>();   // célula pedida por ícone ({-1,-1} = automática)
        private int[][] eff = new int[0][];                   // célula efetiva (já ajustada à tela)
        Runnable onMoved;

        IconGrid(Context c, int cellW, int cellH, int pad) {
            super(c);
            this.cellW = cellW;
            this.cellH = cellH;
            this.pad = pad;
        }

        void addIcon(View v, int[] cell) {
            addView(v);
            want.add(cell == null ? new int[]{-1, -1} : new int[]{cell[0], cell[1]});
        }

        void clearIcons() {
            removeAllViews();
            want.clear();
            eff = new int[0][];
        }

        int[] cellOf(int i) {
            return i >= 0 && i < eff.length && eff[i] != null ? new int[]{eff[i][0], eff[i][1]} : null;
        }

        private void computeEffective(int w, int h) {
            cols = Math.max(1, (w - 2 * pad) / cellW);
            rows = Math.max(1, (h - 2 * pad) / cellH);
            int n = getChildCount();
            eff = new int[n][];
            boolean[][] used = new boolean[cols][rows];
            for (int i = 0; i < n; i++) {
                int[] p = want.get(i);
                if (p[0] >= 0 && p[0] < cols && p[1] >= 0 && p[1] < rows && !used[p[0]][p[1]]) {
                    eff[i] = new int[]{p[0], p[1]};
                    used[p[0]][p[1]] = true;
                }
            }
            for (int i = 0; i < n; i++) {
                if (eff[i] != null) continue;
                boolean placed = false;
                for (int c = 0; c < cols && !placed; c++) {
                    for (int r = 0; r < rows && !placed; r++) {
                        if (!used[c][r]) {
                            used[c][r] = true;
                            eff[i] = new int[]{c, r};
                            placed = true;
                        }
                    }
                }
                if (!placed) eff[i] = new int[]{cols - 1, rows - 1};   // mais ícones que células: sobrepõe no fim
            }
        }

        /** Soltou o ícone: encaixa na célula mais próxima (se ocupada, troca de lugar com o ocupante). */
        void drop(View v) {
            int i = indexOfChild(v);
            if (i < 0 || i >= eff.length) { v.setTranslationX(0); v.setTranslationY(0); v.setAlpha(1f); return; }
            float cx = v.getLeft() + v.getTranslationX() + cellW / 2f;
            float cy = v.getTop() + v.getTranslationY() + cellH / 2f;
            int c = Math.max(0, Math.min(cols - 1, (int) ((cx - pad) / cellW)));
            int r = Math.max(0, Math.min(rows - 1, (int) ((cy - pad) / cellH)));
            int[] from = eff[i];
            for (int j = 0; j < eff.length; j++) {
                if (j == i) continue;
                int[] e = eff[j];
                want.set(j, (e[0] == c && e[1] == r) ? new int[]{from[0], from[1]} : new int[]{e[0], e[1]});
            }
            want.set(i, new int[]{c, r});
            v.setTranslationX(0);
            v.setTranslationY(0);
            v.setAlpha(1f);
            computeEffective(getWidth(), getHeight());
            requestLayout();
            if (onMoved != null) onMoved.run();
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
            computeEffective(r - l, b - t);
            for (int i = 0; i < getChildCount(); i++) {
                int left = pad + eff[i][0] * cellW, top = pad + eff[i][1] * cellH;
                getChildAt(i).layout(left, top, left + cellW, top + cellH);
            }
        }
    }

    /** Ícones de linha brancos desenhados à mão (estilo barra do DeX / Windows). */
    private static class Glyph extends View {
        static final int GRID = 0, SEARCH = 1, RECENTS = 2, HOME = 3, BACK = 4,
                VOLUME = 5, WIFI = 6, PLAY = 7, PAUSE = 8, NEXT = 9, PREV = 10, MUSIC = 11,
                WINLOGO = 12, MINIMIZE = 13, MAXIMIZE = 14, RESTORE = 15, CLOSE = 16, PIN = 17,
                MUTE = 18, BATTERY = 19, LOCK = 20;
        private int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private boolean dim = false;
        private int level = -1;          // WIFI: arcos acesos (1..3); BATTERY: 0..100; PIN: 1 = fixado
        private boolean charging = false;

        Glyph(Context c, int type) {
            super(c);
            this.type = type;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setDim(boolean d) { dim = d; invalidate(); }
        void setType(int t) { if (t != type) { type = t; invalidate(); } }
        void setLevel(int l, boolean ch) { level = l; charging = ch; invalidate(); }
        void setLevel(int l) { setLevel(l, false); }

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
                case MUTE:
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
                    if (type == VOLUME) {
                        for (int k = 1; k <= 2; k++) {
                            float rad = 4f * k * u;
                            r.set(cx + u - rad, cy - rad, cx + u + rad, cy + rad);
                            c.drawArc(r, -45, 90, false, p);
                        }
                    } else {
                        c.drawLine(cx + 3f * u, cy - 3.5f * u, cx + 9f * u, cy + 3.5f * u, p);
                        c.drawLine(cx + 9f * u, cy - 3.5f * u, cx + 3f * u, cy + 3.5f * u, p);
                    }
                    break;
                case WIFI: {
                    p.setStyle(Paint.Style.STROKE);
                    int lit = level < 0 ? 3 : level;
                    for (int k = 1; k <= 3; k++) {
                        float rad = 4f * k * u;
                        r.set(cx - rad, cy + 5f * u - rad, cx + rad, cy + 5f * u + rad);
                        p.setAlpha(dim ? 90 : (k <= lit ? 255 : 80));
                        c.drawArc(r, 225, 90, false, p);
                    }
                    p.setAlpha(dim ? 90 : 255);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy + 5f * u, 1.6f * u, p);
                    break;
                }
                case PLAY:
                    p.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx - 4f * u, cy - 7f * u);
                    path.lineTo(cx + 7f * u, cy);
                    path.lineTo(cx - 4f * u, cy + 7f * u);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case PAUSE:
                    p.setStyle(Paint.Style.FILL);
                    r.set(cx - 5.5f * u, cy - 6.5f * u, cx - 1.8f * u, cy + 6.5f * u);
                    c.drawRoundRect(r, u, u, p);
                    r.set(cx + 1.8f * u, cy - 6.5f * u, cx + 5.5f * u, cy + 6.5f * u);
                    c.drawRoundRect(r, u, u, p);
                    break;
                case NEXT:
                    p.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx - 6f * u, cy - 6f * u);
                    path.lineTo(cx + 3f * u, cy);
                    path.lineTo(cx - 6f * u, cy + 6f * u);
                    path.close();
                    c.drawPath(path, p);
                    r.set(cx + 4.5f * u, cy - 6f * u, cx + 7f * u, cy + 6f * u);
                    c.drawRoundRect(r, u, u, p);
                    break;
                case PREV:
                    p.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx + 6f * u, cy - 6f * u);
                    path.lineTo(cx - 3f * u, cy);
                    path.lineTo(cx + 6f * u, cy + 6f * u);
                    path.close();
                    c.drawPath(path, p);
                    r.set(cx - 7f * u, cy - 6f * u, cx - 4.5f * u, cy + 6f * u);
                    c.drawRoundRect(r, u, u, p);
                    break;
                case MUSIC:
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx - 3f * u, cy + 5f * u, 3f * u, p);
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx, cy + 5f * u, cx, cy - 7f * u, p);
                    c.drawLine(cx, cy - 7f * u, cx + 6f * u, cy - 4f * u, p);
                    break;
                case WINLOGO: {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFF4CC2FF);
                    float s = 6.2f * u, g = 1.4f * u;
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            float l = cx - s - g / 2f + i * (s + g);
                            float t = cy - s - g / 2f + j * (s + g);
                            r.set(l, t, l + s, t + s);
                            c.drawRoundRect(r, .8f * u, .8f * u, p);
                        }
                    }
                    break;
                }
                case MINIMIZE:
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - 5f * u, cy + 3f * u, cx + 5f * u, cy + 3f * u, p);
                    break;
                case MAXIMIZE:
                    p.setStyle(Paint.Style.STROKE);
                    r.set(cx - 5f * u, cy - 5f * u, cx + 5f * u, cy + 5f * u);
                    c.drawRoundRect(r, 1.2f * u, 1.2f * u, p);
                    break;
                case RESTORE:
                    p.setStyle(Paint.Style.STROKE);
                    r.set(cx - 5f * u, cy - 2f * u, cx + 2f * u, cy + 5f * u);
                    c.drawRoundRect(r, 1.2f * u, 1.2f * u, p);
                    path.reset();
                    path.moveTo(cx - 2f * u, cy - 2f * u);
                    path.lineTo(cx - 2f * u, cy - 5f * u);
                    path.lineTo(cx + 5f * u, cy - 5f * u);
                    path.lineTo(cx + 5f * u, cy + 2f * u);
                    path.lineTo(cx + 2f * u, cy + 2f * u);
                    c.drawPath(path, p);
                    break;
                case CLOSE:
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - 5f * u, cy - 5f * u, cx + 5f * u, cy + 5f * u, p);
                    c.drawLine(cx + 5f * u, cy - 5f * u, cx - 5f * u, cy + 5f * u, p);
                    break;
                case PIN:
                    p.setStyle(level == 1 ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * u);
                    c.save();
                    c.rotate(45f, cx, cy);
                    r.set(cx - 3f * u, cy - 8f * u, cx + 3f * u, cy - 1f * u);
                    c.drawRoundRect(r, 1.2f * u, 1.2f * u, p);
                    c.drawLine(cx - 4.5f * u, cy - 1f * u, cx + 4.5f * u, cy - 1f * u, p);
                    c.drawLine(cx, cy - 1f * u, cx, cy + 7f * u, p);
                    c.restore();
                    break;
                case BATTERY: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * u);
                    r.set(cx - 9.5f * u, cy - 5f * u, cx + 7f * u, cy + 5f * u);
                    c.drawRoundRect(r, 2f * u, 2f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    r.set(cx + 8f * u, cy - 2f * u, cx + 9.8f * u, cy + 2f * u);
                    c.drawRoundRect(r, .6f * u, .6f * u, p);
                    int lv = Math.max(0, Math.min(100, level < 0 ? 100 : level));
                    p.setColor(charging ? 0xFFA5D6A7 : (lv <= 15 ? 0xFFFF8A80 : Color.WHITE));
                    p.setAlpha(dim ? 90 : 255);
                    float left = cx - 8f * u, maxW = 14f * u;
                    r.set(left, cy - 3.5f * u, left + Math.max(1.5f * u, maxW * lv / 100f), cy + 3.5f * u);
                    c.drawRoundRect(r, 1f * u, 1f * u, p);
                    break;
                }
                case LOCK:
                    p.setStyle(Paint.Style.FILL);
                    r.set(cx - 4.5f * u, cy - 1f * u, cx + 4.5f * u, cy + 6f * u);
                    c.drawRoundRect(r, 1.5f * u, 1.5f * u, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * u);
                    r.set(cx - 3f * u, cy - 6f * u, cx + 3f * u, cy + 1f * u);
                    c.drawArc(r, 180, 180, false, p);
                    break;
                default:
                    break;
            }
        }
    }

    /** Miniatura de um layout de encaixe: contorno da área útil + retângulo preenchido onde a janela vai ficar. */
    private static class SnapIcon extends View {
        private final float[] f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        SnapIcon(Context c, float[] f) {
            super(c);
            this.f = f;
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = 4f;
            float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(0x99FFFFFF);
            r.set(pad, pad, pad + w, pad + h);
            c.drawRoundRect(r, 6f, 6f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xDD8AB4FF);
            r.set(pad + w * f[0] + 3f, pad + h * f[1] + 3f, pad + w * f[2] - 3f, pad + h * f[3] - 3f);
            c.drawRoundRect(r, 4f, 4f, p);
        }
    }

    /** Miniatura da interface (barra + janela de exemplo) para a escolha "modo DeX" / "modo Windows". */
    private static class ModePreview extends View {
        private final boolean win;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path clip = new Path();

        ModePreview(Context c, boolean win) {
            super(c);
            this.win = win;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = h / 100f, rad = 8f * u;
            r.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, w, h,
                    win ? 0xFF1F6FEB : 0xFF0B1F4D, win ? 0xFF9CCBFF : 0xFF7C3AED, Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            p.setShader(null);

            // ícones do desktop
            p.setColor(0xCCFFFFFF);
            for (int i = 0; i < 3; i++) {
                r.set(7 * u, (8 + i * 17) * u, 15 * u, (16 + i * 17) * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
            }
            // janela de exemplo
            p.setColor(0xE61E2229);
            r.set(w * 0.34f, 14 * u, w * 0.88f, 66 * u);
            c.drawRoundRect(r, 3 * u, 3 * u, p);
            p.setColor(0x33FFFFFF);
            r.set(w * 0.34f, 14 * u, w * 0.88f, 23 * u);
            c.drawRoundRect(r, 3 * u, 3 * u, p);
            p.setColor(0xCCFFFFFF);
            for (int i = 0; i < 3; i++) c.drawCircle(w * 0.88f - (6 + i * 7) * u, 18.5f * u, 2 * u, p);

            // barra de tarefas
            float bh = 15 * u, by = h - bh, cy = h - bh / 2f;
            p.setColor(win ? 0xF01C1C20 : 0xF2101216);
            r.set(0, by, w, h);
            c.drawRect(r, p);
            if (win) {
                p.setColor(0xFF4CC2FF);
                float s = 3.2f * u, g = 0.8f * u, x0 = w / 2f - 22 * u;
                for (int i = 0; i < 2; i++)
                    for (int j = 0; j < 2; j++) {
                        float l = x0 + i * (s + g), t = cy - s - g / 2f + j * (s + g);
                        r.set(l, t, l + s, t + s);
                        c.drawRect(r, p);
                    }
                p.setColor(0xCCFFFFFF);
                for (int i = 0; i < 4; i++) c.drawCircle(w / 2f - 10 * u + i * 9 * u, cy, 2.6f * u, p);
            } else {
                p.setColor(0xCCFFFFFF);
                for (int i = 0; i < 5; i++) c.drawCircle(8 * u + i * 8 * u, cy, 2.1f * u, p);
                for (int i = 0; i < 3; i++) c.drawCircle(w / 2f - 9 * u + i * 9 * u, cy, 2.6f * u, p);
            }
            p.setColor(0x99FFFFFF);
            r.set(w - 36 * u, cy - 2.5f * u, w - 6 * u, cy + 2.5f * u);
            c.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
            c.restore();
        }
    }
}
