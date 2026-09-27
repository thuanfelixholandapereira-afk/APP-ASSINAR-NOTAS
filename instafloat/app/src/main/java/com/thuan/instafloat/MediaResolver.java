package com.thuan.instafloat;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class MediaResolver {
    private MediaResolver() {}

    public interface Callback {
        void onResult(Result result);
        void onError(String message);
    }

    public static class Result {
        public final String url;
        public final boolean video;
        Result(String url, boolean video) { this.url = url; this.video = video; }
    }

    public static void resolve(String instagramUrl, Callback callback) {
        new Thread(() -> {
            try {
                Document doc = Jsoup.connect(instagramUrl)
                        .userAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                        .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7")
                        .timeout(15000)
                        .followRedirects(true)
                        .get();

                String video = content(doc, "meta[property=og:video:secure_url]");
                if (TextUtils.isEmpty(video)) video = content(doc, "meta[property=og:video]");
                if (!TextUtils.isEmpty(video)) { callback.onResult(new Result(video, true)); return; }

                String image = content(doc, "meta[property=og:image:secure_url]");
                if (TextUtils.isEmpty(image)) image = content(doc, "meta[property=og:image]");
                if (!TextUtils.isEmpty(image)) { callback.onResult(new Result(image, false)); return; }

                callback.onResult(null);
            } catch (Exception e) {
                String message = e.getMessage();
                callback.onError("Não foi possível analisar esse link agora" + (TextUtils.isEmpty(message) ? "." : ": " + message));
            }
        }).start();
    }

    private static String content(Document doc, String selector) {
        Element e = doc.selectFirst(selector);
        return e == null ? null : e.attr("content");
    }

    public static void download(Context context, Result result) {
        try {
            String extension = result.video ? ".mp4" : ".jpg";
            String fileName = "InstaFloat_" + System.currentTimeMillis() + extension;
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(result.url));
            request.setTitle(result.video ? "Vídeo do Instagram" : "Foto do Instagram");
            request.setDescription(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "InstaFloat/" + fileName);
            manager.enqueue(request);
            Toast.makeText(context, "Download iniciado em Downloads/InstaFloat", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "Não foi possível iniciar o download", Toast.LENGTH_LONG).show();
        }
    }
}
