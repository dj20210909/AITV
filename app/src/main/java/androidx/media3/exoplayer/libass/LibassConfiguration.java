package androidx.media3.exoplayer.libass;

/**
 * Compatibility configuration for the public FongMi/media libass module.
 *
 * <p>The newer app source targets a LibassConfiguration-based constructor which is not present in
 * the public media snapshot. Values are translated to the older (fontConfig, fontsDir,
 * defaultFontFamily) constructor by ExoPlayerSession.
 */
public final class LibassConfiguration {

    private final String fontConfig;
    private final String fontsDirectory;
    private final String defaultFontFamily;
    private final int maximumRenderPixels;
    private final int maximumGlyphCount;
    private final int maximumBitmapCacheSizeMb;

    private LibassConfiguration(Builder builder) {
        this.fontConfig = builder.fontConfig;
        this.fontsDirectory = builder.fontsDirectory;
        this.defaultFontFamily = builder.defaultFontFamily;
        this.maximumRenderPixels = builder.maximumRenderPixels;
        this.maximumGlyphCount = builder.maximumGlyphCount;
        this.maximumBitmapCacheSizeMb = builder.maximumBitmapCacheSizeMb;
    }

    public String getFontConfig() {
        return fontConfig;
    }

    public String getFontsDirectory() {
        return fontsDirectory;
    }

    public String getDefaultFontFamily() {
        return defaultFontFamily;
    }

    public int getMaximumRenderPixels() {
        return maximumRenderPixels;
    }

    public int getMaximumGlyphCount() {
        return maximumGlyphCount;
    }

    public int getMaximumBitmapCacheSizeMb() {
        return maximumBitmapCacheSizeMb;
    }

    public static final class Builder {

        private String fontConfig;
        private String fontsDirectory;
        private String defaultFontFamily;
        private int maximumRenderPixels;
        private int maximumGlyphCount;
        private int maximumBitmapCacheSizeMb;

        public Builder setFontConfig(String fontConfig) {
            this.fontConfig = fontConfig;
            return this;
        }

        public Builder setFontsDirectory(String fontsDirectory) {
            this.fontsDirectory = fontsDirectory;
            return this;
        }

        public Builder setDefaultFontFamily(String defaultFontFamily) {
            this.defaultFontFamily = defaultFontFamily;
            return this;
        }

        public Builder setMaximumRenderPixels(int maximumRenderPixels) {
            this.maximumRenderPixels = maximumRenderPixels;
            return this;
        }

        public Builder setMaximumGlyphCount(int maximumGlyphCount) {
            this.maximumGlyphCount = maximumGlyphCount;
            return this;
        }

        public Builder setMaximumBitmapCacheSizeMb(int maximumBitmapCacheSizeMb) {
            this.maximumBitmapCacheSizeMb = maximumBitmapCacheSizeMb;
            return this;
        }

        public LibassConfiguration build() {
            return new LibassConfiguration(this);
        }
    }
}
