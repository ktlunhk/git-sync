package com.alex.gitsync;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import android.content.res.AssetFileDescriptor;
import android.util.Base64OutputStream;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

public class GitSyncEngine {
    // Cancellation uses generations rather than a persistent boolean.
    // STOP invalidates operations that are already running, while operations
    // started afterwards automatically belong to the new generation.
    private static final AtomicInteger cancelGeneration = new AtomicInteger(0);
    private static final ThreadLocal<Integer> operationGeneration = new ThreadLocal<Integer>();
    public static void cancel() { cancelGeneration.incrementAndGet(); }
    private static void beginOperation() { operationGeneration.set(Integer.valueOf(cancelGeneration.get())); }
    private static void endOperation() { operationGeneration.remove(); }
    private static void checkCancelled() throws Exception {
        Integer started = operationGeneration.get();
        if (started != null && started.intValue() != cancelGeneration.get()) throw new Exception("Sync stopped by user");
    }
    public interface Callback { void progress(String message); void done(String message); }
    public interface CreationCallback extends Callback { boolean confirmCreateRepository(String owner, String repository); }
    public interface PreviewCallback { void ready(SyncPreview preview); void error(String message); }
    public static class SyncPreview {
        public int upload, download, conflict, unchanged;
        public ArrayList<String> uploads = new ArrayList<String>();
        public ArrayList<String> downloads = new ArrayList<String>();
        public ArrayList<String> conflicts = new ArrayList<String>();
        public String summary() { return "Upload " + upload + "   Download " + download + "   Conflicts " + conflict + "   Unchanged " + unchanged; }
    }
    private static final int MAX_RETRIES = 5;
    private static final ThreadLocal<Callback> callbackHolder = new ThreadLocal<Callback>();
    public static void download(final Context c, final RepoProfile p, final Callback cb) { runTask(c, p, cb, 0); }
    public static void upload(final Context c, final RepoProfile p, final Callback cb) { runTask(c, p, cb, 1); }
    public static void sync(final Context c, final RepoProfile p, final Callback cb) { runTask(c, p, cb, 2); }
    public static void mirrorLocalToRemote(final Context c, final RepoProfile p, final Callback cb) { runTask(c, p, cb, 3); }
    public static void mirrorRemoteToLocal(final Context c, final RepoProfile p, final Callback cb) { runTask(c, p, cb, 4); }

