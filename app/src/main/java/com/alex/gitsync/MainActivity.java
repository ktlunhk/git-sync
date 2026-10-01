package com.alex.gitsync;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.net.Uri;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.content.DialogInterface;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

public class MainActivity extends Activity {
    private ArrayList<RepoProfile> repos;
    private ArrayAdapter<String> adapter;
    private ArrayList<String> labels;
    private TextView status;
    private ScrollView logScroll;
    private EditText pendingFolderTarget;
    private static final int REQUEST_FOLDER = 4101;
    private HashMap<String,String> repoStatus = new HashMap<String,String>();
    private AlertDialog operationDialog;
    private TextView operationDialogMessage;

    public void onCreate(Bundle b) {
        super.onCreate(b); setContentView(R.layout.activity_main); applySystemBarInsets();
        status = (TextView)findViewById(R.id.statusText);
        logScroll = (ScrollView)findViewById(R.id.logScroll);
        repos = RepoStore.load(this); refresh(); updateAuthUi();
        setButtonIconText((Button)findViewById(R.id.settingsButton), R.drawable.ic_action_settings, "OAUTH SETTINGS");
        setButtonIconText((Button)findViewById(R.id.addButton), R.drawable.ic_action_add, "ADD REPOSITORY");
        setButtonIconText((Button)findViewById(R.id.syncAllButton), R.drawable.ic_action_sync, "SYNC ALL");
        setButtonIconText((Button)findViewById(R.id.clearLogButton), R.drawable.ic_action_clear, "CLEAR");
        ((Button)findViewById(R.id.settingsButton)).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showOAuthSettings(); } });
        ((Button)findViewById(R.id.loginButton)).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { loginOrLogout(); } });
        ((Button)findViewById(R.id.addButton)).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showEditor(-1); } });
        ((Button)findViewById(R.id.syncAllButton)).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { syncAll(); } });
        ((Button)findViewById(R.id.clearLogButton)).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { status.setText(""); } });
        ListView lv = (ListView)findViewById(R.id.repoList);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() { public void onItemClick(AdapterView<?> p, View v, int pos, long id) { showActions(pos); } });
    }

    private static class CenteredImageSpan extends ImageSpan {
        CenteredImageSpan(Drawable drawable) { super(drawable); }

        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            Drawable d = getDrawable();
            android.graphics.Rect r = d.getBounds();
            if (fm != null) {
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                int fontHeight = pfm.descent - pfm.ascent;
                int centerY = pfm.ascent + fontHeight / 2;
                fm.ascent = centerY - r.height() / 2;
                fm.descent = centerY + r.height() / 2;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            }
            return r.right;
        }

        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            Drawable d = getDrawable();
            canvas.save();
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int transY = y + (fm.ascent + fm.descent) / 2 - d.getBounds().height() / 2;
            canvas.translate(x, transY);
            d.draw(canvas);
            canvas.restore();
        }
    }

    private void setButtonIconText(Button button, int iconRes, String label) {
        Drawable icon = getResources().getDrawable(iconRes);
        int size = dp(20);
        icon.setBounds(0, 0, size, size);
        String value = "\uFFFC  " + label;
        SpannableString text = new SpannableString(value);
        text.setSpan(new CenteredImageSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setCompoundDrawables(null, null, null, null);
        button.setCompoundDrawablePadding(0);
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.CENTER);
        button.setText(text);
    }

    private void applySystemBarInsets() {
        final View root = findViewById(R.id.rootLayout);
        if (root == null) return;
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        if (android.os.Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    v.setPadding(left + insets.getSystemWindowInsetLeft(), top + insets.getSystemWindowInsetTop(), right + insets.getSystemWindowInsetRight(), bottom + insets.getSystemWindowInsetBottom());
                    return insets;
                }
            });
            root.requestApplyInsets();
        }
    }

    private void updateAuthUi() {
        TextView authStatus = (TextView)findViewById(R.id.authStatus);
        Button loginButton = (Button)findViewById(R.id.loginButton);
        if (GitHubAuth.signedIn(this)) {
            authStatus.setText(GitServerConfig.SERVER_NAME + ": Signed in");
            setButtonIconText(loginButton, R.drawable.ic_action_login, "SIGN OUT FROM " + GitServerConfig.SERVER_NAME.toUpperCase());
        } else {
            authStatus.setText(GitServerConfig.SERVER_NAME + ": Not signed in");
            setButtonIconText(loginButton, R.drawable.ic_action_login, "SIGN IN WITH " + GitServerConfig.SERVER_NAME.toUpperCase());
        }
    }

    private void refresh() {
        // Preserve the repository list viewport across status updates and actions.
        // Replacing the adapter resets ListView to the top unless we remember and
        // restore both the first visible row and its pixel offset.
        ListView existingList = (ListView)findViewById(R.id.repoList);
        int savedFirst = 0;
        int savedTop = 0;
        if (existingList != null && existingList.getAdapter() != null && existingList.getChildCount() > 0) {
            savedFirst = existingList.getFirstVisiblePosition();
            View firstChild = existingList.getChildAt(0);
            if (firstChild != null) savedTop = firstChild.getTop() - existingList.getPaddingTop();
        }

        labels = new ArrayList<String>(); int i;
        for (i = 0; i < repos.size(); i++) { RepoProfile r = repos.get(i); String st=repoStatus.get(r.name); labels.add(st==null ? r.name+"\nReady" : r.name+"\n"+st); }
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(8), dp(12), dp(8));

                RepoProfile repo = repos.get(position);
                String st = repoStatus.get(repo.name);
                if (st == null || st.length() == 0) st = "Ready";

                TextView nameView = new TextView(MainActivity.this);
                nameView.setText(repo.name);
                nameView.setTextSize(16);
                nameView.setTextColor(0xFF202124);
                nameView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

                TextView statusView = new TextView(MainActivity.this);
                statusView.setText(st);
                statusView.setTextSize(11);
                statusView.setTextColor(0xFF6B7280);
                statusView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                statusView.setPadding(0, dp(2), 0, 0);

                row.addView(nameView, new LinearLayout.LayoutParams(-1, -2));
                row.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
                return row;
            }
        };
        ListView list = (ListView)findViewById(R.id.repoList);
        list.setAdapter(adapter);
        if (list != null && repos.size() > 0) {
            int restorePos = savedFirst;
            if (restorePos >= repos.size()) restorePos = repos.size() - 1;
            if (restorePos < 0) restorePos = 0;
            list.setSelectionFromTop(restorePos, savedTop);
        }
        // The repository list now fills only the flexible repository card area.
        // Do not force a row-count height here: doing so could push the fixed log
        // card below the usable window on smaller screens.
    }


    // Update only the visible status TextView. Do not rebuild/reset the ListView.
    // repoStatus remains the source of truth, so an off-screen row receives the
    // latest status automatically when ListView later asks the adapter for it.
    private void updateRepoStatus(final RepoProfile repo, final String newStatus) {
        repoStatus.put(repo.name, newStatus);
        runOnUiThread(new Runnable() { public void run() {
            ListView list = (ListView)findViewById(R.id.repoList);
            if (list == null) return;
            int repoIndex = repos.indexOf(repo);
            if (repoIndex < 0) {
                for (int i = 0; i < repos.size(); i++) {
                    if (repos.get(i).name.equals(repo.name)) { repoIndex = i; break; }
                }
            }
            int first = list.getFirstVisiblePosition();
            int childIndex = repoIndex - first;
            if (childIndex >= 0 && childIndex < list.getChildCount()) {
                View row = list.getChildAt(childIndex);
                if (row instanceof LinearLayout) {
                    LinearLayout box = (LinearLayout)row;
                    if (box.getChildCount() > 1 && box.getChildAt(1) instanceof TextView)
                        ((TextView)box.getChildAt(1)).setText(newStatus);
                }
            }
        }});
    }

    private EditText field(String hint) { EditText e = new EditText(this); e.setHint(hint); return e; }

    private void showEditor(final int index) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); int pad = dp(22); box.setPadding(pad,dp(6),pad,dp(10));
        final EditText name = field("Display name"); final EditText url = field(GitServerConfig.repositoryExampleUrl()); final EditText branch = field("Branch (main)");
        final EditText path = field("Local folder path"); path.setFocusable(false); path.setClickable(true);
        styleDialogField(name); styleDialogField(url); styleDialogField(branch); styleDialogField(path);
        final Button selectFolder = new Button(this); selectFolder.setText("SELECT LOCAL FOLDER"); selectFolder.setBackgroundResource(R.drawable.bg_button_light);
        box.addView(dialogLabel("DISPLAY NAME")); box.addView(name); box.addView(dialogLabel(GitServerConfig.SERVER_NAME.toUpperCase() + " REPOSITORY URL")); box.addView(url); box.addView(dialogLabel("BRANCH")); box.addView(branch); box.addView(dialogLabel("LOCAL FOLDER")); box.addView(path);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(52)); bp.setMargins(0,dp(14),0,0); box.addView(selectFolder,bp);
        if (index >= 0) { RepoProfile r = repos.get(index); name.setText(r.name); url.setText(r.url); branch.setText(r.branch); path.setText(r.localPath); } else { branch.setText("main"); }
        View.OnClickListener folderClick = new View.OnClickListener() { public void onClick(View v) { showFolderPicker(path); } }; path.setOnClickListener(folderClick); selectFolder.setOnClickListener(folderClick);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "Add repository" : "Edit repository").setView(box).setPositiveButton("SAVE", null).setNegativeButton("CANCEL", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface d) { polishDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                String n=name.getText().toString().trim(), u=url.getText().toString().trim(), b=branch.getText().toString().trim(), pa=path.getText().toString().trim();
                if(n.length()==0){ name.setError("Required"); name.requestFocus(); return; }
                String duplicateName = RepositoryValidator.duplicateName(repos,index,n);
                if(duplicateName!=null){ name.setError(duplicateName); name.requestFocus(); return; }
                if(u.length()==0){ url.setError("Required"); url.requestFocus(); return; }
                if(!GitServerConfig.isRepositoryUrl(u)){ url.setError("Enter a " + GitServerConfig.SERVER_NAME + " repository URL"); url.requestFocus(); return; }
                if(b.length()==0){ branch.setError("Required"); branch.requestFocus(); return; }
                if(pa.length()==0){ path.setError("Select a local folder"); return; }
                String duplicateRemote=RepositoryValidator.duplicateRemote(repos,index,u,b);
                if(duplicateRemote!=null){ url.setError(duplicateRemote); url.requestFocus(); return; }
                String duplicateLocal=RepositoryValidator.duplicateLocal(repos,index,pa);
                if(duplicateLocal!=null){ path.setError(duplicateLocal); return; }
                u=GitServerConfig.normalizeRepositoryUrl(u);
                RepoProfile r = new RepoProfile(n,u,b,pa,"",""); if(index<0) repos.add(r); else repos.set(index,r); RepoStore.save(MainActivity.this,repos); refresh(); dialog.dismiss();
            }});
        }}); dialog.show();
    }

    private void showFolderPicker(final EditText target) {
        pendingFolderTarget = target;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception e) { }
        if (pendingFolderTarget != null) pendingFolderTarget.setText(uri.toString());
        pendingFolderTarget = null;
        Toast.makeText(this, "Folder access granted", Toast.LENGTH_SHORT).show();
    }

    private void showActions(final int pos) {
        final RepoProfile r = repos.get(pos);
        if (syncAllRunning) { Toast.makeText(this,"Sync All is running. Stop or finish it before starting another repository action.",Toast.LENGTH_LONG).show(); return; } final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(r.name).create();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(4),dp(18),dp(14));
        String[] names={"Sync (download + upload)","Download from " + GitServerConfig.SERVER_NAME,"Upload to " + GitServerConfig.SERVER_NAME,"Mirror local to " + GitServerConfig.SERVER_NAME,"Mirror " + GitServerConfig.SERVER_NAME + " to local","Edit","Delete repository"};
        int[] icons={R.drawable.ic_action_sync_dark,R.drawable.ic_action_download,R.drawable.ic_action_upload,R.drawable.ic_action_mirror_up,R.drawable.ic_action_mirror_down,R.drawable.ic_action_edit,R.drawable.ic_action_delete};
        for(int i=0;i<names.length;i++){ final int which=i; Button bt=new Button(this); bt.setText(names[i]); bt.setTextSize(16); bt.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL); bt.setPadding(dp(18),0,dp(12),0); bt.setCompoundDrawablesWithIntrinsicBounds(icons[i],0,0,0); bt.setCompoundDrawablePadding(dp(12)); bt.setBackgroundResource(R.drawable.bg_button_light); if(i==6) bt.setTextColor(0xFFC62828); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52)); lp.setMargins(0,dp(5),0,0); box.addView(bt,lp); bt.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ dialog.dismiss(); if(which==0)syncOne(r); else if(which==1)downloadOne(r); else if(which==2)uploadOne(r); else if(which==3)confirmMirror(r); else if(which==4)confirmRemoteMirror(r); else if(which==5){ if(GitSyncService.isRepositoryActive(r)) Toast.makeText(MainActivity.this,"This repository is currently active and cannot be edited.",Toast.LENGTH_LONG).show(); else showEditor(pos); } else { if(GitSyncService.isRepositoryActive(r)) Toast.makeText(MainActivity.this,"This repository is currently active and cannot be deleted.",Toast.LENGTH_LONG).show(); else confirmDelete(pos); } }}); }
        dialog.setView(box); dialog.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface d){ polishDialog(dialog); }}); dialog.show();
    }

    private void confirmDelete(final int pos) {
        final RepoProfile r=repos.get(pos); final AlertDialog d=new AlertDialog.Builder(this).setTitle("Delete repository — "+r.name+"?").setMessage("WARNING: Remove \""+r.name+"\" from myGitSync?\n\nThis only removes the saved repository entry. It will NOT delete the " + GitServerConfig.SERVER_NAME + " repository or any files in the local folder.").setNegativeButton("CANCEL",null).setPositiveButton("DELETE",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface x,int w){ repos.remove(pos); RepoStore.save(MainActivity.this,repos); refresh(); Toast.makeText(MainActivity.this,"Repository entry removed",Toast.LENGTH_SHORT).show(); }}).create(); d.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface x){ polishDialog(d); d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFC62828); }}); d.show();
    }

    private void confirmMirror(final RepoProfile r) {
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Mirror local to " + GitServerConfig.SERVER_NAME + " — " + r.name).setMessage(GitServerConfig.SERVER_NAME + " will be made the same as the selected local folder. Files that exist only on " + GitServerConfig.SERVER_NAME + " will be DELETED. Continue?").setPositiveButton("MIRROR", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { mirrorOne(r); } }).setNegativeButton("CANCEL", null).create(); d.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface x){ polishDialog(d); }}); d.show();
    }

    private void confirmRemoteMirror(final RepoProfile r) {
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Mirror " + GitServerConfig.SERVER_NAME + " to local — " + r.name).setMessage("The selected local folder will be made the same as " + GitServerConfig.SERVER_NAME + ". Local files that do not exist on " + GitServerConfig.SERVER_NAME + " will be DELETED. Continue?").setPositiveButton("MIRROR", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { mirrorRemoteOne(r); } }).setNegativeButton("CANCEL", null).create(); d.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface x){ polishDialog(d); }}); d.show();
    }

    private void mirrorRemoteOne(final RepoProfile r) {
        if (!prepareAuth(r)) return;
        beginWork("Mirror in progress — " + r.name, "Checking " + r.name + " for mirror..."); status.setText("Mirroring " + r.name + " from " + GitServerConfig.SERVER_NAME + " to local folder...");
        GitSyncService.mirrorRemoteToLocal(MainActivity.this, r, creationCallback(r, "Completed just now"));
    }

    private void mirrorOne(final RepoProfile r) {
        if (!prepareAuth(r)) return;
        beginWork("Mirror in progress — " + r.name, "Checking " + r.name + " for mirror..."); status.setText("Mirroring " + r.name + " from local folder to " + GitServerConfig.SERVER_NAME + "...");
        GitSyncService.mirrorLocalToRemote(MainActivity.this, r, creationCallback(r, "Completed just now"));
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private void polishDialog(AlertDialog d) { if(d.getWindow()!=null) d.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog); Button p=d.getButton(AlertDialog.BUTTON_POSITIVE), n=d.getButton(AlertDialog.BUTTON_NEGATIVE), z=d.getButton(AlertDialog.BUTTON_NEUTRAL); if(p!=null)p.setTextColor(0xFF1976D2); if(n!=null)n.setTextColor(0xFF546E7A); if(z!=null)z.setTextColor(0xFF546E7A); makeDialogDraggable(d); }

    // Drag a popup by its title. Keeping the gesture on the title avoids stealing
    // touches from fields, scrolling content, and action buttons.
    private void makeDialogDraggable(final AlertDialog dialog) {
        final Window window = dialog.getWindow();
        if (window == null) return;
        final View title = dialog.findViewById(getResources().getIdentifier("alertTitle", "id", "android"));
        if (title == null) return;
        title.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = window.getAttributes();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downRawX = event.getRawX(); downRawY = event.getRawY();
                    startX = lp.x; startY = lp.y;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    lp.gravity = Gravity.CENTER;
                    lp.x = startX + Math.round(event.getRawX() - downRawX);
                    lp.y = startY + Math.round(event.getRawY() - downRawY);
                    window.setAttributes(lp);
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });
    }

    private TextView dialogLabel(String text) { TextView v = new TextView(this); v.setText(text); v.setTextColor(0xFF455A64); v.setTextSize(13); v.setPadding(2, 10, 2, 4); return v; }
    private void styleDialogField(EditText e) { e.setBackgroundResource(R.drawable.bg_input); e.setPadding(12, 8, 12, 8); }
    private int activeOperations = 0;
    private boolean syncAllRunning = false;
    private int syncAllIndex = 0;
    // Identifies the currently valid Sync/Sync All workflow. STOP invalidates it so
    // callbacks from the checking/preview stage cannot start later stages.
    private int syncWorkflowGeneration = 0;
    private int syncAllWorkflowGeneration = 0;
    private synchronized void beginWork() {
        beginWork("Sync in progress", "Checking...");
    }
    private synchronized void beginWork(String initialMessage) {
        beginWork("Sync in progress", initialMessage);
    }
    private synchronized void beginWork(String dialogTitle, String initialMessage) {
        activeOperations++;
        showOperationDialog(dialogTitle, initialMessage == null ? "Checking..." : initialMessage);
    }
    private synchronized void endWork() {
        if (activeOperations > 0) activeOperations--;
        if (activeOperations == 0) dismissOperationDialog();
    }

    private void showOperationDialog(final String title, final String message) {
        runOnUiThread(new Runnable() { public void run() {
            if (operationDialog != null && operationDialog.isShowing()) {
                operationDialog.setTitle(title == null ? "Sync in progress" : title);
                updateOperationDialog(message);
                return;
            }
            operationDialogMessage = new TextView(MainActivity.this);
            operationDialogMessage.setText(message);
            operationDialogMessage.setTextSize(14);
            operationDialogMessage.setTextColor(0xFF37474F);
            operationDialogMessage.setPadding(dp(24), dp(14), dp(24), dp(10));
            operationDialog = new AlertDialog.Builder(MainActivity.this)
                .setTitle(title == null ? "Sync in progress" : title)
                .setView(operationDialogMessage)
                .setNegativeButton("STOP", null)
                .create();
            operationDialog.setCancelable(false);
            operationDialog.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface d) {
                polishDialog(operationDialog);
                final Button stop = operationDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (stop != null) {
                    stop.setTextColor(0xFFC62828);
                    stop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                        GitSyncService.cancel();
                        // Cancel the whole UI workflow too, not just the engine thread.
                        // This prevents a completed/queued preview callback from opening
                        // the preview or starting a transfer after STOP was pressed.
                        syncWorkflowGeneration++;
                        syncAllRunning = false;
                        stop.setEnabled(false);
                        stop.setText("STOPPING...");
                        updateOperationDialog("Stopping after the current safe operation...");
                        showProgress("Stopping after the current safe operation...");
                    }});
                }
            }});
            operationDialog.show();
        }});
    }

    private void updateOperationDialog(final String message) {
        runOnUiThread(new Runnable() { public void run() {
            if (operationDialogMessage != null && operationDialog != null && operationDialog.isShowing())
                operationDialogMessage.setText(message == null ? "Working..." : message);
        }});
    }

    private void dismissOperationDialog() {
        runOnUiThread(new Runnable() { public void run() {
            if (operationDialog != null) { try { operationDialog.dismiss(); } catch (Exception e) { } }
            operationDialog = null;
            operationDialogMessage = null;
        }});
    }

    private void showOAuthSettings() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); int pad = 28; box.setPadding(pad, 10, pad, 8);
        final EditText clientId = field("Enter " + GitServerConfig.SERVER_NAME + " OAuth Client ID"); clientId.setSingleLine(true); clientId.setText(GitHubAuth.clientId(this)); styleDialogField(clientId);
        final EditText clientSecret = field("Enter " + GitServerConfig.SERVER_NAME + " OAuth Client Secret"); clientSecret.setSingleLine(true); clientSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); clientSecret.setText(GitHubAuth.clientSecret(this)); styleDialogField(clientSecret);
        final EditText redirect = field("alexgitsync://auth"); redirect.setSingleLine(true); redirect.setText(GitHubAuth.redirectUri(this)); styleDialogField(redirect);
        TextView note = new TextView(this); note.setText("These values are saved on this device. The included Android manifest can automatically return from alexgitsync://auth. If you use another callback scheme/host, add a matching intent-filter before building. Saving signs out the current " + GitServerConfig.SERVER_NAME + " session."); note.setPadding(0, 12, 0, 0);
        box.addView(dialogLabel("CLIENT ID")); box.addView(clientId); box.addView(dialogLabel("CLIENT SECRET")); box.addView(clientSecret); box.addView(dialogLabel("REDIRECT URL")); box.addView(redirect); box.addView(note);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(GitServerConfig.SERVER_NAME + " OAuth Settings").setView(box).setPositiveButton("SAVE", null).setNegativeButton("CANCEL", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface d) { polishDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { String id = clientId.getText().toString().trim(); String secret = clientSecret.getText().toString().trim(); String callback = redirect.getText().toString().trim(); if (id.length() == 0) { clientId.setError("Required"); clientId.requestFocus(); return; } if (secret.length() == 0) { clientSecret.setError("Required"); clientSecret.requestFocus(); return; } if (callback.length() == 0) { redirect.setError("Required"); redirect.requestFocus(); return; } Uri callbackUri = Uri.parse(callback); if (callbackUri.getScheme() == null || callbackUri.getScheme().length() == 0) { Toast.makeText(MainActivity.this, "Redirect URL must include a scheme, for example alexgitsync://auth", Toast.LENGTH_LONG).show(); return; } GitHubAuth.saveOAuthSettings(MainActivity.this, id, secret, callback); updateAuthUi(); status.setText("OAuth settings saved. Please sign in with " + GitServerConfig.SERVER_NAME + "."); dialog.dismiss(); } });
        }});
        dialog.show();
    }

    private void loginOrLogout() {
        if (GitHubAuth.signedIn(this)) { if(GitSyncService.isAnyOperationActive()){ Toast.makeText(this,"A repository operation is running. Stop or finish it before signing out.",Toast.LENGTH_LONG).show(); return; } GitHubAuth.signOut(this); updateAuthUi(); status.setText("Signed out"); return; }
        if (!GitHubAuth.isConfigured(this)) { status.setText(GitServerConfig.SERVER_NAME + " OAuth settings required"); showOAuthSettings(); return; }
        status.setText("Opening " + GitServerConfig.SERVER_NAME + " authorization...");
        try { openOAuthCustomTab(GitHubAuth.createAuthorizationUrl(this)); } catch (Exception e) { status.setText("Unable to open " + GitServerConfig.SERVER_NAME + ": " + e.getMessage()); }
    }

    private void openOAuthCustomTab(String url) {
        Uri uri = Uri.parse(url);
        Intent customTab = new Intent(Intent.ACTION_VIEW, uri);
        customTab.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
        customTab.putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", false);
        customTab.putExtra("android.support.customtabs.extra.SHARE_MENU_ITEM", false);
        Bundle session = new Bundle();
        if (android.os.Build.VERSION.SDK_INT >= 18) session.putBinder("android.support.customtabs.extra.SESSION", null);
        customTab.putExtras(session);
        try { customTab.setPackage("com.android.chrome"); startActivity(customTab); return; } catch (Exception e) { }
        Intent browser = new Intent(Intent.ACTION_VIEW, uri);
        startActivity(browser);
    }

    protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleOAuthIntent(intent); }
    protected void onResume() { super.onResume(); handleOAuthIntent(getIntent()); }

    private void handleOAuthIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri u = intent.getData();
        Uri configured = Uri.parse(GitHubAuth.redirectUri(this)); if (configured.getScheme() == null || !configured.getScheme().equals(u.getScheme())) return; if (configured.getHost() != null && !configured.getHost().equals(u.getHost())) return;
        setIntent(new Intent());
        final String code = u.getQueryParameter("code"); final String state = u.getQueryParameter("state"); final String error = u.getQueryParameter("error");
        if (error != null && error.length() > 0) { status.setText(GitServerConfig.SERVER_NAME + " authorization failed: " + error); return; }
        if (code == null || code.length() == 0) { status.setText(GitServerConfig.SERVER_NAME + " did not return an authorization code."); return; }
        status.setText("Completing " + GitServerConfig.SERVER_NAME + " sign in...");
        GitHubAuth.exchangeCode(this, code, state, new GitHubAuth.AuthCallback() { public void done(final boolean ok, final String message) { runOnUiThread(new Runnable() { public void run() { updateAuthUi(); status.setText(message); Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show(); } }); } });
    }

    private boolean prepareAuth(RepoProfile r) {
        String t = GitHubAuth.token(this);
        if (t.length() == 0) { Toast.makeText(this, "Sign in with " + GitServerConfig.SERVER_NAME + " first.", Toast.LENGTH_LONG).show(); return false; }
        r.token = t; return true;
    }

    private void showProgress(final String m) { runOnUiThread(new Runnable() { public void run() { String old = status.getText().toString(); if (old.length() > 3500) old = old.substring(old.length() - 2500); status.setText(old + "\n" + m); if (logScroll != null) { logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); } }); } } }); }

    private boolean askCreateRepository(final String owner, final String repository) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean answer = new AtomicBoolean(false);
        runOnUiThread(new Runnable() { public void run() {
            final AlertDialog q = new AlertDialog.Builder(MainActivity.this)
                .setTitle("Repository not found — " + owner + "/" + repository)
                .setMessage("The " + GitServerConfig.SERVER_NAME + " repository " + owner + "/" + repository + " does not exist or is not accessible.\n\nCreate a new PUBLIC repository with this name and continue the operation?")
                .setPositiveButton("CREATE REPOSITORY", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { answer.set(true); latch.countDown(); } })
                .setNegativeButton("CANCEL", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { answer.set(false); latch.countDown(); } })
                .setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface d) { answer.set(false); latch.countDown(); } })
                .create(); q.setOnShowListener(new DialogInterface.OnShowListener(){ public void onShow(DialogInterface d){ polishDialog(q); }}); q.show();
        } });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return answer.get();
    }

    private GitSyncEngine.CreationCallback creationCallback(final RepoProfile repo, final String successStatus) {
        return new GitSyncEngine.CreationCallback() {
            public void progress(final String m) { updateRepoStatus(repo, m); showProgress(m); updateOperationDialog(m); }
            public boolean confirmCreateRepository(String owner, String repository) { return askCreateRepository(owner, repository); }
            public void done(final String m) { updateRepoStatus(repo, m.startsWith("ERROR") ? "Failed — tap to retry" : successStatus); runOnUiThread(new Runnable() { public void run() { endWork(); showProgress(m); Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); } }); }
        };
    }

    private void syncOne(final RepoProfile r) {
        if (!prepareAuth(r)) return;
        final int workflow = ++syncWorkflowGeneration;
        beginWork("Sync in progress — " + r.name, "Checking " + r.name + "...");
        updateRepoStatus(r,"Checking..."); showProgress("Checking " + r.name + "...");
        GitSyncService.previewSync(MainActivity.this,r,new GitSyncEngine.PreviewCallback(){
            public void ready(final GitSyncEngine.SyncPreview x){ runOnUiThread(new Runnable(){public void run(){
                endWork();
                if (workflow != syncWorkflowGeneration) {
                    updateRepoStatus(r, "Stopped by user");
                    showProgress("Sync stopped by user: " + r.name);
                    return;
                }
                showSyncPreview(r,x,workflow);
            }}); }
            public void error(final String m){ runOnUiThread(new Runnable(){public void run(){
                if (workflow != syncWorkflowGeneration) {
                    endWork();
                    updateRepoStatus(r, "Stopped by user");
                    showProgress("Sync stopped by user: " + r.name);
                    return;
                }
                if(m!=null && m.indexOf("404")>=0){ showProgress("Repository not found; checking creation option..."); updateOperationDialog("Repository not found. Checking creation option..."); GitSyncService.sync(MainActivity.this,r,creationCallback(r, "Synced just now")); } else { endWork(); updateRepoStatus(r,"Preview failed"); showProgress("ERROR " + r.name + ": " + m); }
            }}); }
        });
    }

    private void showSyncPreview(final RepoProfile r, GitSyncEngine.SyncPreview x, final int workflow) {
        StringBuilder b=new StringBuilder(); b.append(x.summary());
        appendPreviewSection(b, "UPLOAD", x.uploads);
        appendPreviewSection(b, "DOWNLOAD", x.downloads);
        appendPreviewSection(b, "CONFLICT — skipped", x.conflicts);
        if(x.conflict>0) b.append("\n\nConflicts are protected. Use Upload or Download from the repository actions to choose which copy should win.");
        b.append("\n\nOnly local-only files will upload and " + GitServerConfig.SERVER_NAME + "-only files will download.");
        final AlertDialog d=new AlertDialog.Builder(this).setTitle("Sync Preview — " + r.name).setMessage(b.toString()).setNegativeButton("CANCEL",null).setPositiveButton("SYNC",new DialogInterface.OnClickListener(){public void onClick(DialogInterface q,int w){ if (workflow != syncWorkflowGeneration) return; beginWork("Sync in progress — " + r.name, "Starting sync for " + r.name + "..."); updateRepoStatus(r,"Syncing..."); GitSyncService.sync(MainActivity.this,r,creationCallback(r, "Synced just now")); }}).create();
        d.setOnShowListener(new DialogInterface.OnShowListener(){public void onShow(DialogInterface q){polishDialog(d);}}); d.show();
    }

    private void appendPreviewSection(StringBuilder b, String title, java.util.ArrayList<String> files) {
        if (files == null || files.size() == 0) return;
        b.append("\n\n").append(title).append(" (").append(files.size()).append(")");
        int limit = Math.min(20, files.size());
        for (int i = 0; i < limit; i++) b.append("\n• ").append(files.get(i));
        if (files.size() > limit) b.append("\n• ... and ").append(files.size() - limit).append(" more");
    }

    private void downloadOne(final RepoProfile r) {
        if (!prepareAuth(r)) return;
        beginWork("Download in progress — " + r.name, "Checking " + r.name + " for download..."); status.setText("Downloading " + r.name + "...");
        GitSyncService.download(MainActivity.this, r, creationCallback(r, "Completed just now"));
    }

    private void uploadOne(final RepoProfile r) {
        if (!prepareAuth(r)) return;
        beginWork("Upload in progress — " + r.name, "Checking " + r.name + " for upload..."); status.setText("Uploading " + r.name + "...");
        GitSyncService.upload(MainActivity.this, r, creationCallback(r, "Completed just now"));
    }

    private void syncAll() {
        if (syncAllRunning) { Toast.makeText(this, "Sync All is already running.", Toast.LENGTH_SHORT).show(); return; }
        if (repos.size() == 0) { Toast.makeText(this, "No repositories to sync.", Toast.LENGTH_SHORT).show(); return; }
        syncAllRunning = true;
        syncAllIndex = 0;
        syncAllWorkflowGeneration = ++syncWorkflowGeneration;
        showProgress("Sync All started. Repositories will be processed one at a time.");
        syncAllNext();
    }

    private void syncAllNext() {
        if (!syncAllRunning || syncAllWorkflowGeneration != syncWorkflowGeneration) return;
        if (syncAllIndex >= repos.size()) {
            syncAllRunning = false;
            showProgress("Sync All finished.");
            Toast.makeText(this, "Sync All finished.", Toast.LENGTH_SHORT).show();
            return;
        }
        final RepoProfile r = repos.get(syncAllIndex++);
        if (!prepareAuth(r)) { syncAllRunning = false; return; }
        beginWork("Sync All in progress — " + r.name, "Checking " + r.name + " (" + syncAllIndex + "/" + repos.size() + ")...");
        updateRepoStatus(r, "Checking...");
        showProgress("Checking " + r.name + " (" + syncAllIndex + "/" + repos.size() + ")...");
        GitSyncService.previewSync(MainActivity.this, r, new GitSyncEngine.PreviewCallback() {
            public void ready(final GitSyncEngine.SyncPreview x) { runOnUiThread(new Runnable() { public void run() {
                endWork();
                if (!syncAllRunning || syncAllWorkflowGeneration != syncWorkflowGeneration) {
                    updateRepoStatus(r, "Stopped by user");
                    return;
                }
                showSyncAllPreview(r, x);
            }}); }
            public void error(final String m) { runOnUiThread(new Runnable() { public void run() {
                if (!syncAllRunning || syncAllWorkflowGeneration != syncWorkflowGeneration) {
                    endWork();
                    updateRepoStatus(r, "Stopped by user");
                    return;
                }
                if (m != null && m.indexOf("404") >= 0) {
                    updateOperationDialog("Repository not found. Checking creation option...");
                    GitSyncService.sync(MainActivity.this, r, syncAllCreationCallback(r));
                } else {
                    endWork();
                    updateRepoStatus(r, "Preview failed");
                    showProgress("ERROR " + r.name + ": " + m);
                    syncAllNext();
                }
            }}); }
        });
    }

    private void showSyncAllPreview(final RepoProfile r, GitSyncEngine.SyncPreview x) {
        if (!syncAllRunning || syncAllWorkflowGeneration != syncWorkflowGeneration) return;
        StringBuilder b = new StringBuilder(); b.append(x.summary());
        appendPreviewSection(b, "UPLOAD", x.uploads);
        appendPreviewSection(b, "DOWNLOAD", x.downloads);
        appendPreviewSection(b, "CONFLICT — skipped", x.conflicts);
        if (x.conflict > 0) b.append("\n\nConflicts are protected. Use Upload or Download from the repository actions to choose which copy should win.");
        b.append("\n\nRepository ").append(syncAllIndex).append(" of ").append(repos.size()).append(". Only one Sync All popup is shown at a time.");
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Sync All Preview — " + r.name).setMessage(b.toString())
            .setNegativeButton("SKIP", new DialogInterface.OnClickListener() { public void onClick(DialogInterface q, int w) { updateRepoStatus(r, "Skipped"); syncAllNext(); } })
            .setPositiveButton("SYNC", new DialogInterface.OnClickListener() { public void onClick(DialogInterface q, int w) {
                if (!syncAllRunning || syncAllWorkflowGeneration != syncWorkflowGeneration) return;
                beginWork("Sync All in progress — " + r.name, "Starting sync for " + r.name + "..."); updateRepoStatus(r, "Syncing...");
                GitSyncService.sync(MainActivity.this, r, syncAllCreationCallback(r));
            }}).create();
        d.setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface q) { if (syncAllRunning) syncAllNext(); }});
        d.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface q) { polishDialog(d); }});
        d.show();
    }

    private GitSyncEngine.CreationCallback syncAllCreationCallback(final RepoProfile repo) {
        return new GitSyncEngine.CreationCallback() {
            public void progress(final String m) { updateRepoStatus(repo, m); showProgress(m); updateOperationDialog(m); }
            public boolean confirmCreateRepository(String owner, String repository) { return askCreateRepository(owner, repository); }
            public void done(final String m) {
                updateRepoStatus(repo, m.startsWith("ERROR") ? "Failed — tap to retry" : "Synced just now");
                runOnUiThread(new Runnable() { public void run() {
                    endWork(); showProgress(m);
                    if (syncAllRunning) syncAllNext();
                }});
            }
        };
    }
}
