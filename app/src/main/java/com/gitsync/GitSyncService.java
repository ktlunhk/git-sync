package com.gitsync;

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
    private static final Object operationLock = new Object();
    private static volatile String activeRepositoryKey = null;

    private static final String X_ID="id", X_MODE="mode", X_NAME="name", X_URL="url", X_BRANCH="branch", X_LOCAL="local", X_USER="user";
    public static final int DOWNLOAD=0, UPLOAD=1, SYNC=2, MIRROR_LOCAL_TO_REMOTE=3, MIRROR_REMOTE_TO_LOCAL=4, PREVIEW=5;

    public static void download(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,DOWNLOAD,cb,null); }
    public static void upload(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,UPLOAD,cb,null); }
    public static void sync(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,SYNC,cb,null); }
    public static void mirrorLocalToRemote(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,MIRROR_LOCAL_TO_REMOTE,cb,null); }
    public static void mirrorRemoteToLocal(Context c, RepoProfile p, GitSyncEngine.Callback cb) { start(c,p,MIRROR_REMOTE_TO_LOCAL,cb,null); }
    public static void previewSync(Context c, RepoProfile p, GitSyncEngine.PreviewCallback cb) { start(c,p,PREVIEW,null,cb); }
    public static void cancel() { GitSyncEngine.cancel(); }
    public static boolean isAnyOperationActive() { synchronized(operationLock) { return activeRepositoryKey != null; } }
    public static boolean isRepositoryActive(RepoProfile p) {
        String k = p == null ? null : GitServerConfig.repositoryKey(p.url);
        synchronized(operationLock) { return k != null && k.equals(activeRepositoryKey); }
    }

    private static void start(Context c, RepoProfile p, int mode, GitSyncEngine.Callback cb, GitSyncEngine.PreviewCallback pcb) {
        final String repoKey=GitServerConfig.repositoryKey(p.url);
        synchronized(operationLock) {
            if (activeRepositoryKey != null) {
                if (pcb != null) pcb.error("Another repository operation is already running");
                else if (cb != null) cb.done("ERROR " + p.name + ": Another repository operation is already running");
                return;
            }
            activeRepositoryKey = repoKey == null ? ("profile:" + p.name) : repoKey;
        }
        int id=NEXT_ID.getAndIncrement();
        if(cb!=null) callbacks.put(Integer.valueOf(id),cb);
        if(pcb!=null) previewCallbacks.put(Integer.valueOf(id),pcb);
        Intent i=new Intent(c,GitSyncService.class);
        i.putExtra(X_ID,id); i.putExtra(X_MODE,mode); i.putExtra(X_NAME,p.name); i.putExtra(X_URL,p.url); i.putExtra(X_BRANCH,p.branch); i.putExtra(X_LOCAL,p.localPath); i.putExtra(X_USER,p.username);
        try {
            if(Build.VERSION.SDK_INT>=26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException (Android 12+). Release the lock, otherwise
            // every later operation reports "Another repository operation is already running".
            callbacks.remove(Integer.valueOf(id)); previewCallbacks.remove(Integer.valueOf(id));
            synchronized(operationLock) { activeRepositoryKey = null; }
            String msg = "Cannot start sync service: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (pcb != null) pcb.error(msg); else if (cb != null) cb.done("ERROR " + p.name + ": " + msg);
        }
    }

    public void onCreate() { super.onCreate(); createChannel(); }
    public IBinder onBind(Intent i) { return null; }

    public int onStartCommand(Intent i, int flags, final int startId) {
        if(i==null) return START_NOT_STICKY;
        final int id=i.getIntExtra(X_ID,0), mode=i.getIntExtra(X_MODE,-1);
        final RepoProfile p=new RepoProfile(n(i,X_NAME),n(i,X_URL),n(i,X_BRANCH),n(i,X_LOCAL),n(i,X_USER),GitHubAuth.token(getApplicationContext()));
        active.incrementAndGet();
        try {
            startForeground(NOTIFICATION_ID, notification("Checking " + p.name + "..."));
        } catch (Exception e) {
            String msg = "Cannot start sync service: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            GitSyncEngine.Callback t = callbacks.get(Integer.valueOf(id)); GitSyncEngine.PreviewCallback pt = previewCallbacks.get(Integer.valueOf(id));
            try { if (mode == PREVIEW) { if (pt != null) pt.error(msg); } else if (t != null) t.done("ERROR " + p.name + ": " + msg); }
            finally { finish(id, startId); }
            return START_NOT_STICKY;
        }
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

    /**
     * Android 15+: dataSync foreground services have a time limit. The system calls this and expects the
     * service to stop within a few seconds, otherwise the app is treated as not responding.
     */
    public void onTimeout(int startId, int fgsType) {
        GitSyncEngine.cancel();
        stopForeground(true);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID + 1, notification("Sync stopped: Android's background time limit was reached. Open myGitSync and start it again to continue.", false));
        stopSelf();
    }

    private void finish(int id,int startId){ callbacks.remove(Integer.valueOf(id)); previewCallbacks.remove(Integer.valueOf(id)); synchronized(operationLock){ activeRepositoryKey=null; } if(active.decrementAndGet()<=0){ active.set(0); stopForeground(true); stopSelf(); } }
    private static String n(Intent i,String k){ String s=i.getStringExtra(k); return s==null?"":s; }
    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Sync operations",NotificationManager.IMPORTANCE_LOW); c.setDescription("Shows active myGitSync transfers"); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c); } }
    private Notification notification(String text){ return notification(text,true); }
    private Notification notification(String text,boolean ongoing){ Intent open=new Intent(this,MainActivity.class); open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP); int pf=PendingIntent.FLAG_UPDATE_CURRENT; if(Build.VERSION.SDK_INT>=23) pf|=PendingIntent.FLAG_IMMUTABLE; PendingIntent pi=PendingIntent.getActivity(this,0,open,pf); Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this); return b.setSmallIcon(R.drawable.ic_gitsync).setContentTitle("myGitSync").setContentText(shortText(text)).setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).build(); }
    private void updateNotification(String repo,String m){ ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification((repo==null?"":repo+": ")+m)); }
    private String shortText(String s){ if(s==null)return "Syncing..."; return s.length()>120?s.substring(0,117)+"...":s; }
}
