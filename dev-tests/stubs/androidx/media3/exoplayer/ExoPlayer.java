package androidx.media3.exoplayer;
public interface ExoPlayer extends androidx.media3.common.Player {
  final class Builder {
    public Builder(android.content.Context c) {}
    public Builder(android.content.Context c, DefaultRenderersFactory rf) {}
    public Builder setMediaSourceFactory(androidx.media3.exoplayer.source.MediaSource.Factory f){return this;}
    public ExoPlayer build(){return null;}
  }
}
