package cn.local.tickethelper;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import cn.local.tickethelper.computer.ComputerLease;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Private, local session only. No network server, shell execution or purchase grant. */
final class ComputerLinkStore {
    private static final Object LOCK=new Object();
    private final AtomicFile file;
    private final Context context;
    ComputerLinkStore(Context c){context=c.getApplicationContext();file=new AtomicFile(new File(c.getFilesDir(),"computer-link-session.json"));}
    ComputerLease read(){synchronized(LOCK){try{
        JSONObject j=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
        if(j.getInt("boot")!=boot())return null;
        return new ComputerLease(j.getString("session"),j.getString("owner"),j.getLong("wall"),j.getLong("elapsed"));
    }catch(Exception e){return null;}}}
    long remaining(){ComputerLease l=read();return l==null?0:l.remaining(System.currentTimeMillis(),SystemClock.elapsedRealtime());}
    void begin() throws Exception {synchronized(LOCK){
        save(new ComputerLease(java.util.UUID.randomUUID().toString().replace("-",""),"",System.currentTimeMillis(),SystemClock.elapsedRealtime()));
    }}
    void claim(String who) throws Exception {synchronized(LOCK){ComputerLease l=read();if(l==null)throw new IllegalArgumentException();
        save(l.claim(who,System.currentTimeMillis(),SystemClock.elapsedRealtime()));}}
    boolean permits(String who){ComputerLease l=read();return l!=null&&l.permits(who,System.currentTimeMillis(),SystemClock.elapsedRealtime());}
    void end(){synchronized(LOCK){file.delete();}}
    private void save(ComputerLease l)throws Exception {
        JSONObject j=new JSONObject().put("session",l.session).put("owner",l.owner).put("wall",l.startedWall).put("elapsed",l.startedElapsed).put("boot",boot());
        FileOutputStream out=null;
        try{out=file.startWrite();out.write(j.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private int boot(){int n=android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);if(n<0)throw new IllegalStateException();return n;}
}
