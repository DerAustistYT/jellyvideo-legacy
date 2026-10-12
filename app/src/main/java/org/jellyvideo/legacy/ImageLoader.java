package org.jellyvideo.legacy;
import android.graphics.*;
import android.widget.ImageView;
import android.os.Handler;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public class ImageLoader {
    private final JellyfinApi api;private final Handler ui=new Handler();private volatile boolean closed;
    private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(24));
    private final Map<String,Bitmap> cache=Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){return size()>16;}});
    public ImageLoader(JellyfinApi a){api=a;}
    public void load(MediaItem item,final ImageView view){final String url=api.imageUrl(item,80);view.setTag(url);view.setImageResource(android.R.drawable.ic_menu_gallery);Bitmap hit=cache.get(url);if(hit!=null){view.setImageBitmap(hit);return;}
        try{jobs.execute(new Runnable(){public void run(){if(closed)return;HttpURLConnection c=null;Bitmap b=null;try{c=org.jellylegacy.net.Network.open(new URL(url));for(Map.Entry<String,String> h:api.playbackHeaders().entrySet())c.setRequestProperty(h.getKey(),h.getValue());c.setConnectTimeout(8000);c.setReadTimeout(8000);InputStream in=c.getInputStream();BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;b=BitmapFactory.decodeStream(in,null,options);in.close();if(b!=null&&!closed)cache.put(url,b);}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}final Bitmap result=b;ui.post(new Runnable(){public void run(){if(!closed&&result!=null&&url.equals(view.getTag()))view.setImageBitmap(result);}});}});}catch(RejectedExecutionException ignored){}
    }
    public void close(){closed=true;jobs.shutdownNow();cache.clear();}
}
