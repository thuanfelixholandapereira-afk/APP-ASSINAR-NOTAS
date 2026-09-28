package com.thuan.instafloat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareReceiverActivity extends Activity {
    private boolean handled;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (handled) return;
        handled = true;

        String shared = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        String link = extractInstagramUrl(shared);
        if (TextUtils.isEmpty(link)) {
            Toast.makeText(this, "Não encontrei um link do Instagram", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, "Buscando o arquivo original do Instagram…", Toast.LENGTH_SHORT).show();
        MediaResolver.resolveAll(link, new MediaResolver.MultiCallback() {
            @Override public void onResult(List<MediaResolver.Result> results) {
                runOnUiThread(() -> {
                    if (results == null || results.isEmpty()) {
                        Toast.makeText(ShareReceiverActivity.this,
                                "O Instagram não liberou a mídia desse link sem autenticação. Tente um Story/Post público e ainda ativo.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        MediaResolver.downloadAll(getApplicationContext(), results);
                    }
                    finish();
                    overridePendingTransition(0, 0);
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ShareReceiverActivity.this, message, Toast.LENGTH_LONG).show();
                    finish();
                    overridePendingTransition(0, 0);
                });
            }
        });
    }

    private String extractInstagramUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = Pattern.compile("https?://(?:www\\.)?instagram\\.com/[^\\s<]+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(".") || url.endsWith(",")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
