package org.jellyvideo.legacy;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private JellyfinApi api; private ImageLoader images;
    private final Handler ui=new Handler();
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout root,body; private TextView title; private Button more;
    private ListView list; private MediaAdapter adapter;
    private final ArrayList<MediaItem> rows=new ArrayList<MediaItem>();
    private String section="Movies",type="Movie",parent,filter,query;
    private int page,generation;private boolean busy,dead;
    private final ArrayList<String[]> history=new ArrayList<String[]>();
    private Runnable poll;
    private interface Request { Object run()throws Exception; }
    private interface Result { void done(Object value); }
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),0,dp(4),0);return b;}
    private TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(dp(10),dp(8),dp(10),dp(8));return t;}
    private void request(final Request r,final Result done){final int stamp=generation;network.execute(new Runnable(){public void run(){Object result;try{result=r.run();}catch(Exception e){result=e;}final Object value=result;ui.post(new Runnable(){public void run(){if(!dead&&stamp==generation)done.done(value);}});}});}
    private boolean error(Object o){if(o instanceof Exception){Toast.makeText(this,((Exception)o).getMessage(),Toast.LENGTH_LONG).show();return true;}return false;}
    @Override public void onCreate(Bundle b){super.onCreate(b);api=new JellyfinApi(this);images=new ImageLoader(api);shell();if(api.hasSession())show("Movies","Movie",null,null,null,false);else login();}
    private void shell(){root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);LinearLayout bar=new LinearLayout(this);title=label("JellyVideo Legacy",18);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));Button menu=button("Menu");menu.setOnClickListener(new View.OnClickListener(){public void onClick(View v){menu();}});bar.addView(menu,new LinearLayout.LayoutParams(dp(58),dp(48)));root.addView(bar);body=new LinearLayout(this);body.setOrientation(1);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);}
    private void login(){generation++;body.removeAllViews();title.setText("Connect to Jellyfin");body.addView(label("Movies and shows on your server",20));final EditText server=new EditText(this);server.setSingleLine(true);server.setHint("https://your-server");server.setText(api.getBaseUrl());body.addView(server);Button connect=button("Connect with Quick Connect");body.addView(connect);connect.setOnClickListener(new View.OnClickListener(){public void onClick(View v){api.setServer(server.getText().toString());v.setEnabled(false);request(new Request(){public Object run()throws Exception{api.serverInfo();return api.beginQuickConnect();}},new Result(){public void done(Object o){connect.setEnabled(true);if(!error(o)){org.json.JSONObject j=(org.json.JSONObject)o;quick(j.optString("Code"),j.optString("Secret"));}}});}});}
    private void quick(String code,final String secret){body.removeAllViews();body.addView(label("Approve in Jellyfin → Quick Connect",18));body.addView(label(code,36));poll=new Runnable(){public void run(){request(new Request(){public Object run()throws Exception{return api.quickConnectState(secret);}},new Result(){public void done(Object o){if(o instanceof org.json.JSONObject&&((org.json.JSONObject)o).optBoolean("Authenticated")){request(new Request(){public Object run()throws Exception{api.finishQuickConnect(secret);return true;}},new Result(){public void done(Object x){if(!error(x))show("Movies","Movie",null,null,null,false);}});}else if(!dead)ui.postDelayed(poll,5000);}});}};ui.postDelayed(poll,2000);}
    private void menu(){final String[] choices=api.hasSession()?new String[]{"Movies","TV shows","Continue watching","Favorites","Search","Refresh","Playback quality","Previous page","Log out"}:new String[]{"Playback quality"};new AlertDialog.Builder(this).setItems(choices,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){String s=choices[n];if(s.equals("Previous page")){if(!busy&&page>1){page-=2;load();}return;}if(s.equals("Playback quality")){quality();return;}if(s.equals("Log out")){api.logout();history.clear();login();return;}if(s.equals("Refresh")){show(section,type,parent,filter,query,false);return;}if(s.equals("Search")){search();return;}history.clear();if(s.equals("Movies"))show(s,"Movie",null,null,null,false);else if(s.equals("TV shows"))show(s,"Series",null,null,null,false);else show(s,"Movie,Episode",null,s.equals("Favorites")?"IsFavorite":"IsResumable",null,false);}}).show();}
    private void quality(){final String[] options={"240p · low data (default)","320p · balanced","480p · higher quality"};final android.content.SharedPreferences prefs=getSharedPreferences("settings",0);new AlertDialog.Builder(this).setTitle("Server transcoding quality").setSingleChoiceItems(options,prefs.getInt("quality",0),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){prefs.edit().putInt("quality",n).apply();d.dismiss();}}).show();}
    private void search(){final EditText input=new EditText(this);input.setSingleLine(true);new AlertDialog.Builder(this).setTitle("Search movies and shows").setView(input).setPositiveButton("Search",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){show("Search","Movie,Series,Episode",null,null,input.getText().toString(),true);}}).setNegativeButton("Cancel",null).show();}
    private void show(String heading,String itemType,String itemParent,String itemFilter,String itemQuery,boolean push){
        if(poll!=null)ui.removeCallbacks(poll);
        if(push)history.add(new String[]{section,type,parent,filter,query});
        generation++;section=heading;type=itemType;parent=itemParent;filter=itemFilter;query=itemQuery;page=0;busy=false;rows.clear();body.removeAllViews();title.setText(heading);
        list=new ListView(this);list.setCacheColorHint(Color.BLACK);more=button("Loading…");list.addFooterView(more,null,false);adapter=new MediaAdapter(this,rows,images);list.setAdapter(adapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        more.setOnClickListener(new View.OnClickListener(){public void onClick(View v){load();}});
        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int position,long id){if(position>=rows.size())return;MediaItem m=rows.get(position);if("Series".equals(m.kind))show(m.name,"Season",m.id,null,null,true);else if("Season".equals(m.kind))show(m.name,"Episode",m.id,null,null,true);else detail(m.id);}});
        load();
    }
    private void load(){if(busy)return;busy=true;more.setText("Loading…");more.setEnabled(false);final String t=type,p=parent,f=filter,q=query;final int pg=page;
        request(new Request(){public Object run()throws Exception{return api.items(t,p,f,q,pg);}},new Result(){@SuppressWarnings("unchecked")public void done(Object o){busy=false;if(error(o)){more.setText("Retry");more.setEnabled(true);return;}List<MediaItem> batch=(List<MediaItem>)o;rows.clear();rows.addAll(batch);page++;title.setText(section+" · "+page);adapter.notifyDataSetChanged();more.setEnabled(batch.size()==40);more.setText(batch.size()==40?"Next page":"End of list");if(rows.size()==0)more.setText("No titles found");}});
    }
    private void detail(final String id){request(new Request(){public Object run()throws Exception{return api.item(id);}},new Result(){public void done(Object o){if(!error(o))details((MediaItem)o);}});}
    private void details(final MediaItem m){LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.addView(label(m.subtitle,14));box.addView(label(m.overview==null?"":m.overview,16));ScrollView scroll=new ScrollView(this);scroll.addView(box);
        final String[] actions=m.resumeTicks>0?new String[]{"Resume","Play from beginning",m.favorite?"Remove favorite":"Add favorite",m.watched?"Mark unwatched":"Mark watched"}:new String[]{"Play",m.favorite?"Remove favorite":"Add favorite",m.watched?"Mark unwatched":"Mark watched"};
        final AlertDialog info=new AlertDialog.Builder(this).setTitle(m.name).setView(scroll).setPositiveButton("Actions",null).setNegativeButton("Close",null).create();info.show();info.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){public void onClick(View v){info.dismiss();new AlertDialog.Builder(MainActivity.this).setTitle(m.name).setItems(actions,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){String a=actions[n];if(a.startsWith("Play")||a.equals("Resume")){Intent intent=new Intent(MainActivity.this,VideoActivity.class);intent.putExtra("id",m.id);intent.putExtra("title",m.name);intent.putExtra("duration",m.durationTicks);intent.putExtra("start",a.equals("Resume")?m.resumeTicks:0L);startActivity(intent);}else {final boolean fav=a.contains("favorite");final boolean value=a.startsWith("Add")||a.equals("Mark watched");request(new Request(){public Object run()throws Exception{if(fav)api.favorite(m.id,value);else api.watched(m.id,value);return true;}},new Result(){public void done(Object o){if(!error(o)){Toast.makeText(MainActivity.this,"Saved",Toast.LENGTH_SHORT).show();show(section,type,parent,filter,query,false);}}});}}}).show();}});
    }
    @Override public void onBackPressed(){if(history.size()>0){String[] h=history.remove(history.size()-1);show(h[0],h[1],h[2],h[3],h[4],false);}else super.onBackPressed();}
    @Override protected void onDestroy(){dead=true;generation++;if(poll!=null)ui.removeCallbacks(poll);network.shutdownNow();images.close();super.onDestroy();}
}
