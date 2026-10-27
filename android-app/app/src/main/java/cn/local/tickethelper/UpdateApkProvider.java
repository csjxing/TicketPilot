package cn.local.tickethelper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, URI-granted access to verified update APKs; never serves private app files. */
public final class UpdateApkProvider extends ContentProvider {
    static Uri uri(android.content.Context context, File file) throws IOException {
        File directory = new File(context.getCacheDir(), "cloud-updates").getCanonicalFile();
        File apk = file.getCanonicalFile();
        if (!directory.equals(apk.getParentFile()) || !apk.isFile() || !validName(apk.getName()))
            throw new IOException("Invalid update file");
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(apk.getName()).build();
    }
    private static boolean validName(String name) { return name.matches("[A-Za-z0-9._-]{1,160}\\.apk"); }
    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            if (getContext() == null || !"content".equals(uri.getScheme())
                    || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null
                    || !validName(uri.getLastPathSegment())) throw new IOException();
            File directory = new File(getContext().getCacheDir(), "cloud-updates").getCanonicalFile();
            File file = new File(directory, uri.getLastPathSegment()).getCanonicalFile();
            if (!directory.equals(file.getParentFile()) || !file.isFile()) throw new IOException();
            return file;
        } catch (Exception error) { throw new FileNotFoundException("Update APK unavailable"); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException error) { return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
