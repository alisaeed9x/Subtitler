package androidx.media3.datasource;
public class DefaultHttpDataSource {
  public static final class Factory implements DataSource.Factory {
    public Factory() {}
    public Factory setDefaultRequestProperties(java.util.Map<String,String> m){return this;}
    public Factory setAllowCrossProtocolRedirects(boolean b){return this;}
  }
}
