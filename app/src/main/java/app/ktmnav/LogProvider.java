package app.ktmnav;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class LogProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File selectedFile(Uri uri) {
        if ("/diagnostics".equals(uri.getPath())) return new File(getContext().getCacheDir(), "ktmnav-diagnostics.txt");
        if ("/maneuvers".equals(uri.getPath())) return new File(getContext().getCacheDir(), "ktmnav-maneuvers.zip");
        String ride = rideName(uri);
        if (ride != null) {
            File file = app.ktmnav.backend.ride.RideServices.storeFor(getContext()).exportFile(ride);
            if (file != null) return file;
        }
        String route = routeName(uri);
        if (route != null) {
            File file = new app.ktmnav.backend.ride.RouteStore(getContext().getFilesDir()).exportFile(route);
            if (file != null) return file;
        }
        throw new IllegalArgumentException("Unknown export");
    }
    private static String rideName(Uri uri) {
        java.util.List<String> segments = uri.getPathSegments();
        return segments.size() == 2 && "rides".equals(segments.get(0)) ? segments.get(1) : null;
    }
    private static String routeName(Uri uri) {
        java.util.List<String> segments = uri.getPathSegments();
        return segments.size() == 2 && "routes".equals(segments.get(0)) ? segments.get(1) : null;
    }
    @Override public String getType(Uri uri) {
        if (rideName(uri) != null || routeName(uri) != null) return "application/gpx+xml";
        return "/maneuvers".equals(uri.getPath()) ? "application/zip" : "text/plain";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        File file = selectedFile(uri);
        cursor.addRow(new Object[]{file.getName(), file.length()}); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !("/diagnostics".equals(uri.getPath()) || "/maneuvers".equals(uri.getPath()) || rideName(uri) != null || routeName(uri) != null)) throw new FileNotFoundException();
        try {
            return ParcelFileDescriptor.open(selectedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IllegalArgumentException missing) {
            throw new FileNotFoundException();
        }
    }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
