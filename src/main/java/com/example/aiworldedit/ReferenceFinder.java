package com.example.aiworldedit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds reference images for a build on free, openly licensed image libraries
 * (Wikimedia Commons, then Openverse), or loads images the player supplies.
 * Only small thumbnails are downloaded, one request at a time.
 * All methods block: call them off the client thread.
 */
public final class ReferenceFinder {
    private ReferenceFinder() {}

    /** One reference image with its credit line. */
    public record Ref(String title, String source, String license, String author, String pageUrl,
                      AiClient.Img image) {
        public String credit() {
            StringBuilder s = new StringBuilder("\"").append(title).append("\"");
            if (source != null && !source.isBlank()) s.append(" - ").append(source);
            if (license != null && !license.isBlank()) s.append(", ").append(license);
            if (author != null && !author.isBlank()) s.append(", by ").append(author);
            return s.toString();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** API endpoints (overridable for tests). */
    static String wikimediaApi = "https://commons.wikimedia.org/w/api.php";
    static String openverseApi = "https://api.openverse.org/v1/images/";

    private static final int THUMB_WIDTH = 500;            // a standard Wikimedia thumbnail size
    private static final int MAX_IMAGE_BYTES = 3_000_000;

    private static String userAgent() {
        String contact = Config.get().referenceContact;
        return "AIWorldEditBuilder/5.1 (Minecraft Fabric mod; personal use"
            + (contact == null || contact.isBlank() ? "" : "; " + contact.trim()) + ")";
    }

    /** Folder for images the player supplies with /airef <file>. */
    public static Path referenceFolder() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiworldedit").resolve("references");
    }

    // ---------------- search ----------------

    /** Searches the enabled sources; returns up to `max` downloaded candidates. */
    public static List<Ref> search(List<String> queries, int max) {
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String sources = Config.get().referenceSources == null ? "" : Config.get().referenceSources.toLowerCase(Locale.ROOT);
        int perQuery = Math.max(2, (max + queries.size() - 1) / Math.max(1, queries.size()));
        for (String q : queries) {
            if (q == null || q.isBlank() || out.size() >= max) continue;
            List<Ref> found = new ArrayList<>();
            if (sources.contains("wikimedia")) found.addAll(safe(() -> wikimedia(q, perQuery + 2)));
            if (found.size() < perQuery && sources.contains("openverse")) found.addAll(safe(() -> openverse(q, perQuery + 2)));
            int taken = 0;
            for (Ref r : found) {
                if (out.size() >= max || taken >= perQuery) break;
                if (r.pageUrl() != null && !seen.add(r.pageUrl())) continue;
                out.add(r);
                taken++;
            }
        }
        return out;
    }

    private interface Source { List<Ref> get() throws Exception; }

    private static List<Ref> safe(Source s) {
        try {
            return s.get();
        } catch (Exception e) {
            System.err.println("[AI WorldEdit] Reference search failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Wikimedia Commons: full-text file search with thumbnail URLs and license metadata. */
    private static List<Ref> wikimedia(String query, int limit) throws Exception {
        String url = wikimediaApi + "?action=query&format=json&formatversion=2"
            + "&generator=search&gsrnamespace=6&gsrlimit=" + limit
            + "&gsrsearch=" + enc(query + " filetype:bitmap")
            + "&prop=imageinfo&iiprop=url%7Cmime%7Cextmetadata&iiurlwidth=" + THUMB_WIDTH;
        JsonObject root = JsonParser.parseString(getText(url)).getAsJsonObject();
        List<Ref> out = new ArrayList<>();
        if (!root.has("query")) return out;
        JsonArray pages = root.getAsJsonObject("query").getAsJsonArray("pages");
        if (pages == null) return out;
        List<JsonObject> sorted = new ArrayList<>();
        for (JsonElement p : pages) sorted.add(p.getAsJsonObject());
        sorted.sort((a, b) -> Integer.compare(num(a, "index"), num(b, "index"))); // search rank
        for (JsonObject page : sorted) {
            if (out.size() >= limit) break;
            JsonArray infos = page.getAsJsonArray("imageinfo");
            if (infos == null || infos.isEmpty()) continue;
            JsonObject info = infos.get(0).getAsJsonObject();
            String mime = str(info, "mime");
            if (!(mime.equals("image/jpeg") || mime.equals("image/png"))) continue;
            String thumb = str(info, "thumburl");
            if (thumb.isEmpty()) continue;
            JsonObject meta = info.has("extmetadata") ? info.getAsJsonObject("extmetadata") : new JsonObject();
            String title = str(page, "title").replaceFirst("^File:", "").replaceAll("\\.[A-Za-z]{3,4}$", "");
            AiClient.Img img = download(thumb);
            if (img == null) continue;
            out.add(new Ref(title, "Wikimedia Commons", metaValue(meta, "LicenseShortName"),
                stripHtml(metaValue(meta, "Artist")), str(info, "descriptionurl"), img));
        }
        return out;
    }

    /** Openverse: openly licensed images from many collections. */
    private static List<Ref> openverse(String query, int limit) throws Exception {
        String url = openverseApi + "?q=" + enc(query) + "&page_size=" + limit + "&mature=false";
        JsonObject root = JsonParser.parseString(getText(url)).getAsJsonObject();
        List<Ref> out = new ArrayList<>();
        JsonArray results = root.getAsJsonArray("results");
        if (results == null) return out;
        for (JsonElement el : results) {
            if (out.size() >= limit) break;
            JsonObject r = el.getAsJsonObject();
            String thumb = str(r, "thumbnail");
            if (thumb.isEmpty()) thumb = str(r, "url");
            if (thumb.isEmpty()) continue;
            AiClient.Img img = download(thumb);
            if (img == null) continue;
            String license = str(r, "license").toUpperCase(Locale.ROOT);
            if (!str(r, "license_version").isEmpty()) license = "CC " + license + " " + str(r, "license_version");
            out.add(new Ref(str(r, "title").isEmpty() ? query : str(r, "title"), "Openverse", license,
                str(r, "creator"), str(r, "foreign_landing_url"), img));
        }
        return out;
    }

    // ---------------- player-supplied ----------------

    /** Loads a reference from a URL or from a file in config/aiworldedit/references. */
    public static Ref load(String urlOrFile) throws Exception {
        String s = urlOrFile.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            AiClient.Img img = download(s);
            if (img == null) throw new RuntimeException("Couldn't download a JPG/PNG image from " + s);
            return new Ref(fileName(s), "your link", "", "", s, img);
        }
        Path p = referenceFolder().resolve(s).normalize();
        if (!p.startsWith(referenceFolder())) throw new RuntimeException("Reference files must be in " + referenceFolder());
        if (!Files.exists(p)) throw new RuntimeException("No file " + s + " in " + referenceFolder());
        byte[] data = Files.readAllBytes(p);
        String mime = sniff(data);
        if (mime == null) throw new RuntimeException(s + " is not a JPG or PNG image");
        if (data.length > MAX_IMAGE_BYTES * 3) throw new RuntimeException(s + " is too large (max ~9 MB)");
        return new Ref(fileName(s), "your file", "", "", p.toString(), new AiClient.Img(data, mime));
    }

    // ---------------- HTTP ----------------

    private static String getText(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", userAgent())
            .header("Accept", "application/json")
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode() + " from " + host(url));
        return resp.body();
    }

    /** Downloads a JPG/PNG (by content, not just header). Returns null if unusable. */
    static AiClient.Img download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent())
                .GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) return null;
            byte[] data = resp.body();
            if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) return null;
            String mime = sniff(data);
            return mime == null ? null : new AiClient.Img(data, mime);
        } catch (Exception e) {
            return null;
        }
    }

    /** Detects JPEG/PNG from the file signature. */
    static String sniff(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        return null;
    }

    // ---------------- helpers ----------------

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "server";
        }
    }

    private static String fileName(String s) {
        String n = s.replaceAll("[?#].*$", "");
        n = n.substring(n.lastIndexOf('/') + 1);
        return n.isEmpty() ? "reference" : n;
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : Integer.MAX_VALUE;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String metaValue(JsonObject meta, String key) {
        try {
            return meta.has(key) ? meta.getAsJsonObject(key).get("value").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    static String stripHtml(String s) {
        String t = s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replaceAll("\\s+", " ").trim();
        return t.length() > 60 ? t.substring(0, 57) + "..." : t;
    }
}
