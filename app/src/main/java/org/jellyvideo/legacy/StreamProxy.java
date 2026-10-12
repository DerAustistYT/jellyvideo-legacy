package org.jellyvideo.legacy;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Native Android 4 video networking does not use Java's modern TLS provider.
 * Stream a bounded amount of data over loopback, using Java TLS upstream. */
public final class StreamProxy {
    private final ServerSocket server; private final File cacheDir;
    private final URL origin;
    private final Map<String,String> headers;
    private final Map<String,String> routes=new HashMap<String,String>();
    private final Map<String,String> reverse=new HashMap<String,String>();
    private final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<Socket>());
    private final String secret=UUID.randomUUID().toString();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(8));
    private volatile boolean stopped; private volatile String lastError=""; public String lastError(){return lastError;}
    private static final Pattern URI=Pattern.compile("URI=\"([^\"]+)\"");
    public StreamProxy(String base,Map<String,String> auth)throws IOException{
        this(base,auth,new File(System.getProperty("java.io.tmpdir")));
    }
    public StreamProxy(String base,Map<String,String> auth,File cache)throws IOException{
        origin=new URL(base);headers=auth;cacheDir=cache;
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        new Thread(new Runnable(){public void run(){
            while(!stopped)try{
                final Socket s=server.accept();s.setSoTimeout(30000);sockets.add(s);
                try{workers.execute(new Runnable(){public void run(){serve(s);}});}catch(RejectedExecutionException e){sockets.remove(s);s.close();}
            }catch(IOException e){if(stopped)return;}
        }},"video-loopback").start();
    }
    public synchronized String local(String target)throws IOException{
        URL u=new URL(target);check(u);
        String key=reverse.get(target);
        if(key==null){if(routes.size()>=4096)throw new IOException("Too many stream segments");key=Integer.toHexString(routes.size())+(u.getPath().endsWith(".m3u8")?".m3u8":".ts");routes.put(key,target);reverse.put(target,key);}
        return "http://127.0.0.1:"+server.getLocalPort()+"/"+secret+"/"+key;
    }
    private void check(URL u)throws IOException{
        if(!origin.getHost().equalsIgnoreCase(u.getHost())||origin.getPort()!=u.getPort()||!origin.getProtocol().equals(u.getProtocol()))throw new IOException("Stream URL outside configured server");
    }
    private String line(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1&&c!='\n'){if(out.size()>=8192)throw new IOException("Header too long");if(c!='\r')out.write(c);}
        return out.toString("ISO-8859-1");
    }
    private void serve(Socket socket){HttpURLConnection c=null;File manifest=null;try{
        InputStream input=new BufferedInputStream(socket.getInputStream());OutputStream output=new BufferedOutputStream(socket.getOutputStream());
        String[] request=line(input).split(" ");if(request.length<2||!(request[0].equals("GET")||request[0].equals("HEAD")))throw new IOException("Unsupported request");
        System.out.println("JVideoProxy request "+request[0]); if(request[1].startsWith("http://"))request[1]=new URL(request[1]).getFile(); int queryAt=request[1].indexOf('?' );if(queryAt>=0)request[1]=request[1].substring(0,queryAt); String prefix="/"+secret+"/";if(!request[1].startsWith(prefix))throw new IOException("Unknown route");
        String target; synchronized(this){target=routes.get(request[1].substring(prefix.length()));}if(target==null)throw new IOException("Unknown stream");
        String range=null;int total=0;while(true){String h=line(input);if(h.length()==0)break;total+=h.length();if(total>16384)throw new IOException("Headers too large");if(h.toLowerCase(Locale.US).startsWith("range:"))range=h.substring(6).trim();}
        URL url=new URL(target);
        for(int redirects=0;;redirects++){
            check(url);c=org.jellylegacy.net.Network.open(url);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");
            for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());if(range!=null&&!url.getPath().endsWith(".m3u8"))c.setRequestProperty("Range",range);
            int status=c.getResponseCode();if(status>=300&&status<400&&redirects<3){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Missing redirect");URL next=new URL(url,location);c.disconnect();url=next;continue;}break;
        }
        int status=c.getResponseCode();String type=c.getContentType();boolean playlist=url.getPath().endsWith(".m3u8")||(type!=null&&type.toLowerCase(Locale.US).contains("mpegurl"));
        if(status<200||status>=300){lastError="Jellyfin HTTP "+status;InputStream errors=c.getErrorStream();if(errors!=null){byte[] errorBytes=new byte[1024];int errorLength=errors.read(errorBytes);if(errorLength>0)lastError+=" "+new String(errorBytes,0,errorLength,"UTF-8");errors.close();}System.out.println("JVideoProxy "+lastError);writeHead(output,status,"text/plain",0,null);output.flush();return;} System.out.println("JVideoProxy upstream "+status+" playlist="+playlist);
        if(playlist){
            manifest=File.createTempFile("hls-",".m3u8",cacheDir);
            InputStream upstream=new BufferedInputStream(c.getInputStream(),8192);BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(manifest),"UTF-8"),8192);
            try{long totalChars=0;while(true){String entry=playlistLine(upstream);if(entry==null)break;totalChars+=entry.length();if(totalChars>16777216)throw new IOException("Playlist exceeds 16 MB");
                if(entry.length()>0&&!entry.startsWith("#"))entry=local(new URL(url,entry.trim()).toString());
                else{Matcher m=URI.matcher(entry);StringBuffer replaced=new StringBuffer();while(m.find())m.appendReplacement(replaced,Matcher.quoteReplacement("URI=\""+local(new URL(url,m.group(1)).toString())+"\""));m.appendTail(replaced);entry=replaced.toString();}
                writer.write(entry);writer.write('\n');
            }}finally{writer.close();upstream.close();}
            writeHead(output,200,"application/x-mpegURL",manifest.length(),null);
            if(!request[0].equals("HEAD")){InputStream body=new FileInputStream(manifest);try{byte[] b=new byte[16384];int n;while((n=body.read(b))!=-1)output.write(b,0,n);}finally{body.close();}}
        }else{
            long length=-1;try{length=Long.parseLong(c.getHeaderField("Content-Length"));}catch(Exception ignored){}
            writeHead(output,status,type==null?"video/mp2t":type,length,c.getHeaderField("Content-Range"));
            if(!request[0].equals("HEAD")){InputStream upstream=c.getInputStream();byte[] b=new byte[16384];int n;while(!stopped&&(n=upstream.read(b))!=-1)output.write(b,0,n);upstream.close();}
        }
        output.flush();
    }catch(Exception failure){lastError=failure.getClass().getSimpleName()+": "+failure.getMessage();System.out.println("JVideoProxy "+lastError);try{OutputStream o=socket.getOutputStream();writeHead(o,502,"text/plain",0,null);o.flush();}catch(Exception ignoredToo){}}
    finally{if(manifest!=null)manifest.delete();if(c!=null)c.disconnect();sockets.remove(socket);try{socket.close();}catch(Exception ignored){}}}
    private String playlistLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1&&c!='\n'){if(b.size()>16384)throw new IOException("Playlist line too long");if(c!='\r')b.write(c);}return c==-1&&b.size()==0?null:b.toString("UTF-8");}
    private void writeHead(OutputStream o,int status,String type,long length,String range)throws IOException{
        String text="HTTP/1.1 "+status+" "+(status==200?"OK":status==206?"Partial Content":"Error")+"\r\nContent-Type: "+type+"\r\nConnection: close\r\n";
        if(length>=0)text+="Content-Length: "+length+"\r\n";if(range!=null)text+="Content-Range: "+range+"\r\n";o.write((text+"\r\n").getBytes("ISO-8859-1"));
    }
    public void close(){stopped=true;try{server.close();}catch(Exception ignored){}synchronized(sockets){for(Socket s:sockets)try{s.close();}catch(Exception ignored){}sockets.clear();}workers.shutdownNow();synchronized(this){routes.clear();reverse.clear();}}
}
