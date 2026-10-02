package androidx.media3.common;
public interface Player {
  interface Listener { default void onVideoSizeChanged(VideoSize videoSize) {} default void onIsPlayingChanged(boolean p) {} }
  void addListener(Listener l);
  void setVideoSurfaceView(android.view.SurfaceView v);
  void setMediaItem(MediaItem m);
  void prepare(); void play(); void pause(); void release();
  boolean isPlaying();
  void setPlaybackSpeed(float speed);
  long getCurrentPosition(); long getDuration();
  boolean getPlayWhenReady(); void setPlayWhenReady(boolean b);
  void seekTo(long ms);
}
