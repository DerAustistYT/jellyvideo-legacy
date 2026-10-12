package org.jellyvideo.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class JellyfinApi {
    private static final String APP = "JellyVideo Legacy";
    private static final String VERSION = "0.1.0";
    private final Context context;
    private final SharedPreferences prefs;
    private String baseUrl;
    private String token;
    private String userId;
    private final String deviceId;

    public JellyfinApi(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("session", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("server", "");
        token = prefs.getString("token", "");
        userId = prefs.getString("user", "");
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceId = TextUtils.isEmpty(id) ? "legacy-android-device" : id;
    }

    public boolean hasSession() { return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(token) && !TextUtils.isEmpty(userId); }
    public String getBaseUrl() { return baseUrl; }
    public String getToken() { return token; }
    public String getUserId() { return userId; }

    public void setServer(String value) {
        String url = value == null ? "" : value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!(url.startsWith("https://") || url.startsWith("http://"))) url = "https://" + url;
        baseUrl = url;
        prefs.edit().putString("server", baseUrl).apply();
    }

    public void logout() {
        token = ""; userId = "";
        prefs.edit().remove("token").remove("user").apply();
    }

    private String authorization(boolean includeToken) {
        String device = Build.MANUFACTURER + " " + Build.MODEL;
        String value = "MediaBrowser Client=\"" + APP + "\", Device=\"" + clean(device) + "\", DeviceId=\"" + clean(deviceId) + "\", Version=\"" + VERSION + "\"";
        if (includeToken && !TextUtils.isEmpty(token)) value += ", Token=\"" + clean(token) + "\"";
        return value;
    }

    private String clean(String value) { return value == null ? "" : value.replace("\\", "").replace("\"", ""); }

    private JSONObject json(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection c = org.jellylegacy.net.Network.open(new URL(baseUrl + path));
        c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", authorization(authenticated));
        c.setRequestProperty("X-Emby-Authorization", authorization(authenticated));
        if (authenticated && !TextUtils.isEmpty(token)) c.setRequestProperty("X-Emby-Token", token);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream out = c.getOutputStream(); out.write(body.toString().getBytes("UTF-8")); out.close();
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readText(stream, c.getContentLength());
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Server returned " + code + (text.length() > 0 ? ": " + text : ""));
        return text.length() == 0 ? new JSONObject() : new JSONObject(text);
    }

    private String readText(InputStream stream) throws Exception { return readText(stream, -1); }
    private String readText(InputStream stream, int sizeHint) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        // A size hint (Content-Length, when the server sends one uncompressed) avoids StringBuilder's
        // repeated reallocate-and-copy growth on the larger responses here, like a 300-song page of Items.
        StringBuilder result = new StringBuilder(sizeHint > 0 ? sizeHint : 16); String line;
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close(); return result.toString();
    }

    public JSONObject serverInfo() throws Exception { return json("GET", "/System/Info/Public", null, false); }
    public JSONObject beginQuickConnect() throws Exception { return json("POST", "/QuickConnect/Initiate", new JSONObject(), false); }
    public JSONObject quickConnectState(String secret) throws Exception { return json("GET", "/QuickConnect/Connect?secret=" + enc(secret), null, false); }
    public void finishQuickConnect(String secret) throws Exception {
        JSONObject body = new JSONObject(); body.put("Secret", secret);
        JSONObject result = json("POST", "/Users/AuthenticateWithQuickConnect", body, false);
        token = result.optString("AccessToken", "");
        JSONObject user = result.optJSONObject("User");
        userId = user == null ? "" : user.optString("Id", "");
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(userId)) throw new Exception("Server approved the code but returned no session token.");
        prefs.edit().putString("token", token).putString("user", userId).apply();
    }

    public List<MediaItem> items(String type, String parent, String filter, String search, int page) throws Exception {
        String path="/Users/"+enc(userId)+"/Items?Recursive="+(parent==null?"true":"false")+"&IncludeItemTypes="+enc(type)
            +"&Fields=Overview,RunTimeTicks,PrimaryImageAspectRatio&StartIndex="+(page*40)+"&Limit=40&EnableTotalRecordCount=false"
            +"&SortBy="+(("Episode".equals(type)||"Season".equals(type))?"ParentIndexNumber,IndexNumber":"SortName")+"&SortOrder=Ascending";
        if(parent!=null)path+="&ParentId="+enc(parent);
        if(filter!=null)path+="&Filters="+enc(filter);
        if(search!=null&&search.length()>0)path+="&SearchTerm="+enc(search);
        if("IsResumable".equals(filter))path=path.replace("SortBy=SortName&SortOrder=Ascending","SortBy=DatePlayed&SortOrder=Descending");
        List<MediaItem> out=new ArrayList<MediaItem>(); JSONArray array=json("GET",path,null,true).optJSONArray("Items");
        if(array!=null)for(int n=0;n<array.length();n++)out.add(parse(array.getJSONObject(n)));
        return out;
    }
    public MediaItem item(String id)throws Exception{return parse(json("GET","/Users/"+enc(userId)+"/Items/"+enc(id),null,true));}
    private MediaItem parse(JSONObject o){
        MediaItem m=new MediaItem();m.id=o.optString("Id");m.name=o.optString("Name","Untitled");m.kind=o.optString("Type");m.overview=o.optString("Overview");m.year=o.optInt("ProductionYear");m.durationTicks=o.optLong("RunTimeTicks");
        JSONObject u=o.optJSONObject("UserData");if(u!=null){m.favorite=u.optBoolean("IsFavorite");m.watched=u.optBoolean("Played");m.resumeTicks=u.optLong("PlaybackPositionTicks");}
        m.albumId=o.optString("SeriesId",m.id);m.album=false;
        if("Episode".equals(m.kind))m.subtitle=o.optString("SeriesName")+" · S"+o.optInt("ParentIndexNumber")+" E"+o.optInt("IndexNumber");
        else m.subtitle=m.kind+(m.year>0?" · "+m.year:"");
        if(m.durationTicks>0)m.subtitle+=" · "+(m.durationTicks/600000000L)+" min";
        if(m.watched)m.subtitle+=" · Watched";else if(m.resumeTicks>0)m.subtitle+=" · In progress";
        return m;
    }
    public String imageUrl(MediaItem item,int size){return baseUrl+"/Items/"+item.albumId+"/Images/Primary?maxWidth="+size+"&quality=65";}
    public java.util.Map<String,String> playbackHeaders(){java.util.Map<String,String> h=new java.util.HashMap<String,String>();h.put("X-Emby-Token",token);h.put("Authorization",authorization(true));return h;}
    public void favorite(String id,boolean value)throws Exception{json(value?"POST":"DELETE","/Users/"+enc(userId)+"/FavoriteItems/"+enc(id),null,true);}
    public void watched(String id,boolean value)throws Exception{json(value?"POST":"DELETE","/Users/"+enc(userId)+"/PlayedItems/"+enc(id),null,true);}
    public static class Playback {
        public String url,session,source;public long startTicks;
    }
    public Playback playback(String id,long start,int quality)throws Exception{
        int height=quality==0?240:quality==1?320:480, width=quality==0?320:quality==1?480:720;
        int videoRate=quality==0?350000:quality==1?600000:1200000;
        JSONObject profile=new JSONObject();profile.put("Name","JellyVideo Android 4");profile.put("MaxStreamingBitrate",videoRate+96000);profile.put("MaxStaticBitrate",videoRate+96000);
        profile.put("DirectPlayProfiles",new JSONArray());
        JSONObject trans=new JSONObject();trans.put("Type","Video");trans.put("Container","ts");trans.put("Protocol","hls");trans.put("VideoCodec","h264");trans.put("AudioCodec","aac");trans.put("Context","Streaming");trans.put("MaxAudioChannels","2");trans.put("MinSegments",2);trans.put("SegmentLength",4);trans.put("BreakOnNonKeyFrames",false);
        profile.put("TranscodingProfiles",new JSONArray().put(trans));
        JSONArray conditions=new JSONArray().put(new JSONObject().put("Condition","EqualsAny").put("Property","VideoProfile").put("Value","baseline|constrained baseline").put("IsRequired",true)).put(new JSONObject().put("Condition","LessThanEqual").put("Property","VideoLevel").put("Value","30").put("IsRequired",true));
        profile.put("CodecProfiles",new JSONArray().put(new JSONObject().put("Type","Video").put("Codec","h264").put("Conditions",conditions)));
        JSONObject body=new JSONObject();body.put("UserId",userId);body.put("DeviceProfile",profile);body.put("StartTimeTicks",start);body.put("IsPlayback",true);body.put("EnableDirectPlay",false);body.put("EnableDirectStream",false);body.put("EnableTranscoding",true);body.put("AutoOpenLiveStream",true);body.put("MaxStreamingBitrate",videoRate+96000);
        JSONObject result=json("POST","/Items/"+enc(id)+"/PlaybackInfo",body,true);
        String err=result.optString("ErrorCode");if(err.length()>0)throw new Exception("Jellyfin cannot play this video: "+err);
        JSONArray sources=result.optJSONArray("MediaSources");if(sources==null||sources.length()==0)throw new Exception("No video source available");
        JSONObject src=sources.getJSONObject(0);if(!src.optBoolean("SupportsTranscoding",false))throw new Exception("Enable video transcoding for this Jellyfin user.");
        Playback p=new Playback();p.source=src.optString("Id");p.session=result.optString("PlaySessionId");p.startTicks=start;
        p.url=baseUrl+"/Videos/"+enc(id)+"/master.m3u8?MediaSourceId="+enc(p.source)+"&DeviceId="+enc(deviceId)+"&PlaySessionId="+enc(p.session)
          +"&VideoCodec=h264&AudioCodec=aac&VideoBitRate="+videoRate+"&AudioBitRate=96000&MaxWidth="+width+"&MaxHeight="+height
          +"&MaxFramerate=24&MaxAudioChannels=2&AudioSampleRate=44100&Profile=baseline&Level=30&MaxVideoBitDepth=8&MaxRefFrames=1"
          +"&AllowVideoStreamCopy=false&AllowAudioStreamCopy=false&EnableAutoStreamCopy=false&SegmentContainer=ts&SegmentLength=4&MinSegments=2&StartTimeTicks="+start;
        int audio=src.optInt("DefaultAudioStreamIndex",-1);if(audio>=0)p.url+="&AudioStreamIndex="+audio;
        String live=src.optString("LiveStreamId");if(live.length()>0)p.url+="&LiveStreamId="+enc(live);
        return p;
    }
    public void report(String event,String id,Playback p,long ticks,boolean paused)throws Exception{
        JSONObject b=new JSONObject();b.put("ItemId",id);b.put("PlaySessionId",p.session);b.put("MediaSourceId",p.source);b.put("PositionTicks",ticks);b.put("IsPaused",paused);b.put("CanSeek",true);b.put("PlayMethod","Transcode");
        json("POST","/Sessions/Playing"+("start".equals(event)?"":"stop".equals(event)?"/Stopped":"/Progress"),b,true);
    }
    public void stopEncoding(Playback p){try{json("DELETE","/Videos/ActiveEncodings?DeviceId="+enc(deviceId)+"&PlaySessionId="+enc(p.session),null,true);}catch(Exception ignored){}}
    private String enc(String s)throws Exception{return URLEncoder.encode(s==null?"":s,"UTF-8");}
}
