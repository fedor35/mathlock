package dev.gubenkov.mathlock;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Установка пакета чтения без интернета — копированием в {@code files/} «Таблицы»:
 * <ul>
 *   <li>из папки на телефоне (выбор папки системным окном: «Загрузки» после копирования с компьютера по кабелю,
 *       флешка через OTG, карта памяти);</li>
 *   <li>из самого APK, если он собран с {@code android/app/reading-pack/} (assets/reading/).</li>
 * </ul>
 * Берутся только известные файлы ({@link #accept}); каждый пишется во временный и переименовывается — недокопированная
 * модель не считается установленной. Файл того же размера пропускается.
 */
final class PackInstaller {
    static final String TAG = ReadingEngine.TAG;
    static final String ASSETS = "reading";

    private PackInstaller() {}

    /** Что брать из пакета: путь относительно корня пакета. */
    static boolean accept(String rel) {
        String n = rel.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("tts/")) {
            String f = n.substring(4);
            return !f.contains("/") && (f.endsWith(".ogg") || f.endsWith(".wav") || f.endsWith(".json"));
        }
        if (n.contains("/")) return false;
        return n.equals(ReadingPack.TEXTS) || n.endsWith(".onnx") || n.endsWith(ReadingPack.MANIFEST_SUFFIX);
    }

    static boolean apkHasPack(Context c) {
        try { String[] l = c.getAssets().list(ASSETS); return l != null && l.length > 0; } catch (Exception e) { return false; }
    }

    static final class Count { int copied, skipped; long bytes; final java.util.List<String> models = new java.util.ArrayList<>(); }

    /** Новая модель — старый скомпилированный контекст NPU к ней не подходит: убрать, «Подготовить» соберёт заново. */
    private static void invalidate(Context c, Count n) {
        File q = ReadingEngine.qnnDir(c);
        for (String m : n.models) {
            new File(q, ReadingPack.ctxName(m)).delete();
            String stem = m.replace(".onnx", "");
            File[] bins = q.listFiles((d, x) -> x.startsWith(stem) && x.endsWith(".bin"));
            if (bins != null) for (File b : bins) b.delete();
        }
    }

    static String installFromAssets(Context c, Consumer<String> progress) {
        File dst = ReadingEngine.dataDir(c);
        if (dst == null) return "Внешнее хранилище недоступно";
        Count n = new Count();
        try { assetsDir(c.getAssets(), ASSETS, "", dst, n, progress); }
        catch (Exception e) { Log.e(TAG, "установка из APK", e); return "Ошибка: " + e.getMessage(); }
        finally { invalidate(c, n); }
        return summary(n);
    }

    private static void assetsDir(AssetManager am, String path, String rel, File dst, Count n, Consumer<String> progress) throws Exception {
        String[] list = am.list(path);
        if (list == null) return;
        for (String name : list) {
            String p = path + "/" + name, r = rel.isEmpty() ? name : rel + "/" + name;
            String[] sub = am.list(p);
            if (sub != null && sub.length > 0) { assetsDir(am, p, r, dst, n, progress); continue; }
            if (!accept(r)) continue;
            long size = -1;
            try (android.content.res.AssetFileDescriptor fd = am.openFd(p)) { size = fd.getLength(); } catch (Exception ignored) { }   // сжатый ассет — размер неизвестен
            try (InputStream in = am.open(p)) { copy(in, size, new File(dst, r), n, progress); }
        }
    }

    static String installFromTree(Context c, Uri tree, Consumer<String> progress) {
        File dst = ReadingEngine.dataDir(c);
        if (dst == null) return "Внешнее хранилище недоступно";
        Count n = new Count();
        try {
            String root = DocumentsContract.getTreeDocumentId(tree);
            treeDir(c.getContentResolver(), tree, root, "", dst, n, progress);
        } catch (Exception e) { Log.e(TAG, "установка из папки", e); return "Ошибка: " + e.getMessage(); }
        finally { invalidate(c, n); }
        if (n.copied + n.skipped == 0) return "В папке нет файлов пакета чтения (texts.jsonl, *.onnx, tts/)";
        return summary(n);
    }

    private static void treeDir(ContentResolver cr, Uri tree, String docId, String rel, File dst, Count n, Consumer<String> progress) throws Exception {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor q = cr.query(kids, cols, null, null, null)) {
            if (q == null) return;
            while (q.moveToNext()) {
                String id = q.getString(0), name = q.getString(1), mime = q.getString(2);
                long size = q.isNull(3) ? -1 : q.getLong(3);
                String r = rel.isEmpty() ? name : rel + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (rel.isEmpty() && name.equalsIgnoreCase(ReadingPack.TTS)) treeDir(cr, tree, id, ReadingPack.TTS, dst, n, progress);
                    continue;
                }
                if (!accept(r)) continue;
                try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))) { copy(in, size, new File(dst, r), n, progress); }
            }
        }
    }

    private static void copy(InputStream in, long size, File out, Count n, Consumer<String> progress) throws Exception {
        if (size >= 0 && out.isFile() && out.length() == size) { n.skipped++; return; }
        out.getParentFile().mkdirs();
        File tmp = new File(out.getPath() + ".part");
        long done = 0, shownAt = 0; byte[] buf = new byte[1 << 20];
        try (OutputStream o = new FileOutputStream(tmp)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                o.write(buf, 0, r); done += r;
                if (progress != null && done - shownAt > (16 << 20)) { shownAt = done; progress.accept(out.getName() + ": " + (done >> 20) + " МБ"); }
            }
        }
        if (size >= 0 && done != size) { tmp.delete(); throw new java.io.IOException(out.getName() + ": скопировано " + done + " из " + size); }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new java.io.IOException("не переименовать " + tmp);
        n.copied++; n.bytes += done;
        // новая модель — старый скомпилированный контекст NPU к ней не подходит
        if (out.getName().endsWith(".onnx")) n.models.add(out.getName());
        if (progress != null && !out.getParentFile().getName().equals(ReadingPack.TTS)) progress.accept(out.getName() + " ✓");
    }

    private static String summary(Count n) {
        return "Скопировано файлов: " + n.copied + " (" + (n.bytes >> 20) + " МБ), без изменений: " + n.skipped;
    }
}
