package org.jellyvideo.legacy;
public class LegacyVideoApp extends android.app.Application {
    public void onCreate(){super.onCreate();org.jellylegacy.net.Network.initialize(this);}
}
