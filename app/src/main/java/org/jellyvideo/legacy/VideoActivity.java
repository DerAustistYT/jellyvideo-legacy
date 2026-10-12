package org.jellyvideo.legacy;
import android.app.Activity;
import android.os.*;
import android.media.MediaPlayer;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public class VideoActivity extends Activity implements SurfaceHolder.Callback {
    private JellyfinApi api;private JellyfinApi.Playback session;private StreamProxy proxy;private MediaPlayer player;
    private final ExecutorService tasks=Executors.newSingleThreadExecutor();private final Handler ui=new Handler();
    private SurfaceView video;private FrameLayout screen;private LinearLayout controls;private TextView status,time;private SeekBar seek;private Button pause;
    private String id,name;private long duration,startTicks,offset;private int quality,version,clock;
    private boolean ready,dead,seeking;
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);api=new JellyfinApi(this);id=getIntent().getStringExtra("id");name=getIntent().getStringExtra("title");duration=getIntent().getLongExtra("duration",0);startTicks=getIntent().getLongExtra("start",0);quality=getSharedPreferences("settings",0).getInt("quality",0);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);screen=new FrameLayout(this);video=new SurfaceView(this);screen.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));status=new TextView(this);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setText("Preparing "+name+"…");screen.addView(status,new FrameLayout.LayoutParams(-1,-1));root.addView(screen,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=new LinearLayout(this);Button back=button("−30s"),forward=button("+30s");pause=button("Pause");time=new TextView(this);time.setTextColor(Color.WHITE);time.setTextSize(12);time.setGravity(Gravity.CENTER);row.addView(back,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(pause,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(forward,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(row);seek=new SeekBar(this);seek.setMax((int)Math.min(Integer.MAX_VALUE,duration/10000000L));root.addView(seek,new LinearLayout.LayoutParams(-1,dp(35)));root.removeView(row);root.removeView(seek);controls=new LinearLayout(this);controls.setOrientation(1);controls.setBackgroundColor(Color.argb(210,0,0,0));controls.addView(time,new LinearLayout.LayoutParams(-1,dp(24)));controls.addView(row);controls.addView(seek);screen.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(root);View.OnTouchListener taps=new View.OnTouchListener(){public boolean onTouch(View v,android.view.MotionEvent e){if(e.getAction()==android.view.MotionEvent.ACTION_UP){if(controls.getVisibility()==View.VISIBLE)hideControls();else showControls();}return true;}};video.setOnTouchListener(taps);screen.setOnTouchListener(taps);
        pause.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(!ready)return;showControls();if(player.isPlaying()){player.pause();pause.setText("Play");}else{player.start();pause.setText("Pause");}report("progress",ticks(),!player.isPlaying(),false);showControls();}});
        back.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(ready)start(Math.max(0,ticks()-300000000L));}});forward.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(ready)start(Math.min(Math.max(0,duration-10000000L),ticks()+300000000L));}});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seeking=true;ui.removeCallbacks(hide);}public void onProgressChanged(SeekBar s,int p,boolean user){if(user)time.setText(format(p));}public void onStopTrackingTouch(SeekBar s){seeking=false;showControls();if(ready)start(s.getProgress()*10000000L);}});
        video.getHolder().addCallback(this);video.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }
    private final Runnable hide=new Runnable(){public void run(){if(!seeking&&ready&&player!=null&&player.isPlaying())hideControls();}};
    private void hideControls(){ui.removeCallbacks(hide);if(controls!=null)controls.setVisibility(View.GONE);}
    private void showControls(){if(controls!=null)controls.setVisibility(View.VISIBLE);ui.removeCallbacks(hide);if(ready&&player!=null&&player.isPlaying())ui.postDelayed(hide,3000);}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),0,dp(4),0);return b;}
    private String format(long seconds){return (seconds/3600>0?seconds/3600+":":"")+String.format(java.util.Locale.US,seconds/3600>0?"%02d:%02d":"%d:%02d",(seconds/60)%60,seconds%60);}
    private long ticks(){long pos=offset;try{if(ready)pos+=player.getCurrentPosition()*10000L;}catch(Exception ignored){}return duration>0?Math.min(duration,pos):pos;}
    private void start(final long at){if(dead)return;final int current=++version;long oldPosition=ticks();ready=false;ui.removeCallbacks(tick);ui.removeCallbacks(hide);if(player!=null){player.release();player=null;}if(proxy!=null){proxy.close();proxy=null;}final JellyfinApi.Playback old=session;session=null;if(old!=null)stopSession(old,oldPosition);
        status.setText("Loading "+name+"…");status.setVisibility(View.VISIBLE);pause.setEnabled(false);
        tasks.execute(new Runnable(){public void run(){JellyfinApi.Playback p=null;Exception failure=null;try{p=api.playback(id,at,quality);}catch(Exception e){failure=e;}final JellyfinApi.Playback plan=p;final Exception error=failure;ui.post(new Runnable(){public void run(){if(dead||current!=version){if(plan!=null)stopSession(plan,at);return;}if(error!=null){status.setText(error.getMessage());return;}session=plan;offset=at;open(plan,current);}});}});
    }
    private void open(final JellyfinApi.Playback plan,final int current){try{
        proxy=new StreamProxy(api.getBaseUrl(),api.playbackHeaders(),getCacheDir());player=new MediaPlayer();player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);player.setDisplay(video.getHolder());player.setDataSource(proxy.local(plan.url));player.setScreenOnWhilePlaying(true);
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){if(dead||current!=version)return;ready=true;fit(p.getVideoWidth(),p.getVideoHeight());status.setVisibility(View.GONE);pause.setEnabled(true);pause.setText("Pause");p.start();showControls();report("start",offset,false,false);ui.post(tick);}});
        player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener(){public void onVideoSizeChanged(MediaPlayer p,int w,int h){fit(w,h);}});
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){report("stop",duration>0?duration:ticks(),false,true);session=null;finish();}});
        player.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){long pos=ticks();ready=false;ui.removeCallbacks(tick);report("stop",pos,true,true);session=null;status.setVisibility(View.VISIBLE);status.setText("Video could not play ("+what+"/"+extra+"). "+(proxy==null?"":proxy.lastError()));return true;}});
        player.prepareAsync();
    }catch(Exception e){status.setText(e.getMessage());if(session!=null){stopSession(session,offset);session=null;}}}
    private void fit(int w,int h){if(w<=0||h<=0)return;int sw=screen.getWidth(),sh=screen.getHeight();if(sw<=0||sh<=0)return;double scale=Math.min((double)sw/w,(double)sh/h);video.setLayoutParams(new FrameLayout.LayoutParams((int)(w*scale),(int)(h*scale),Gravity.CENTER));}
    private final Runnable tick=new Runnable(){public void run(){if(dead||!ready)return;long pos=ticks();if(!seeking)seek.setProgress((int)(pos/10000000L));time.setText(format(pos/10000000L)+" / "+format(duration/10000000L));if(++clock%10==0)report("progress",pos,!player.isPlaying(),false);ui.postDelayed(this,1000);}};
    private void report(final String event,final long pos,final boolean paused,final boolean stop){final JellyfinApi.Playback p=session;if(p==null)return;tasks.execute(new Runnable(){public void run(){try{api.report(event,id,p,pos,paused);}catch(Exception ignored){}if(stop)api.stopEncoding(p);}});}
    private void stopSession(final JellyfinApi.Playback p,final long pos){Runnable cleanup=new Runnable(){public void run(){try{api.report("stop",id,p,pos,true);}catch(Exception ignored){}api.stopEncoding(p);}};if(tasks.isShutdown())new Thread(cleanup,"video-cleanup").start();else tasks.execute(cleanup);}
    public void surfaceCreated(SurfaceHolder h){if(player==null&&!dead)start(startTicks);}
    public void surfaceChanged(SurfaceHolder h,int format,int w,int ht){if(player!=null&&ready)fit(player.getVideoWidth(),player.getVideoHeight());}
    public void surfaceDestroyed(SurfaceHolder h){}
    @Override protected void onStop(){super.onStop();if(!isFinishing())finish();}
    @Override protected void onDestroy(){long pos=ticks();dead=true;version++;ui.removeCallbacks(tick);ui.removeCallbacks(hide);if(session!=null)stopSession(session,pos);if(player!=null)player.release();if(proxy!=null)proxy.close();tasks.shutdown();super.onDestroy();}
}
