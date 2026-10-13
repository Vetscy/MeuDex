package com.dexcustom.desktop;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Meu Dex - launcher estilo desktop para rodar na tela virtual do scrcpy.
 *
 * Técnica central (a mesma do DroidUP Dex):
 *   ActivityOptions.setLaunchDisplayId(id)  -> abre o app NA tela virtual, não no celular
 *   ActivityOptions.setLaunchBounds(rect)   -> posição/tamanho da "janela" (precisa de freeform ligado)
 */
public class MainActivity extends Activity {

    private static final String PREFS = "meudex_prefs";
    private static final String KEY_SHORTCUTS = "shortcuts";
    private static final long LAUNCH_DEBOUNCE_MS = 500;

    private static class AppEntry {
        String pkg;
        CharSequence label;
        Drawable icon;
    }

    private PackageManager pm;
    private SharedPreferences prefs;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<String> shortcuts = new ArrayList<>(); // ordem preservada
    private final List<String> running = new ArrayList<>();   // apps abertos nesta sessão

    private FrameLayout desktopArea;
    private ColumnFlow shortcutContainer;
    private LinearLayout taskbarStrip;
    private FrameLayout startMenu;
    private TextView emptyHint;

    private long lastLaunch = 0;
    private int cascade = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadApps();
        loadShortcuts();
        setContentView(buildUi());
        refreshShortcuts();
        refreshTaskbar();
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
        // Back no desktop só fecha o menu Iniciar; nunca sai do launcher.
        if (startMenu != null && startMenu.getVisibility() == View.VISIBLE) {
            startMenu.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ dados

    private void loadApps() {
        allApps.clear();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue; // não lista o próprio launcher
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = ri.loadLabel(pm);
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
        String raw = prefs.getString(KEY_SHORTCUTS, "");
        for (String p : raw.split(",")) {
            if (!p.isEmpty() && find(p) != null) shortcuts.add(p);
        }
    }

    private void saveShortcuts() {
        prefs.edit().putString(KEY_SHORTCUTS, String.join(",", shortcuts)).apply();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Área do desktop com papel de parede em gradiente
        desktopArea = new FrameLayout(this);
        GradientDrawable wallpaper = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1F4D, 0xFF1D4ED8, 0xFF7C3AED});
        desktopArea.setBackground(wallpaper);
        root.addView(desktopArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        shortcutContainer = new ColumnFlow(this, dp(96), dp(108));
        desktopArea.addView(shortcutContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyHint = new TextView(this);
        emptyHint.setText("Abra o menu Iniciar e segure um app para colocá-lo no desktop");
        emptyHint.setTextColor(0xAAFFFFFF);
        emptyHint.setTextSize(14);
        emptyHint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        desktopArea.addView(emptyHint, hintLp);

        buildStartMenu();

        // Clicar no fundo vazio fecha o menu Iniciar
        desktopArea.setOnClickListener(v -> startMenu.setVisibility(View.GONE));

        root.addView(buildTaskbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return root;
    }

    private View buildTaskbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xF0101624);
        bar.setPadding(dp(8), 0, dp(12), 0);

        TextView start = new TextView(this);
        start.setText("⊞");
        start.setTextColor(Color.WHITE);
        start.setTextSize(24);
        start.setGravity(Gravity.CENTER);
        start.setBackground(roundRect(0xFF2563EB, dp(8)));
        start.setOnClickListener(v -> toggleStartMenu());
        bar.addView(start, new LinearLayout.LayoutParams(dp(44), dp(40)));

        taskbarStrip = new LinearLayout(this);
        taskbarStrip.setOrientation(LinearLayout.HORIZONTAL);
        taskbarStrip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        stripLp.leftMargin = dp(10);
        bar.addView(taskbarStrip, stripLp);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm\ndd/MM/yyyy");
        clock.setFormat12Hour("HH:mm\ndd/MM/yyyy");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(11);
        clock.setGravity(Gravity.CENTER);
        bar.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void buildStartMenu() {
        startMenu = new FrameLayout(this);
        startMenu.setBackground(roundRect(0xF2141B2D, dp(14)));
        startMenu.setPadding(dp(14), dp(14), dp(14), dp(14));
        startMenu.setVisibility(View.GONE);
        startMenu.setClickable(true); // não deixa o clique vazar para o desktop

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(8));
        grid.setAdapter(new StartMenuAdapter());
        startMenu.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(440), dp(500),
                Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = dp(10);
        lp.bottomMargin = dp(10);
        desktopArea.addView(startMenu, lp);
    }

