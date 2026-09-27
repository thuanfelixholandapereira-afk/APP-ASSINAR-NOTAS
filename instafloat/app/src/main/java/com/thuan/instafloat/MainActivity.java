package com.thuan.instafloat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private TextView actionStatus;
    private Button downloadButton;
    private MediaResolver.Result current;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247,247,249));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(30));
        scroll.addView(root);

        root.addView(text("Insta Float", 30, true, Color.rgb(20,20,22)));
        root.addView(text("Bolha flutuante para capturar o link do conteúdo aberto no Instagram e salvar a mídia pública.", 15, false, Color.rgb(92,92,99)), gap(8));

        LinearLayout setup = card();
        root.addView(setup, gap(22));
        setup.addView(text("Configuração", 18, true, Color.rgb(25,25,27)));
        overlayStatus = text("", 14, false, Color.DKGRAY);
        setup.addView(overlayStatus, gap(12));
        Button overlay = button("1. Permitir bolha sobre outros apps", false);
        setup.addView(overlay, gap(8));
        overlay.setOnClickListener(v -> openOverlaySettings());

        accessibilityStatus = text("", 14, false, Color.DKGRAY);
        setup.addView(accessibilityStatus, gap(15));
        Button accessibility = button("2. Ativar leitura do Instagram", false);
        setup.addView(accessibility, gap(8));
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button start = button("Ativar bolha flutuante", true);
        root.addView(start, gap(18));
        start.setOnClickListener(v -> startBubble());

        Button stop = button("Desativar bolha", false);
        root.addView(stop, gap(9));
        stop.setOnClickListener(v -> stopService(new Intent(this, FloatingService.class)));

        LinearLayout content = card();
        root.addView(content, gap(22));
        content.addView(text("Conteúdo atual", 18, true, Color.rgb(25,25,27)));
        actionStatus = text("Abra um Reel, publicação ou Story e toque na bolha.", 14, false, Color.rgb(92,92,99));
        content.addView(actionStatus, gap(10));
        downloadButton = button("Baixar", true);
        downloadButton.setVisibility(View.GONE);
        content.addView(downloadButton, gap(14));
        downloadButton.setOnClickListener(v -> { if (current != null) MediaResolver.download(this, current); });
        Button clipboard = button("Usar link copiado", false);
        content.addView(clipboard, gap(9));
        clipboard.setOnClickListener(v -> readClipboard());

        root.addView(text("Sem login: o app não solicita nem armazena sua senha. Conteúdo privado continua sujeito às permissões do Instagram.", 12, false, Color.rgb(120,120,126)), gap(18));
        setContentView(scroll);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            resolve(extractInstagramUrl(intent.getStringExtra(Intent.EXTRA_TEXT)));
            return;
        }
        String link = intent.getStringExtra("link");
        if (!TextUtils.isEmpty(link)) { resolve(link); return; }
        if (intent.getBooleanExtra("process_clipboard", false)) new Handler().postDelayed(this::readClipboard, 400);
        String msg = intent.getStringExtra("message");
        if (!TextUtils.isEmpty(msg)) actionStatus.setText(msg);
    }

    private void resolve(String link) {
        if (TextUtils.isEmpty(link)) {
            actionStatus.setText("Não encontrei um link do Instagram.");
            return;
        }
        current = null;
        downloadButton.setVisibility(View.GONE);
        actionStatus.setText("Analisando o conteúdo…");
        MediaResolver.resolve(link, new MediaResolver.Callback() {
            @Override public void onResult(MediaResolver.Result result) {
                runOnUiThread(() -> {
                    current = result;
                    if (result == null) {
                        actionStatus.setText("O Instagram não expôs a mídia publicamente nesse link. Tente conteúdo público e ainda ativo.");
                    } else {
                        actionStatus.setText(result.video ? "✓ Vídeo encontrado." : "✓ Foto encontrada.");
                        downloadButton.setText(result.video ? "Baixar vídeo" : "Baixar foto");
                        downloadButton.setVisibility(View.VISIBLE);
                    }
                });
            }
            @Override public void onError(String message) { runOnUiThread(() -> actionStatus.setText(message)); }
        });
    }

    private void readClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) { actionStatus.setText("Não há link copiado."); return; }
        ClipData data = cm.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;
        CharSequence value = data.getItemAt(0).coerceToText(this);
        resolve(extractInstagramUrl(value == null ? null : value.toString()));
    }

    private String extractInstagramUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = Pattern.compile("https?://(?:www\\.)?instagram\\.com/[^\\s<]+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(".") || url.endsWith(",")) url = url.substring(0, url.length()-1);
        return url;
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void startBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { openOverlaySettings(); return; }
        Intent i = new Intent(this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Bolha ativada", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        overlayStatus.setText((Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) ? "✓ Bolha autorizada" : "• Bolha ainda não autorizada");
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = new ComponentName(this, InstaAccessibilityService.class).flattenToString();
        accessibilityStatus.setText(enabled != null && enabled.toLowerCase().contains(component.toLowerCase()) ? "✓ Leitura ativada" : "• Leitura ainda não ativada");
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE); g.setCornerRadius(dp(18)); v.setBackground(g); v.setElevation(dp(2));
        return v;
    }

    private Button button(String label, boolean dark) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setMinHeight(dp(50));
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? Color.rgb(20,20,22) : Color.rgb(238,238,241)); g.setCornerRadius(dp(14)); b.setBackground(g);
        if (dark) b.setTextColor(Color.WHITE);
        return b;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0,1.12f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams gap(int d) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(d); return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
