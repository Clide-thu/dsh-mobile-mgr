package dev.dsh.remotehosts.ui;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a small UTF-8 text file from a SAF {@code content://} URI, with a hard
 * size cap so a user picked file can never be read into an unbounded buffer.
 */
final class FileTextReader {

    /** 1 MiB, per docs/app-spec.md §7.3. */
    static final int MAX_BYTES = 1024 * 1024;

    private FileTextReader() {
    }

    /** Thrown when the picked file is bigger than {@link #MAX_BYTES}. */
    static final class TooLargeException extends IOException {
        TooLargeException() {
            super("file too large");
        }
    }

    /** Read up to {@link #MAX_BYTES} bytes and decode them as UTF-8. */
    static String read(ContentResolver resolver, Uri uri) throws IOException {
        if (uri == null) throw new IOException("empty uri");
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) throw new IOException("cannot open file");
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BYTES) throw new TooLargeException();
                buffer.write(chunk, 0, read);
            }
            String text = new String(buffer.toByteArray(), "UTF-8");
            // Tolerate a UTF-8 BOM written by some Windows editors.
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return text;
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Closing a content stream is best effort.
            }
        }
    }
}