    public static void previewSync(final Context c, final RepoProfile p, final PreviewCallback pcb) {
        new Thread(new Runnable() { public void run() { beginOperation(); try {
            RepoId id = parseRepo(p.url); if (id == null) throw new Exception("Invalid " + GitServerConfig.SERVER_NAME + " repository URL");
            if (p.localPath == null || !p.localPath.startsWith("content://")) throw new Exception("Select the local folder again.");
            Uri root = treeDocumentUri(Uri.parse(p.localPath));
            pcb.ready(buildPreview(c, p, id, root));
        } catch (Exception e) { pcb.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); } finally { endOperation(); } } }).start();
    }

    private static void runTask(final Context c, final RepoProfile p, final Callback cb, final int mode) {
        new Thread(new Runnable() { public void run() { beginOperation(); try {
            callbackHolder.set(cb); cb.progress("Starting " + p.name + "...");
            RepoId id = parseRepo(p.url); if (id == null) throw new Exception("Use a " + GitServerConfig.SERVER_NAME + " repository URL such as " + GitServerConfig.repositoryExampleUrl());
            ensureRepositoryExists(p, id);
            if (p.localPath == null || !p.localPath.startsWith("content://")) throw new Exception("Select the local folder again using Select local folder so Android can grant access.");
            Uri tree = Uri.parse(p.localPath); Uri root = treeDocumentUri(tree); int down = 0; int up = 0; cb.progress("Local folder access OK");
            if (mode == 0) { cb.progress("Reading " + GitServerConfig.SERVER_NAME + " repository..."); down = downloadRepository(c, p, id, root); }
            if (mode == 1) { cb.progress("Scanning local folder and uploading..."); up = uploadFolder(c, p, id, root, root, ""); }
            if (mode == 2) { cb.progress("Applying protected two-way sync..."); int[] sr = safeTwoWaySync(c, p, id, root); down = sr[0]; up = sr[1]; }
            if (mode == 3) { cb.progress("Mirror mode: local folder is the source of truth"); int[] result = mirrorFolder(c, p, id, root); up = result[0]; down = result[1]; }
            if (mode == 4) { cb.progress("Mirror mode: " + GitServerConfig.SERVER_NAME + " is the source of truth"); int[] result = mirrorRemoteFolder(c, p, id, root); down = result[0]; up = result[1]; }
            if (mode == 0) cb.done("Downloaded " + down + " file(s) from " + p.name); else if (mode == 1) cb.done("Uploaded " + up + " file(s) to " + p.name); else if (mode == 3) cb.done("Mirror complete: uploaded/updated " + up + ", deleted from " + GitServerConfig.SERVER_NAME + " " + down + " file(s)"); else if (mode == 4) cb.done("Mirror complete: downloaded/updated " + down + ", deleted locally " + up + " file(s)"); else cb.done("Sync complete: downloaded " + down + ", uploaded " + up + " file(s)");
        } catch (Exception e) { String m = e.getMessage(); if (m == null || m.length() == 0) m = e.getClass().getSimpleName(); cb.done("ERROR " + p.name + ": " + m); } finally { callbackHolder.remove(); endOperation(); } } }).start();
    }


    private static void ensureRepositoryExists(RepoProfile p, RepoId id) throws Exception {
        String api = GitServerConfig.repoApiUrl(id.owner, id.repo);
        try { request("GET", api, p.token, null, null); return; } catch (HttpError e) { if (e.code != 404) throw e; }
        Callback cb = callbackHolder.get();
        if (!(cb instanceof CreationCallback)) throw new Exception(GitServerConfig.SERVER_NAME + " repository not found: " + id.owner + "/" + id.repo);
        if (!((CreationCallback)cb).confirmCreateRepository(id.owner, id.repo)) throw new Exception("Repository creation cancelled by user");
        if (cb != null) cb.progress("Creating " + GitServerConfig.SERVER_NAME + " repository: " + id.owner + "/" + id.repo);
        JSONObject user = new JSONObject(request("GET", GitServerConfig.currentUserUrl(), p.token, null, null));
        String login = user.optString("login", "");
        String createUrl;
        if (login.equalsIgnoreCase(id.owner)) createUrl = GitServerConfig.createUserRepoUrl(); else createUrl = GitServerConfig.createOrgRepoUrl(id.owner);
        JSONObject body = new JSONObject(); body.put("name", id.repo); body.put("description", "Created by myGitSync"); body.put("private", false); body.put("auto_init", true);
        JSONObject created = new JSONObject(request("POST", createUrl, p.token, "application/json; charset=UTF-8", body.toString()));
        String fullName = created.optString("full_name", id.owner + "/" + id.repo);
        if (cb != null) cb.progress("Created " + GitServerConfig.SERVER_NAME + " repository: " + fullName);
        String defaultBranch = created.optString("default_branch", "");
        if (defaultBranch.length() > 0 && (p.branch == null || p.branch.trim().length() == 0 || "main".equals(p.branch.trim()))) p.branch = defaultBranch;
    }

    private static SyncPreview buildPreview(Context c, RepoProfile p, RepoId id, Uri root) throws Exception {
        HashMap<String,String> remote = remoteShas(p, id);
        HashMap<String,Uri> localUris = new HashMap<String,Uri>();
        HashMap<String,String> localShas = new HashMap<String,String>();
        collectLocalFiles(c, root, root, "", localUris, localShas);
        SyncPreview x = new SyncPreview();
        for (Map.Entry<String,Uri> e : localUris.entrySet()) {
            String path=e.getKey(); String rs=remote.get(path);
            if (rs==null) { x.upload++; x.uploads.add(path); }
            else if (getFileSize(c,e.getValue()) >= 50L*1024L*1024L) {
                LfsPointer lp = remoteLfsPointer(p, id, rs);
                if (lp != null && lp.size == getFileSize(c, e.getValue()) && lp.oid.equalsIgnoreCase(sha256(c, e.getValue(), path))) x.unchanged++;
                else { x.conflict++; x.conflicts.add(path + (lp == null ? " (remote is not Git LFS)" : " (Git LFS content differs)")); }
            }
            else if (rs.equals(localShas.get(path))) x.unchanged++;
            else { x.conflict++; x.conflicts.add(path); }
        }
        for (String path : remote.keySet()) if (!localUris.containsKey(path)) { x.download++; x.downloads.add(path); }
        return x;
    }

    private static int[] safeTwoWaySync(Context c, RepoProfile p, RepoId id, Uri root) throws Exception {
        HashMap<String,String> remote = remoteShas(p,id);
        HashMap<String,Uri> localUris=new HashMap<String,Uri>(); HashMap<String,String> localShas=new HashMap<String,String>();
        collectLocalFiles(c,root,root,"",localUris,localShas); int down=0,up=0;
        for(String path:remote.keySet()) if(!localUris.containsKey(path)) { downloadPath(c,p,id,root,path,remote.get(path)); down++; }
        for(Map.Entry<String,Uri> e:localUris.entrySet()) { checkCancelled(); String path=e.getKey(); String rs=remote.get(path);
            if(rs==null) { uploadFileWithRetry(c,p,id,e.getValue(),path); up++; }
            else if(getFileSize(c,e.getValue()) >= 50L*1024L*1024L) {
                LfsPointer lp = remoteLfsPointer(p, id, rs);
                boolean same = lp != null && lp.size == getFileSize(c, e.getValue()) && lp.oid.equalsIgnoreCase(sha256(c, e.getValue(), path));
                if(!same && callbackHolder.get()!=null) callbackHolder.get().progress("CONFLICT skipped: " + path + " (use Upload or Download to choose a side)");
            } else if(!rs.equals(localShas.get(path))) {
                if(callbackHolder.get()!=null) callbackHolder.get().progress("CONFLICT skipped: " + path + " (use Upload or Download to choose a side)");
            }
        }
        return new int[]{down,up};
    }

    private static HashMap<String,String> remoteShas(RepoProfile p, RepoId id) throws Exception {
        String treeUrl=GitServerConfig.treeUrl(id.owner,id.repo,safeBranch(p.branch));
        JSONArray a=new JSONObject(request("GET",treeUrl,p.token,null,null)).getJSONArray("tree"); HashMap<String,String> m=new HashMap<String,String>();
        for(int i=0;i<a.length();i++){ JSONObject o=a.getJSONObject(i); if("blob".equals(o.optString("type"))) m.put(o.optString("path"),o.optString("sha")); } return m;
    }

    private static void collectLocalFiles(Context c, Uri root, Uri dir, String prefix, HashMap<String,Uri> uris, HashMap<String,String> shas) throws Exception {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(dir,DocumentsContract.getDocumentId(dir)); Cursor cur=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null); if(cur==null)return;
        try{while(cur.moveToNext()){String id=cur.getString(0),name=cur.getString(1),mime=cur.getString(2); if(".git".equals(name)||".gitsync".equals(name))continue; Uri child=DocumentsContract.buildDocumentUriUsingTree(root,id); String rel=prefix.length()==0?name:prefix+"/"+name; if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) collectLocalFiles(c,root,child,rel,uris,shas); else {uris.put(rel,child); if(getFileSize(c,child)<50L*1024L*1024L) shas.put(rel,gitBlobSha(c,child));}}}finally{cur.close();}
    }

    private static String gitBlobSha(Context c, Uri file) throws Exception { long size=getFileSize(c,file); MessageDigest md=MessageDigest.getInstance("SHA-1"); md.update(("blob "+size+"\0").getBytes("UTF-8")); InputStream in=c.getContentResolver().openInputStream(file); byte[] b=new byte[32768]; int n; while((n=in.read(b))>0)md.update(b,0,n); in.close(); byte[] d=md.digest(); StringBuilder x=new StringBuilder(); for(byte q:d)x.append(String.format("%02x",q&255)); return x.toString(); }

    private static class LfsPointer {
        String oid; long size;
        LfsPointer(String oid, long size) { this.oid = oid; this.size = size; }
    }

    private static LfsPointer remoteLfsPointer(RepoProfile p, RepoId id, String blobSha) throws Exception {
        checkCancelled();
        JSONObject blob = new JSONObject(request("GET", GitServerConfig.blobUrl(id.owner, id.repo, blobSha), p.token, null, null));
        byte[] data = Base64.decode(blob.optString("content").replace("\n", "").replace("\r", ""), Base64.DEFAULT);
        if (data.length < 40 || data.length > 4096) return null;
        String pointer = new String(data, "UTF-8");
        if (!pointer.startsWith("version " + GitServerConfig.LFS_SPEC_URL)) return null;
        String oid = null; long size = -1L;
        String[] lines = pointer.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("oid sha256:")) oid = line.substring(11).trim();
            else if (line.startsWith("size ")) try { size = Long.parseLong(line.substring(5).trim()); } catch (Exception ignored) { }
        }
        if (oid == null || oid.length() != 64 || size < 0) return null;
        return new LfsPointer(oid, size);
    }

    private static void downloadPath(Context c, RepoProfile p, RepoId id, Uri root, String path, String sha) throws Exception {
        if (callbackHolder.get() != null) callbackHolder.get().progress("Downloading: " + path);
        JSONObject blob = new JSONObject(request("GET", GitServerConfig.blobUrl(id.owner, id.repo, sha), p.token, null, null));
        byte[] data = Base64.decode(blob.optString("content").replace("\n", "").replace("\r", ""), Base64.DEFAULT);
        Uri out = ensureFile(c, root, path);
        if (!downloadLfsPointerIfNeeded(c, p, id, path, data, out)) writeBytes(c, out, path, data);
    }

    private static void writeBytes(Context c, Uri out, String path, byte[] data) throws Exception {
        OutputStream os = c.getContentResolver().openOutputStream(out, "wt");
        if (os == null) throw new Exception("Cannot write " + path);
        try { os.write(data); } finally { try { os.close(); } catch (Exception ignored) { } }
    }

    private static boolean downloadLfsPointerIfNeeded(Context c, RepoProfile p, RepoId id, String path, byte[] data, Uri out) throws Exception {
        if (data == null || data.length < 40 || data.length > 4096) return false;
        String pointer;
        try { pointer = new String(data, "UTF-8"); } catch (Exception e) { return false; }
        if (!pointer.startsWith("version " + GitServerConfig.LFS_SPEC_URL)) return false;
        String oid = null; long size = -1L; String[] lines = pointer.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("oid sha256:")) oid = line.substring(11).trim();
            else if (line.startsWith("size ")) try { size = Long.parseLong(line.substring(5).trim()); } catch (Exception ignored) { }
        }
        if (oid == null || oid.length() != 64 || size < 0) return false;
        Callback cb = callbackHolder.get(); if (cb != null) cb.progress("Git LFS download: " + path + " (" + (size / (1024L * 1024L)) + " MiB)");
        JSONObject obj = new JSONObject(); obj.put("oid", oid); obj.put("size", size);
        JSONArray objects = new JSONArray(); objects.put(obj);
        JSONObject batch = new JSONObject(); batch.put("operation", "download"); JSONArray transfers = new JSONArray(); transfers.put("basic"); batch.put("transfers", transfers); batch.put("objects", objects);
        String lfsBase = GitServerConfig.lfsBaseUrl(id.owner, id.repo);
        JSONObject response = new JSONObject(lfsRequest("POST", lfsBase + "/objects/batch", p.token, "application/vnd.git-lfs+json", batch.toString()));
        JSONObject result = response.getJSONArray("objects").getJSONObject(0);
        if (result.has("error")) throw new Exception("Git LFS: " + result.getJSONObject("error").optString("message", "download rejected"));
        JSONObject actions = result.optJSONObject("actions");
        if (actions == null || !actions.has("download")) throw new Exception("Git LFS download action missing for " + path);
        JSONObject action = actions.getJSONObject("download");
        streamLfsDownload(c, out, path, size, action.getString("href"), action.optJSONObject("header"));
        return true;
    }

    private static void streamLfsDownload(Context c, Uri outUri, String path, long size, String href, JSONObject headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(href).openConnection(); conn.setConnectTimeout(20000); conn.setReadTimeout(300000); conn.setRequestMethod("GET");
        if (headers != null) { java.util.Iterator<String> it = headers.keys(); while (it.hasNext()) { String k = it.next(); conn.setRequestProperty(k, headers.optString(k)); } }
        int code = conn.getResponseCode(); if (code < 200 || code >= 300) { String text = readText(conn.getErrorStream()); conn.disconnect(); throw new Exception("Git LFS download HTTP " + code + ": " + text); }
        InputStream in = conn.getInputStream(); OutputStream out = c.getContentResolver().openOutputStream(outUri, "wt"); if (out == null) { try { in.close(); } catch (Exception ignored) { } conn.disconnect(); throw new Exception("Cannot write " + path); }
        byte[] b = new byte[64 * 1024]; int n; long got = 0; int last = -1;
        try { while ((n = in.read(b)) >= 0) { checkCancelled(); if (n == 0) continue; out.write(b, 0, n); got += n; if (size > 0) { int pct = (int)(got * 100L / size); if (pct >= last + 10) { last = pct; Callback cb = callbackHolder.get(); if (cb != null) cb.progress("Downloading " + path + ": " + pct + "%"); } } } out.flush(); }
        finally { try { in.close(); } catch (Exception ignored) { } try { out.close(); } catch (Exception ignored) { } conn.disconnect(); }
        if (size >= 0 && got != size) throw new Exception("Git LFS download size mismatch for " + path + ": expected " + size + ", received " + got);
    }

    private static int downloadRepository(Context c, RepoProfile p, RepoId id, Uri root) throws Exception {
        String branch = safeBranch(p.branch); String treeUrl = GitServerConfig.treeUrl(id.owner, id.repo, branch);
        JSONArray items = new JSONObject(request("GET", treeUrl, p.token, null, null)).getJSONArray("tree"); int count = 0; int i; if (callbackHolder.get() != null) callbackHolder.get().progress("" + GitServerConfig.SERVER_NAME + " tree loaded: " + items.length() + " entries");
        for (i = 0; i < items.length(); i++) { checkCancelled(); JSONObject item = items.getJSONObject(i); if (!"blob".equals(item.optString("type"))) continue; String path = item.optString("path"); if (path.length() == 0 || path.startsWith(".git/")) continue; downloadPath(c, p, id, root, path, item.optString("sha")); count++; }
        return count;
    }

    private static int uploadFolder(Context c, RepoProfile p, RepoId id, Uri root, Uri dir, String prefix) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir)); Cursor cur = c.getContentResolver().query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null); if (cur == null) return 0; int count = 0;
        try { while (cur.moveToNext()) { checkCancelled(); String docId = cur.getString(0); String name = cur.getString(1); String mime = cur.getString(2); if (".git".equals(name) || ".gitsync".equals(name)) continue; Uri child = DocumentsContract.buildDocumentUriUsingTree(root, docId); String rel = prefix.length() == 0 ? name : prefix + "/" + name; if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) count += uploadFolder(c, p, id, root, child, rel); else { if (callbackHolder.get() != null) callbackHolder.get().progress("Uploading: " + rel); uploadFileWithRetry(c, p, id, child, rel); count++; } } } finally { cur.close(); } return count;
    }

    private static void uploadFileWithRetry(Context c, RepoProfile p, RepoId id, Uri file, String rel) throws Exception {
        Exception last=null; for(int a=1;a<=3;a++){ try{ uploadFile(c,p,id,file,rel); return; } catch(Exception e){ last=e; if(a<3 && callbackHolder.get()!=null) callbackHolder.get().progress("File failed: " + rel + " - retry " + (a+1) + "/3"); if(a<3) try{Thread.sleep(1000L*a);}catch(InterruptedException z){} } } throw last;
    }

    private static void uploadFile(Context c, RepoProfile p, RepoId id, Uri file, String rel) throws Exception {
        String api = GitServerConfig.contentsUrl(id.owner, id.repo, rel);
        String sha = null;
        try { sha = new JSONObject(request("GET", api + "?ref=" + enc(safeBranch(p.branch)), p.token, null, null)).optString("sha", null); }
        catch (HttpError e) { if (e.code != 404) throw e; }
        long size = getFileSize(c, file);
        // GitHub's JSON Git-blob endpoint rejects very large request bodies. For files >= 50 MiB,
        // store the binary through Git LFS and commit only the small LFS pointer through Contents API.
        if (size >= 50L * 1024L * 1024L) {
            uploadLfsFile(c, p, id, file, rel, size, api, sha);
            return;
        }
        InputStream in = c.getContentResolver().openInputStream(file); if (in == null) throw new Exception("Cannot read " + rel);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n); in.close();
        JSONObject body = new JSONObject(); body.put("message", "Sync " + rel + " from Android");
        body.put("content", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)); body.put("branch", safeBranch(p.branch));
        if (sha != null && sha.length() > 0) body.put("sha", sha);
        request("PUT", api, p.token, "application/json; charset=UTF-8", body.toString());
    }

    private static long getFileSize(Context c, Uri file) {
        AssetFileDescriptor afd = null;
        try { afd = c.getContentResolver().openAssetFileDescriptor(file, "r"); if (afd != null) return afd.getLength(); }
        catch (Exception ignored) { } finally { try { if (afd != null) afd.close(); } catch (Exception ignored) { } }
        return -1L;
    }

    private static String sha256(Context c, Uri file, String rel) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = c.getContentResolver().openInputStream(file); if (in == null) throw new Exception("Cannot read " + rel);
        byte[] b = new byte[64 * 1024]; int n;
        try { while ((n = in.read(b)) >= 0) { checkCancelled(); if (n > 0) md.update(b, 0, n); } } finally { in.close(); }
        byte[] d = md.digest(); StringBuilder x = new StringBuilder();
        for (int i = 0; i < d.length; i++) { String h = Integer.toHexString(d[i] & 255); if (h.length() < 2) x.append('0'); x.append(h); }
        return x.toString();
    }

    private static void uploadLfsFile(Context c, RepoProfile p, RepoId id, Uri file, String rel, long size, String contentsApi, String existingSha) throws Exception {
        Callback cb = callbackHolder.get();
        if (cb != null) cb.progress("Large file: " + rel + " (" + (size / (1024L * 1024L)) + " MiB) - using Git LFS");
        if (cb != null) cb.progress("Preparing large file (SHA-256)...");
        String oid = sha256(c, file, rel);
        String lfsBase = GitServerConfig.lfsBaseUrl(id.owner, id.repo);
        JSONObject obj = new JSONObject(); obj.put("oid", oid); obj.put("size", size);
        JSONArray objs = new JSONArray(); objs.put(obj);
        JSONObject batch = new JSONObject(); batch.put("operation", "upload"); JSONArray transfers = new JSONArray(); transfers.put("basic"); batch.put("transfers", transfers); batch.put("objects", objs);
        JSONObject response = new JSONObject(lfsRequest("POST", lfsBase + "/objects/batch", p.token, "application/vnd.git-lfs+json", batch.toString()));
        JSONObject result = response.getJSONArray("objects").getJSONObject(0);
        if (result.has("error")) throw new Exception("Git LFS: " + result.getJSONObject("error").optString("message", "upload rejected"));
        JSONObject actions = result.optJSONObject("actions");
        if (actions != null && actions.has("upload")) {
            JSONObject action = actions.getJSONObject("upload");
            streamLfsUpload(c, file, rel, size, action.getString("href"), action.optJSONObject("header"));
            if (actions.has("verify")) {
                JSONObject verify = actions.getJSONObject("verify");
                JSONObject verifyBody = new JSONObject(); verifyBody.put("oid", oid); verifyBody.put("size", size);
                lfsActionRequest("POST", verify.getString("href"), verify.optJSONObject("header"), "application/vnd.git-lfs+json", verifyBody.toString(), p.token);
            }
        } else if (cb != null) cb.progress("Git LFS object already exists; committing pointer...");
        String pointer = "version " + GitServerConfig.LFS_SPEC_URL + "\n" + "oid sha256:" + oid + "\n" + "size " + size + "\n";
        JSONObject body = new JSONObject(); body.put("message", "Sync " + rel + " from Android (Git LFS)");
        body.put("content", Base64.encodeToString(pointer.getBytes("UTF-8"), Base64.NO_WRAP)); body.put("branch", safeBranch(p.branch));
        if (existingSha != null && existingSha.length() > 0) body.put("sha", existingSha);
        request("PUT", contentsApi, p.token, "application/json; charset=UTF-8", body.toString());
        if (cb != null) cb.progress("Large file completed: " + rel);
    }

    private static String lfsRequest(String method, String url, String token, String contentType, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(url).openConnection(); conn.setConnectTimeout(20000); conn.setReadTimeout(120000);
        conn.setRequestMethod(method); conn.setRequestProperty("Accept", "application/vnd.git-lfs+json"); conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("User-Agent", "myGitSync"); if (token != null && token.length() > 0) conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        if (body != null) { conn.setDoOutput(true); byte[] d = body.getBytes("UTF-8"); conn.setFixedLengthStreamingMode(d.length); OutputStream os = conn.getOutputStream(); os.write(d); os.close(); }
        int code = conn.getResponseCode(); InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(); String text = readText(in); conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Git LFS HTTP " + code + ": " + text); return text;
    }

    private static void streamLfsUpload(Context c, Uri file, String rel, long size, String href, JSONObject headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(href).openConnection(); conn.setConnectTimeout(20000); conn.setReadTimeout(300000); conn.setRequestMethod("PUT"); conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream"); if (headers != null) { java.util.Iterator<String> it = headers.keys(); while (it.hasNext()) { String k = it.next(); conn.setRequestProperty(k, headers.optString(k)); } }
        if (size >= 0 && size <= Integer.MAX_VALUE) conn.setFixedLengthStreamingMode((int)size); else conn.setChunkedStreamingMode(64 * 1024);
        OutputStream out = conn.getOutputStream(); InputStream in = c.getContentResolver().openInputStream(file); if (in == null) throw new Exception("Cannot read " + rel);
        byte[] b = new byte[64 * 1024]; int n; long sent = 0; int last = -1;
        try { while ((n = in.read(b)) >= 0) { checkCancelled(); if (n == 0) continue; out.write(b, 0, n); sent += n; if (size > 0) { int pct = (int)(sent * 90L / size); if (pct >= last + 10) { last = pct; Callback cb = callbackHolder.get(); if (cb != null) cb.progress("Uploading " + rel + ": " + pct + "%"); } } } out.flush(); }
        finally { try { in.close(); } catch (Exception ignored) {} try { out.close(); } catch (Exception ignored) {} }
        Callback cb = callbackHolder.get(); if (cb != null) cb.progress("Finalizing on " + GitServerConfig.SERVER_NAME + "...");
        int code = conn.getResponseCode(); String text = readText(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()); conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Git LFS upload HTTP " + code + ": " + text);
    }

    private static String lfsActionRequest(String method, String href, JSONObject headers, String contentType, String body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(href).openConnection(); conn.setConnectTimeout(20000); conn.setReadTimeout(120000); conn.setRequestMethod(method);
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType); if (headers != null) { java.util.Iterator<String> it = headers.keys(); while (it.hasNext()) { String k = it.next(); conn.setRequestProperty(k, headers.optString(k)); } }
        if (body != null) { conn.setDoOutput(true); byte[] d = body.getBytes("UTF-8"); conn.setFixedLengthStreamingMode(d.length); OutputStream os = conn.getOutputStream(); os.write(d); os.close(); }
        int code = conn.getResponseCode(); String text = readText(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()); conn.disconnect(); if (code < 200 || code >= 300) throw new Exception("Git LFS verify HTTP " + code + ": " + text); return text;
    }

    private static int[] mirrorFolder(Context c, RepoProfile p, RepoId id, Uri root) throws Exception {
        HashSet<String> local = new HashSet<String>();
        collectLocalPaths(c, root, root, "", local);
        if (callbackHolder.get() != null) callbackHolder.get().progress("Local scan complete: " + local.size() + " file(s)");
        int uploaded = uploadFolder(c, p, id, root, root, "");
        String branch = safeBranch(p.branch);
        String treeUrl = GitServerConfig.treeUrl(id.owner, id.repo, branch);
        JSONArray items = new JSONObject(request("GET", treeUrl, p.token, null, null)).getJSONArray("tree");
        int deleted = 0; int i;
        for (i = 0; i < items.length(); i++) { checkCancelled();
            JSONObject item = items.getJSONObject(i);
            if (!"blob".equals(item.optString("type"))) continue;
            String path = item.optString("path");
            if (path.length() == 0 || path.startsWith(".git/") || local.contains(path)) continue;
            if (callbackHolder.get() != null) callbackHolder.get().progress("Deleting from " + GitServerConfig.SERVER_NAME + ": " + path);
            deleteRemoteFile(p, id, path, item.optString("sha"));
            deleted++;
        }
        return new int[] { uploaded, deleted };
    }

    private static int[] mirrorRemoteFolder(Context c, RepoProfile p, RepoId id, Uri root) throws Exception {
        String branch = safeBranch(p.branch);
        String treeUrl = GitServerConfig.treeUrl(id.owner, id.repo, branch);
        JSONArray items = new JSONObject(request("GET", treeUrl, p.token, null, null)).getJSONArray("tree");
        HashSet<String> remote = new HashSet<String>();
        int downloaded = 0; int i;
        if (callbackHolder.get() != null) callbackHolder.get().progress("" + GitServerConfig.SERVER_NAME + " tree loaded: " + items.length() + " entries");
        for (i = 0; i < items.length(); i++) { checkCancelled();
            JSONObject item = items.getJSONObject(i);
            if (!"blob".equals(item.optString("type"))) continue;
            String path = item.optString("path");
            if (path.length() == 0 || path.startsWith(".git/")) continue;
            remote.add(path);
            downloadPath(c, p, id, root, path, item.optString("sha")); downloaded++;
        }
        if (callbackHolder.get() != null) callbackHolder.get().progress("Removing local files that are not on " + GitServerConfig.SERVER_NAME + "...");
        int deleted = pruneLocal(c, root, root, "", remote);
        return new int[] { downloaded, deleted };
    }

    private static int pruneLocal(Context c, Uri root, Uri dir, String prefix, HashSet<String> remote) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir));
        Cursor cur = c.getContentResolver().query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
        if (cur == null) return 0;
        java.util.ArrayList<String> ids = new java.util.ArrayList<String>(); java.util.ArrayList<String> names = new java.util.ArrayList<String>(); java.util.ArrayList<String> mimes = new java.util.ArrayList<String>();
        try { while (cur.moveToNext()) { checkCancelled(); ids.add(cur.getString(0)); names.add(cur.getString(1)); mimes.add(cur.getString(2)); } } finally { cur.close(); }
        int deleted = 0; int i;
        for (i = 0; i < ids.size(); i++) { checkCancelled();
            String name = names.get(i); if (".git".equals(name) || ".gitsync".equals(name)) continue;
            Uri child = DocumentsContract.buildDocumentUriUsingTree(root, ids.get(i)); String rel = prefix.length() == 0 ? name : prefix + "/" + name;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimes.get(i))) { deleted += pruneLocal(c, root, child, rel, remote); if (!hasRemotePrefix(remote, rel + "/")) { if (callbackHolder.get() != null) callbackHolder.get().progress("Deleting local folder: " + rel); try { DocumentsContract.deleteDocument(c.getContentResolver(), child); } catch (Exception ignored) { } } }
            else if (!remote.contains(rel)) { if (callbackHolder.get() != null) callbackHolder.get().progress("Deleting local: " + rel); if (DocumentsContract.deleteDocument(c.getContentResolver(), child)) deleted++; }
        }
        return deleted;
    }

    private static boolean hasRemotePrefix(HashSet<String> remote, String prefix) { java.util.Iterator<String> it = remote.iterator(); while (it.hasNext()) { if (it.next().startsWith(prefix)) return true; } return false; }

    private static void collectLocalPaths(Context c, Uri root, Uri dir, String prefix, HashSet<String> paths) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir));
        Cursor cur = c.getContentResolver().query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
        if (cur == null) return;
        try { while (cur.moveToNext()) { checkCancelled(); String docId = cur.getString(0); String name = cur.getString(1); String mime = cur.getString(2); if (".git".equals(name) || ".gitsync".equals(name)) continue; Uri child = DocumentsContract.buildDocumentUriUsingTree(root, docId); String rel = prefix.length() == 0 ? name : prefix + "/" + name; if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) collectLocalPaths(c, root, child, rel, paths); else paths.add(rel); } } finally { cur.close(); }
    }

    private static void deleteRemoteFile(RepoProfile p, RepoId id, String rel, String sha) throws Exception {
        String api = GitServerConfig.contentsUrl(id.owner, id.repo, rel);
        JSONObject body = new JSONObject(); body.put("message", "Mirror delete " + rel + " from Android"); body.put("sha", sha); body.put("branch", safeBranch(p.branch));
        request("DELETE", api, p.token, "application/json; charset=UTF-8", body.toString());
    }

    private static Uri ensureFile(Context c, Uri root, String path) throws Exception { String[] parts = path.split("/"); Uri dir = root; int i; for (i = 0; i < parts.length - 1; i++) dir = ensureDirectory(c, root, dir, parts[i]); Uri found = findChild(c, root, dir, parts[parts.length - 1], false); if (found != null) return found; Uri made = DocumentsContract.createDocument(c.getContentResolver(), dir, "application/octet-stream", parts[parts.length - 1]); if (made == null) throw new Exception("Cannot create " + path); return made; }
    private static Uri ensureDirectory(Context c, Uri root, Uri parent, String name) throws Exception { Uri found = findChild(c, root, parent, name, true); if (found != null) return found; Uri made = DocumentsContract.createDocument(c.getContentResolver(), parent, DocumentsContract.Document.MIME_TYPE_DIR, name); if (made == null) throw new Exception("Cannot create folder " + name); return made; }
    private static Uri findChild(Context c, Uri root, Uri parent, String name, boolean directory) throws Exception { Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent)); Cursor cur = c.getContentResolver().query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null); if (cur == null) return null; try { while (cur.moveToNext()) { checkCancelled(); if (name.equals(cur.getString(1))) { boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2)); if (isDir == directory) return DocumentsContract.buildDocumentUriUsingTree(root, cur.getString(0)); } } } finally { cur.close(); } return null; }

    private static Uri treeDocumentUri(Uri tree) throws Exception { if (tree == null || !"content".equals(tree.getScheme())) throw new Exception("Invalid local folder URI. Select the local folder again."); String treeId; try { treeId = DocumentsContract.getTreeDocumentId(tree); } catch (Exception e) { throw new Exception("Invalid local folder URI. Select the local folder again."); } return DocumentsContract.buildDocumentUriUsingTree(tree, treeId); }

    private static String request(String method, String urlText, String token, String contentType, String body) throws Exception {
        int attempt;
        for (attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                checkCancelled();
                if (callbackHolder.get() != null && attempt > 1) callbackHolder.get().progress("Retry " + attempt + "/" + MAX_RETRIES + "...");
                return requestOnce(method, urlText, token, contentType, body);
            } catch (HttpError e) {
                if (!isRetryableHttp(e.code) || attempt >= MAX_RETRIES) throw e;
                retryWait(attempt, GitServerConfig.SERVER_NAME + " HTTP " + e.code);
            } catch (UnknownHostException e) {
                if (attempt >= MAX_RETRIES) throw new Exception("Cannot resolve " + GitServerConfig.API_BASE_URL + " after " + MAX_RETRIES + " attempts. Check Wi-Fi/mobile data, Private DNS/VPN.");
                retryWait(attempt, "DNS lookup failed");
            } catch (SocketTimeoutException e) {
                if (attempt >= MAX_RETRIES) throw new Exception("Network timeout after " + MAX_RETRIES + " attempts.");
                retryWait(attempt, "Network timeout");
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) throw e;
                retryWait(attempt, "Network connection error");
            }
        }
        throw new Exception("Network request failed after retries.");
    }
    private static boolean isRetryableHttp(int code) { return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504; }
    private static void retryWait(int attempt, String reason) throws Exception { checkCancelled(); long wait = 1000L << (attempt - 1); if (wait > 12000L) wait = 12000L; if (callbackHolder.get() != null) callbackHolder.get().progress(reason + " - retry " + (attempt + 1) + "/" + MAX_RETRIES + " in " + (wait / 1000L) + "s"); try { Thread.sleep(wait); } catch (InterruptedException ignored) { } }
    private static String requestOnce(String method, String urlText, String token, String contentType, String body) throws Exception { HttpURLConnection c = (HttpURLConnection)new URL(urlText).openConnection(); c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setRequestMethod(method); c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28"); c.setRequestProperty("User-Agent", "AIDE-GitSync"); if (token != null && token.trim().length() > 0) c.setRequestProperty("Authorization", "Bearer " + token.trim()); if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", contentType == null ? "application/json; charset=UTF-8" : contentType); byte[] data = body.getBytes("UTF-8"); c.setFixedLengthStreamingMode(data.length); OutputStream os = c.getOutputStream(); os.write(data); os.close(); } int code = c.getResponseCode(); InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(); String text = readText(in); c.disconnect(); if (code < 200 || code >= 300) { String message = text; try { message = new JSONObject(text).optString("message", text); } catch (Exception ignored) { } throw new HttpError(code, GitServerConfig.SERVER_NAME + " HTTP " + code + ": " + message); } return text; }
    private static String readText(InputStream in) throws Exception { if (in == null) return ""; BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8")); StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line).append('\n'); br.close(); return sb.toString(); }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
    private static String encodePath(String path) throws Exception { String[] parts = path.split("/"); StringBuilder b = new StringBuilder(); int i; for (i = 0; i < parts.length; i++) { if (i > 0) b.append('/'); b.append(enc(parts[i])); } return b.toString(); }
    private static String safeBranch(String b) { return b == null || b.trim().length() == 0 ? "main" : b.trim(); }
    private static RepoId parseRepo(String u) { String[] a = GitServerConfig.parseRepositoryUrl(u); return a == null ? null : new RepoId(a[0], a[1]); }
    private static class RepoId { String owner; String repo; RepoId(String owner, String repo) { this.owner = owner; this.repo = repo; } }
    private static class HttpError extends Exception { int code; HttpError(int code, String message) { super(message); this.code = code; } }
}
