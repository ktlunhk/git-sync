package com.alex.gitsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground owner for repository network/file operations.
 *
 * The sync algorithm intentionally remains in GitSyncEngine.  This service owns
 * operation lifetime so a transfer is no longer started by MainActivity itself.
 */
public class GitSyncService extends Service {
    private static final String CHANNEL_ID = "mygitsync_sync";
    private static final int NOTIFICATION_ID = 47;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer,GitSyncEngine.Callback> callbacks = new ConcurrentHashMap<Integer,GitSyncEngine.Callback>();
    private static final ConcurrentHashMap<Integer,GitSyncEngine.PreviewCallback> previewCallbacks = new ConcurrentHashMap<Integer,GitSyncEngine.PreviewCallback>();
    private static final AtomicInteger active = new AtomicInteger(0);

    private static final String X_ID="id", X_MODE="mode", X_NAME="name", X_URL="url", X_BRANCH="branch", X_LOCAL="local", X_USER="user", X_TOKEN="token";
    public static final int DOWNLOAD=0, UPLOAD=1, SYNC=2, MIRROR_LOCAL_TO_REMOTE=3, MIRROR_REMOTE_TO_LOCAL=4, PREVIEW=5;

    public static void download(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,DOWNLOAD,cb,null); }
    public static void upload(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,UPLOAD,cb,null); }
    public static void sync(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,SYNC,cb,null); }
    public static void mirrorLocalToRemote(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,MIRROR_LOCAL_TO_REMOTE,cb,null); }
    public static void mirrorRemoteToLocal(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,MIRROR_REMOTE_TO_LOCAL,cb,null); }
    public static void previewSync(Context c, RepoProfile p, GitSyncEngine.PreviewCallback cb) { start(c,p,PREVIEW,null,cb); }
    public static void cancel() { GitSyncEngine.cancel(); }

    private static void start(Context c, RepoProfile p, int mode, GitSyncEngine.Callback cb, GitSyncEngine.PreviewCallback pcb) {
        int id=NEXT_ID.getAndIncrement();
        if(cb!=null) callbacks.put(Integer.valueOf(id),cb);
        if(pcb!=null) previewCallbacks.put(Integer.valueOf(id),pcb);
        Intent i=new Intent(c,GitSyncService.class);
        i.putExtra(X_ID,id); i.putExtra(X_MODE,mode); i.putExtra(X_NAME,p.name); i.putExtra(X_URL,p.url); i.putExtra(X_BRANCH,p.branch); i.putExtra(X_LOCAL,p.localPath); i.putExtra(X_USER,p.username); i.putExtra(X_TOKEN,p.token);
        if(Build.VERSION.SDK_INT>=26) c.startForegroundService(i); else c.startService(i);
    }

    public void onCreate() { super.onCreate(); createChannel(); }
    public IBinder onBind(Intent i) { return null; }

    public int onStartCommand(Intent i, int flags, final int startId) {
        if(i==null) return START_NOT_STICKY;
        final int id=i.getIntExtra(X_ID,0), mode=i.getIntExtra(X_MODE,-1);
        final RepoProfile p=new RepoProfile(n(i,X_NAME),n(i,X_URL),n(i,X_BRANCH),n(i,X_LOCAL),n(i,X_USER),n(i,X_TOKEN));
        active.incrementAndGet();
        startForeground(NOTIFICATION_ID, notification("Checking " + p.name + "..."));
        if(mode==PREVIEW) {
            final GitSyncEngine.PreviewCallback target=previewCallbacks.get(Integer.valueOf(id));
            GitSyncEngine.previewSync(getApplicationContext(),p,new GitSyncEngine.PreviewCallback(){
                public void ready(GitSyncEngine.SyncPreview x){ try { if(target!=null) target.ready(x); } finally { finish(id,startId); } }
                public void error(String m){ try { if(target!=null) target.error(m); } finally { finish(id,startId); } }
            });
            return START_NOT_STICKY;
        }
        final GitSyncEngine.Callback target=callbacks.get(Integer.valueOf(id));
        GitSyncEngine.CreationCallback bridge=new GitSyncEngine.CreationCallback(){
            public void progress(String m){ updateNotification(p.name,m); if(target!=null) target.progress(m); }
            public boolean confirmCreateRepository(String owner,String repository){ return target instanceof GitSyncEngine.CreationCallback && ((GitSyncEngine.CreationCallback)target).confirmCreateRepository(owner,repository); }
            public void done(String m){ try { updateNotification(p.name,m); if(target!=null) target.done(m); } finally { finish(id,startId); } }
        };
        if(mode==DOWNLOAD) GitSyncEngine.download(getApplicationContext(),p,bridge);
        else if(mode==UPLOAD) GitSyncEngine.upload(getApplicationContext(),p,bridge);
        else if(mode==SYNC) GitSyncEngine.sync(getApplicationContext(),p,bridge);
        else if(mode==MIRROR_LOCAL_TO_REMOTE) GitSyncEngine.mirrorLocalToRemote(getApplicationContext(),p,bridge);
        else if(mode==MIRROR_REMOTE_TO_LOCAL) GitSyncEngine.mirrorRemoteToLocal(getApplicationContext(),p,bridge);
        else { callbacks.remove(Integer.valueOf(id)); finish(id,startId); }
        return START_NOT_STICKY;
    }

    private void finish(int id,int startId){ callbacks.remove(Integer.valueOf(id)); previewCallbacks.remove(Integer.valueOf(id)); if(active.decrementAndGet()<=0){ active.set(0); stopForeground(true); stopSelf(); } }
    private static String n(Intent i,String k){ String s=i.getStringExtra(k); return s==null?"":s; }
    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Sync operations",NotificationManager.IMPORTANCE_LOW); c.setDescription("Shows active myGitSync transfers"); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c); } }
    private Notification notification(String text){ Intent open=new Intent(this,MainActivity.class); open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP); int pf=PendingIntent.FLAG_UPDATE_CURRENT; if(Build.VERSION.SDK_INT>=23) pf|=PendingIntent.FLAG_IMMUTABLE; PendingIntent pi=PendingIntent.getActivity(this,0,open,pf); Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this); return b.setSmallIcon(R.drawable.ic_gitsync).setContentTitle("myGitSync").setContentText(shortText(text)).setContentIntent(pi).setOngoing(true).build(); }
    private void updateNotification(String repo,String m){ ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification((repo==null?"":repo+": ")+m)); }
    private String shortText(String s){ if(s==null)return "Syncing..."; return s.length()>120?s.substring(0,117)+"...":s; }
}
