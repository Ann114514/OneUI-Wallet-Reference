package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.LruCache;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Wallet-private artwork only; keys are hashes supplied by the per-card UI binding. */
final class WalletCardArtStore {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 600;
    private static final int MAX_DECODE_EDGE = 2048;
    private static final String ART_DIRECTORY = "oneui_toolbox_card_art";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern LIBRARY_FILE_PATTERN = Pattern.compile("[a-f0-9]{64}\\.png");
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
        // A drawable may still display an evicted bitmap; never recycle cache entries.
    };

    private WalletCardArtStore() { }

    /** Caller performs disk loads on a worker thread. Invalid or absent files return null. */
    static synchronized Bitmap load(Context context, String key) {
        try {
            return loadBitmap(file(context, key), key);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The returned source belongs to the crop editor and must be recycled by its owner. */
    static Bitmap decodeSource(Context context, Uri uri) throws Exception {
        if (context == null || uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null)
            throw new IllegalArgumentException("An image from the system picker is required");
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, unused) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            if (width <= 0 || height <= 0 || width > 262144 || height > 262144
                    || (long) Math.max(width, height) > (long) Math.min(width, height) * 100)
                throw new IllegalArgumentException("Unsupported image dimensions");
            double scale = Math.min(1d, (double) MAX_DECODE_EDGE / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, (int) Math.round(width * scale)),
                    Math.max(1, (int) Math.round(height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            decoder.setOnPartialImageListener(error -> false);
            // ImageDecoder applies embedded orientation before returning the bitmap.
        });
        if (bitmap == null) throw new IOException("Image decoding failed");
        return bitmap;
    }

    /** Reusable user-added art, newest first. Incomplete atomic writes are not listed. */
    static synchronized List<String> library(Context context) {
        List<String> ids = new ArrayList<>();
        if (context == null) return ids;
        File directory = new File(context.getFilesDir(), "oneui_toolbox_card_library");
        File[] files = directory.listFiles(path -> path.isFile() && LIBRARY_FILE_PATTERN.matcher(path.getName()).matches());
        if (files == null) return ids;
        Arrays.sort(files, (left, right) -> {
            int recent = Long.compare(right.lastModified(), left.lastModified());
            return recent != 0 ? recent : left.getName().compareTo(right.getName());
        });
        for (File path : files) ids.add(path.getName().substring(0, 64));
        return ids;
    }

    /** Copy the caller-owned crop so the caller may release it after this method returns. */
    static synchronized String addLibrary(Context context, Bitmap bitmap) throws Exception {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != WIDTH || bitmap.getHeight() != HEIGHT)
            throw new IllegalArgumentException("A 960 by 600 artwork image is required");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        String id = hex.toString();
        File target = libraryFile(context, id);
        ensureDirectory(target.getParentFile());
        Bitmap owned = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (owned == null) throw new IOException("Artwork copy failed");
        boolean retained = false;
        try {
            writeBitmap(target, owned);
            CACHE.put("lib:" + id, owned);
            retained = true;
            return id;
        } finally {
            if (!retained) owned.recycle();
        }
    }

    /** Cached library bitmaps remain store-owned and must never be recycled by a view. */
    static synchronized Bitmap loadLibrary(Context context, String id) {
        try {
            return loadBitmap(libraryFile(context, id), "lib:" + id);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Called under the store lock; decoded images remain cache-owned. */
    private static Bitmap loadBitmap(File path, String cacheKey) throws IOException {
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        AtomicFile atomic = new AtomicFile(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (FileInputStream stream = atomic.openRead()) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth != WIDTH || bounds.outHeight != HEIGHT) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = false;
        Bitmap bitmap;
        try (FileInputStream stream = atomic.openRead()) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap != null) CACHE.put(cacheKey, bitmap);
        return bitmap;
    }

    /** Removing a reusable item leaves every previously applied card copy intact. */
    static synchronized void deleteLibrary(Context context, String id) throws Exception {
        File path = libraryFile(context, id);
        new AtomicFile(path).delete();
        CACHE.remove("lib:" + id);
        if (path.exists() || new File(path.getPath() + ".bak").exists()
                || new File(path.getPath() + ".new").exists())
            throw new IOException("Library artwork could not be removed");
    }

    /** Apply a separate private copy, preserving the previous card art on copy failure. */
    static synchronized void applyLibrary(Context context, String id, String cardKey) throws Exception {
        File source = libraryFile(context, id);
        File target = file(context, cardKey);
        Bitmap bitmap = loadLibrary(context, id);
        if (bitmap == null) throw new IOException("Library artwork is invalid");
        ensureDirectory(target.getParentFile());
        AtomicFile destination = new AtomicFile(target);
        FileOutputStream stream = null;
        try {
            stream = destination.startWrite();
            try (FileInputStream input = new AtomicFile(source).openRead()) {
                byte[] buffer = new byte[32768];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 4 * 1024 * 1024) throw new IOException("Library artwork is too large");
                    stream.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("Library artwork is empty");
            }
            destination.finishWrite(stream);
            stream = null;
        } finally {
            if (stream != null) destination.failWrite(stream);
        }
        CACHE.put(cardKey, bitmap);
    }

    private static void writeBitmap(File path, Bitmap bitmap) throws IOException {
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Artwork encoding failed");
            atomic.finishWrite(stream);
            stream = null;
        } finally {
            if (stream != null) atomic.failWrite(stream);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Artwork directory unavailable");
    }

    private static File libraryFile(Context context, String id) {
        if (context == null || id == null || !KEY_PATTERN.matcher(id).matches())
            throw new IllegalArgumentException("Invalid library artwork key");
        return new File(new File(context.getFilesDir(), "oneui_toolbox_card_library"), id + ".png");
    }

    /** Memory-only lookup, safe for a view binding on the UI thread. */
    static Bitmap cached(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches() ? CACHE.get(key) : null;
    }

    static boolean exists(Context context, String key) {
        try {
            File target = file(context, key);
            return target.isFile() || new File(target.getPath() + ".bak").isFile();
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized void remove(Context context, String key) throws Exception {
        File target = file(context, key);
        new AtomicFile(target).delete();
        CACHE.remove(key);
        if (target.exists() || new File(target.getPath() + ".bak").exists()
                || new File(target.getPath() + ".new").exists())
            throw new IOException("Artwork could not be removed");
    }

    private static File file(Context context, String key) {
        if (context == null || key == null || !KEY_PATTERN.matcher(key).matches())
            throw new IllegalArgumentException("Invalid artwork key");
        return new File(new File(context.getFilesDir(), ART_DIRECTORY), key + ".png");
    }
}
