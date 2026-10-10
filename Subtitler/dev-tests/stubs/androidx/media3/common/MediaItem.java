package androidx.media3.common;
public final class MediaItem {
    public static MediaItem fromUri(android.net.Uri uri){return null;}
    public static MediaItem fromUri(String uri){return null;}
    public static final class ClippingConfiguration {
        public static final class Builder {
            public Builder(){}
            public Builder setStartPositionMs(long v){return this;}
            public Builder setEndPositionMs(long v){return this;}
            public ClippingConfiguration build(){return null;}
        }
    }
    public static final class Builder {
        public Builder(){}
        public Builder setUri(String uri){return this;}
        public Builder setUri(android.net.Uri uri){return this;}
        public Builder setClippingConfiguration(ClippingConfiguration c){return this;}
        public MediaItem build(){return null;}
    }
}