    private void toggleStartMenu() {
        startMenu.setVisibility(startMenu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private View makeIconCell(AppEntry e, boolean dark) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(6), dp(4), dp(4));

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(e.icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView tv = new TextView(this);
        tv.setText(e.label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        if (!dark) tv.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        cell.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private void refreshShortcuts() {
        shortcutContainer.removeAllViews();
        for (String pkg : shortcuts) {
            AppEntry e = find(pkg);
            if (e == null) continue;
            View cell = makeIconCell(e, false);
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

    private void refreshTaskbar() {
        taskbarStrip.removeAllViews();
        for (String pkg : running) {
            AppEntry e = find(pkg);
            if (e == null) continue;
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            iv.setPadding(dp(6), dp(6), dp(6), dp(6));
            iv.setBackground(roundRect(0x33FFFFFF, dp(8)));
            iv.setOnClickListener(v -> launch(pkg, null));            // traz para frente
            iv.setOnLongClickListener(v -> { showWindowMenu(e); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
            lp.rightMargin = dp(6);
            taskbarStrip.addView(iv, lp);
        }
    }

    private void showWindowMenu(AppEntry e) {
        String[] items = {"Trazer para frente", "Maximizar", "Metade esquerda", "Metade direita", "Fechar"};
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: launch(e.pkg, null); break;
                        case 1: launch(e.pkg, areaBounds(0f, 1f)); break;
                        case 2: launch(e.pkg, areaBounds(0f, 0.5f)); break;
                        case 3: launch(e.pkg, areaBounds(0.5f, 1f)); break;
                        case 4: closeApp(e.pkg); break;
                    }
                }).show();
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

    /** Retângulo (coordenadas de tela) cobrindo uma faixa horizontal da área do desktop. */
    private Rect areaBounds(float from, float to) {
        int[] loc = new int[2];
        desktopArea.getLocationOnScreen(loc);
        int w = desktopArea.getWidth(), h = desktopArea.getHeight();
        return new Rect(loc[0] + (int) (w * from), loc[1],
                loc[0] + (int) (w * to), loc[1] + h);
    }

    /** Janelas novas abrem em cascata, como no Windows. */
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
        if (now - lastLaunch < LAUNCH_DEBOUNCE_MS) return; // evita duplo-clique abrir 2 vezes
        lastLaunch = now;
        startMenu.setVisibility(View.GONE);

        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Toast.makeText(this, "Não foi possível abrir este app", Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId());
        if (bounds != null) opts.setLaunchBounds(bounds); // ignorado se o aparelho não suportar freeform

        try {
            startActivity(intent, opts.toBundle());
            if (!running.contains(pkg)) running.add(pkg);
            refreshTaskbar();
        } catch (Exception ex) {
            Toast.makeText(this, "Falha ao abrir: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void bringLauncherToFront() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId());
        startActivity(i, opts.toBundle());
    }

    /**
     * killBackgroundProcesses só mata apps que NÃO estão em primeiro plano,
     * então primeiro trazemos o launcher para frente e só depois matamos o app.
     */
    private void closeApp(String pkg) {
        bringLauncherToFront();
        handler.postDelayed(() -> {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(pkg);
            running.remove(pkg);
            refreshTaskbar();
        }, 450);
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // ------------------------------------------------------------------ componentes

    /** Menu Iniciar: grade com todos os apps. Toque abre; segurar adiciona ao desktop. */
    private class StartMenuAdapter extends BaseAdapter {
        @Override public int getCount() { return allApps.size(); }
        @Override public Object getItem(int i) { return allApps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry e = allApps.get(position);
            View cell = makeIconCell(e, true);
            cell.setOnClickListener(v -> launch(e.pkg, cascadeBounds()));
            cell.setOnLongClickListener(v -> {
                if (shortcuts.contains(e.pkg)) {
                    Toast.makeText(MainActivity.this, "Já está no desktop", Toast.LENGTH_SHORT).show();
                } else {
                    shortcuts.add(e.pkg);
                    saveShortcuts();
                    refreshShortcuts();
                    Toast.makeText(MainActivity.this, "Adicionado ao desktop: " + e.label,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            return cell;
        }
    }

    /** Ícones do desktop fluem de cima para baixo e quebram para a próxima coluna (como o Windows). */
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
}
