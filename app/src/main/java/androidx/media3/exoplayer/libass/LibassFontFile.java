package androidx.media3.exoplayer.libass;

import java.io.File;

/**
 * Compatibility font helper.
 *
 * <p>The newer libass module resolves a font family from the embedded OpenType/TrueType name
 * table. The public snapshot has no such helper; using the file stem is sufficient for the font
 * list UI and avoids loading a third-party font parser.
 */
public final class LibassFontFile {

    private LibassFontFile() {
    }

    public static String getFamilyName(File file) {
        if (file == null) return "";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ').trim();
    }
}
