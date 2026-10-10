package androidx.media3.transformer;
public final class Transformer {
    public static final int PROGRESS_STATE_AVAILABLE = 1;
    public interface Listener {
        default void onCompleted(Composition composition, ExportResult exportResult) {}
        default void onError(Composition composition, ExportResult exportResult, ExportException exportException) {}
    }
    public static final class Builder {
        public Builder(android.content.Context c){}
        public Builder setEncoderFactory(Object f){return this;}
        public Builder setAudioMimeType(String m){return this;}
        public Builder setVideoMimeType(String m){return this;}
        public Builder addListener(Listener l){return this;}
        public Transformer build(){return null;}
    }
    public void start(EditedMediaItem e, String path){}
    public int getProgress(ProgressHolder h){return 0;}
    public void cancel(){}
}
