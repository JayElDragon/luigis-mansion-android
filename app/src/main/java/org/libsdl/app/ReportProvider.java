package org.libsdl.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Lets the share sheet read the report file (read-only, only reports/report.txt). */
public class ReportProvider extends ContentProvider {
    private File report() { return new File(Diagnostics.dir(getContext()), "report.txt"); }

    private boolean ours(Uri uri) { return uri != null && "/report.txt".equals(uri.getPath()); }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!ours(uri) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(report(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        if (!ours(uri)) return null;
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {"LM-report.txt", report().length()});
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
