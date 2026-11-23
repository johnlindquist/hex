package anomaly.hex.android.keyboard;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only ContentProvider that exposes only the single opt-in typing telemetry NDJSON
 * file to Android's Share Sheet (Intent.ACTION_SEND) without needing AndroidX FileProvider.
 */
public final class TypingTelemetryProvider extends ContentProvider {
    public static final String AUTHORITY = "anomaly.hex.android.telemetry";
    public static final Uri FILE_URI = Uri.parse("content://" + AUTHORITY + "/" + TypingTelemetryLog.FILE_NAME);

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/x-ndjson";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null || !FILE_URI.equals(uri)) return null;
        File f = TypingTelemetryLog.get(ctx).file();
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = TypingTelemetryLog.FILE_NAME;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.isFile() ? f.length() : 0L;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || !FILE_URI.equals(uri)) throw new FileNotFoundException("Unsupported URI");
        TypingTelemetryLog log = TypingTelemetryLog.get(ctx);
        log.flushSync();
        File f = log.file();
        if (!f.isFile()) throw new FileNotFoundException("No telemetry log yet");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
