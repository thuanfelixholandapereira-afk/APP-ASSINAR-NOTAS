package com.thuan.instafloat;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaResolver {
    private static final String UA = "Mozilla/5.0 (Linux; Android 15; SM-A556E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36";
    private static final String IG_APP_ID = "936619743392459";

    private MediaResolver() {}

    public interface Callback {
        void onResult(Result result);
        void onError(String message);
    }

    public interface MultiCallback {
        void onResult(List<Result> results);
        void onError(String message);
    }

    public static class Result {
        public final String url;
        public final boolean video;
        public final int width;
        public final int height;

        Result(String url, boolean video) {
            this(url, video, 0, 0);
        }

        Result(String url, boolean video, int width, int height) {
            this.url = url;
            this.video = video;
            this.width = width;
            this.height = height;
        }

        long score() {
            long area = (long) width * (long) height;
            return area > 0 ? area : 1;
        }
    }

    public static void resolve(String instagramUrl, Callback callback) {
        resolveAll(instagramUrl, new MultiCallback() {
            @Override public void onResult(List<Result> results) {
                if (results == null || results.isEmpty()) {
                    callback.onResult(null);
                    return;
                }
                Result best = results.get(0);
                for (Result r : results) {
                    if (r.video && !best.video) best = r;
                    else if (r.video == best.video && r.score() > best.score()) best = r;
                }
                callback.onResult(best);
            }

            @Override public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    public static void resolveAll(String instagramUrl, MultiCallback callback) {
        new Thread(() -> {
            try {
                String normalized = normalizeInstagramUrl(instagramUrl);
                if (normalized == null) {
                    callback.onError("Link do Instagram inválido.");
                    return;
                }

                LinkedHashMap<String, Result> found = new LinkedHashMap<>();

                fetchWebPage(normalized, found);

                // Instagram sometimes returns richer JSON through the legacy web response flag.
                if (found.isEmpty()) {
                    String separator = normalized.contains("?") ? "&" : "?";
                    fetchJsonEndpoint(normalized + separator + "__a=1&__d=dis", found);
                }

                // A Story URL contains a numeric media id. For public media, the web media-info
                // endpoint can expose the same CDN file Instagram serves to the app/browser.
                String storyId = storyMediaId(normalized);
                if (!TextUtils.isEmpty(storyId)) {
                    fetchJsonEndpoint("https://www.instagram.com/api/v1/media/" + storyId + "/info/", found);
                }

                // Public post/reel fallback: decode the shortcode to its numeric media id.
                String shortcode = shortcode(normalized);
                if (!TextUtils.isEmpty(shortcode)) {
                    String mediaId = shortcodeToMediaId(shortcode);
                    if (!TextUtils.isEmpty(mediaId)) {
                        fetchJsonEndpoint("https://www.instagram.com/api/v1/media/" + mediaId + "/info/", found);
                    }
                }

                List<Result> results = collapseBestVariants(new ArrayList<>(found.values()));
                if (results.isEmpty()) {
                    callback.onResult(results);
                } else {
                    callback.onResult(results);
                }
            } catch (Exception e) {
                String message = e.getMessage();
                callback.onError("Não foi possível localizar a mídia original agora" +
                        (TextUtils.isEmpty(message) ? "." : ": " + message));
            }
        }).start();
    }

    private static void fetchWebPage(String url, Map<String, Result> found) {
        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7")
                    .header("Referer", "https://www.instagram.com/")
                    .header("X-IG-App-ID", IG_APP_ID)
                    .timeout(18000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute();

            String html = response.body();
            Document doc = response.parse();

            addMeta(doc, found, "meta[property=og:video:secure_url]", true);
            addMeta(doc, found, "meta[property=og:video]", true);
            addMeta(doc, found, "meta[property=og:image:secure_url]", false);
            addMeta(doc, found, "meta[property=og:image]", false);

            for (Element script : doc.select("script[type=application/json],script[type=application/ld+json]")) {
                String json = script.data();
                if (TextUtils.isEmpty(json)) json = script.html();
                parseJsonIfPossible(json, found);
            }

            extractFromRawText(html, found);
        } catch (Exception ignored) {}
    }

    private static void fetchJsonEndpoint(String url, Map<String, Result> found) {
        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7")
                    .header("Referer", "https://www.instagram.com/")
                    .header("X-IG-App-ID", IG_APP_ID)
                    .timeout(15000)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute();
            String body = response.body();
            parseJsonIfPossible(body, found);
            extractFromRawText(body, found);
        } catch (Exception ignored) {}
    }

    private static void parseJsonIfPossible(String text, Map<String, Result> found) {
        if (TextUtils.isEmpty(text)) return;
        String trimmed = text.trim();
        try {
            if (trimmed.startsWith("{")) walkJson(new JSONObject(trimmed), found);
            else if (trimmed.startsWith("[")) walkJson(new JSONArray(trimmed), found);
        } catch (Exception ignored) {}
    }

    private static void walkJson(Object node, Map<String, Result> found) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;

            // Native Instagram media objects expose video_versions and image_versions2.
            JSONArray videos = obj.optJSONArray("video_versions");
            if (videos != null && videos.length() > 0) {
                Result bestVideo = bestCandidate(videos, true);
                add(found, bestVideo);
            } else {
                JSONObject imageVersions = obj.optJSONObject("image_versions2");
                if (imageVersions != null) {
                    JSONArray candidates = imageVersions.optJSONArray("candidates");
                    Result bestImage = bestCandidate(candidates, false);
                    add(found, bestImage);
                }
            }

            String videoUrl = cleanUrl(obj.optString("video_url", null));
            if (isInstagramCdn(videoUrl)) {
                int w = obj.optInt("width", 0);
                int h = obj.optInt("height", 0);
                JSONObject dimensions = obj.optJSONObject("dimensions");
                if (dimensions != null) {
                    w = dimensions.optInt("width", w);
                    h = dimensions.optInt("height", h);
                }
                add(found, new Result(videoUrl, true, w, h));
            }

            String displayUrl = cleanUrl(obj.optString("display_url", null));
            if (isInstagramCdn(displayUrl) && TextUtils.isEmpty(videoUrl)) {
                JSONObject dimensions = obj.optJSONObject("dimensions");
                int w = dimensions == null ? obj.optInt("width", 0) : dimensions.optInt("width", 0);
                int h = dimensions == null ? obj.optInt("height", 0) : dimensions.optInt("height", 0);
                add(found, new Result(displayUrl, false, w, h));
            }

            JSONArray carousel = obj.optJSONArray("carousel_media");
            if (carousel != null) walkJson(carousel, found);

            JSONObject sidecar = obj.optJSONObject("edge_sidecar_to_children");
            if (sidecar != null) walkJson(sidecar, found);

            JSONArray edges = obj.optJSONArray("edges");
            if (edges != null) walkJson(edges, found);

            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    if ("video_versions".equals(key) || "image_versions2".equals(key) ||
                            "carousel_media".equals(key) || "edge_sidecar_to_children".equals(key) ||
                            "edges".equals(key)) continue;
                    Object child = obj.opt(key);
                    if (child instanceof JSONObject || child instanceof JSONArray) walkJson(child, found);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) walkJson(child, found);
            }
        }
    }

    private static Result bestCandidate(JSONArray candidates, boolean video) {
        if (candidates == null) return null;
        Result best = null;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null) continue;
            String url = cleanUrl(c.optString("url", null));
            if (!isInstagramCdn(url)) continue;
            Result r = new Result(url, video, c.optInt("width", 0), c.optInt("height", 0));
            if (best == null || r.score() > best.score()) best = r;
        }
        return best;
    }

    private static void addMeta(Document doc, Map<String, Result> found, String selector, boolean video) {
        Element e = doc.selectFirst(selector);
        if (e == null) return;
        String url = cleanUrl(e.attr("content"));
        if (!TextUtils.isEmpty(url)) add(found, new Result(url, video));
    }

    private static void extractFromRawText(String text, Map<String, Result> found) {
        if (TextUtils.isEmpty(text)) return;
        extractKeyUrls(text, "video_url", true, found);
        extractKeyUrls(text, "display_url", false, found);

        Pattern p = Pattern.compile("\\{[^{}]{0,180}?\\\"width\\\"\\s*:\\s*(\\d+)[^{}]{0,120}?\\\"height\\\"\\s*:\\s*(\\d+)[^{}]{0,240}?\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            int w = safeInt(m.group(1));
            int h = safeInt(m.group(2));
            String url = cleanUrl(m.group(3));
            if (isInstagramCdn(url) && (long) w * h >= 250_000L) {
                boolean video = looksLikeVideo(url);
                add(found, new Result(url, video, w, h));
            }
        }
    }

    private static void extractKeyUrls(String text, String key, boolean video, Map<String, Result> found) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String url = cleanUrl(m.group(1));
            if (isInstagramCdn(url)) add(found, new Result(url, video));
        }
    }

    private static List<Result> collapseBestVariants(List<Result> input) {
        LinkedHashMap<String, Result> unique = new LinkedHashMap<>();
        for (Result r : input) {
            if (r == null || TextUtils.isEmpty(r.url)) continue;
            String key = stripVolatileQuery(r.url);
            Result old = unique.get(key);
            if (old == null || r.score() > old.score()) unique.put(key, r);
        }

        List<Result> values = new ArrayList<>(unique.values());

        // If a single video is present alongside its cover image, keep the video.
        int videoCount = 0;
        Result onlyVideo = null;
        for (Result r : values) if (r.video) { videoCount++; onlyVideo = r; }
        if (videoCount == 1 && values.size() <= 3) {
            List<Result> one = new ArrayList<>();
            one.add(onlyVideo);
            return one;
        }
        return values;
    }

    private static void add(Map<String, Result> found, Result result) {
        if (result == null || TextUtils.isEmpty(result.url)) return;
        String url = cleanUrl(result.url);
        if (!isInstagramCdn(url)) return;
        String key = url;
        Result old = found.get(key);
        if (old == null || result.score() > old.score()) found.put(key, new Result(url, result.video, result.width, result.height));
    }

    private static String normalizeInstagramUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String url = raw.trim();
        Matcher matcher = Pattern.compile("https?://(?:www\\.)?instagram\\.com/[^\\s<]+", Pattern.CASE_INSENSITIVE).matcher(url);
        if (matcher.find()) url = matcher.group();
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(".") || url.endsWith(",")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.toLowerCase(Locale.US).contains("instagram.com/")) return null;
        int q = url.indexOf('?');
        if (q > 0) url = url.substring(0, q);
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    private static String storyMediaId(String url) {
        Matcher m = Pattern.compile("/stories/[^/]+/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String shortcode(String url) {
        Matcher m = Pattern.compile("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String shortcodeToMediaId(String code) {
        if (TextUtils.isEmpty(code)) return null;
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.math.BigInteger value = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(64);
        for (int i = 0; i < code.length(); i++) {
            int idx = alphabet.indexOf(code.charAt(i));
            if (idx < 0) return null;
            value = value.multiply(base).add(java.math.BigInteger.valueOf(idx));
        }
        return value.toString();
    }

    private static String cleanUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String s = raw.trim();
        s = s.replace("\\u0026", "&")
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&");
        try {
            if (s.contains("%3A%2F%2F") || s.contains("%3a%2f%2f")) {
                s = URLDecoder.decode(s, StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {}
        return s;
    }

    private static boolean isInstagramCdn(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return false;
        String l = url.toLowerCase(Locale.US);
        return l.contains("cdninstagram.com") || l.contains("fbcdn.net") || l.contains("instagram.com");
    }

    private static boolean looksLikeVideo(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String l = url.toLowerCase(Locale.US);
        return l.contains(".mp4") || l.contains("video") || l.contains("/o1/") || l.contains("/t2/");
    }

    private static String stripVolatileQuery(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    public static void download(Context context, Result result) {
        List<Result> list = new ArrayList<>();
        if (result != null) list.add(result);
        downloadAll(context, list);
    }

    public static void downloadAll(Context context, List<Result> results) {
        if (results == null || results.isEmpty()) {
            Toast.makeText(context, "Nenhum arquivo de mídia encontrado", Toast.LENGTH_LONG).show();
            return;
        }

        int index = 1;
        int queued = 0;
        for (Result result : results) {
            try {
                String extension = result.video ? ".mp4" : ".jpg";
                String fileName = "InstaFloat_" + System.currentTimeMillis() + (results.size() > 1 ? "_" + index : "") + extension;
                DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(result.url));
                request.setTitle(result.video ? "Vídeo do Instagram" : "Foto do Instagram");
                request.setDescription("Qualidade original disponível no Instagram");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.addRequestHeader("User-Agent", UA);
                request.addRequestHeader("Referer", "https://www.instagram.com/");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "InstaFloat/" + fileName);
                manager.enqueue(request);
                queued++;
            } catch (Exception ignored) {}
            index++;
        }

        if (queued > 0) {
            Toast.makeText(context, queued == 1 ?
                    "Download iniciado em Downloads/InstaFloat" :
                    queued + " arquivos enviados para Downloads/InstaFloat", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "Não foi possível iniciar o download", Toast.LENGTH_LONG).show();
        }
    }
}
