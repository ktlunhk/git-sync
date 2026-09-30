# AIDE GitSync Java v1.6

AIDE-compatible Android Java project. No AndroidX, no JGit, and no Java lambda (`->`) syntax.

## GitHub login like GitSync
Normal users only tap **SIGN IN WITH GITHUB**. They do not enter a Client ID in the app.

Before building your own distribution, register your own GitHub OAuth App and edit only:

`app/src/main/java/com/alex/gitsync/OAuthConfig.java`

Set:
- `GITHUB_CLIENT_ID`
- `GITHUB_CLIENT_SECRET`
- callback URL at GitHub: `alexgitsync://auth`

Do not use GitSync/ViscousPotential's OAuth credentials; those identify their application.

The Client Secret is present in an APK if compiled this way, so this design is appropriate for a personal/testing build. For a public production app, perform the secret-bearing token exchange on a backend.

## v1.7 in-app browser style OAuth
GitHub authorization is opened as a Chrome Custom Tab when Chrome is available, without AndroidX. This gives an in-app browser style screen with a close button and returns to the app through `alexgitsync://auth`. If Custom Tabs are unavailable, the app falls back to the default browser.

## v1.26 large-file upload
- Files 10 MiB and larger use GitHub's Git Data API with streaming Base64 rather than buffering the entire file and JSON body in Android memory.
- Upload progress is reported in the Sync Log.
- Files over 100 MiB are rejected with a clear Git LFS message.

## v1.27 large-file fix
- Files 50 MiB and larger are uploaded through Git LFS instead of the GitHub Git-blob JSON endpoint that returned HTTP 422 for an 81 MiB APK.
- Upload progress reserves the final stage for GitHub finalization instead of reporting 99% before server acceptance.
- Existing repository creation, popup validation/styling, and delete warning behavior are retained.

## v1.28 safety and usability changes
- Sync Preview before two-way Sync: upload/download/conflict/unchanged counts.
- Conflict protection: two-way Sync skips differing same-path files instead of overwriting; choose Upload or Download explicitly to resolve.
- Per-repository status text in the repository list.
- Per-file upload retry (up to 3 file-level attempts), in addition to existing HTTP retry handling.


## v1.29 - Log window layout fix
- Fixed the main content container so it uses only the screen space remaining below the header.
- Repository list now uses the flexible remaining area instead of forcing a 1-4 row pixel height.
- Removed the spacer that could push the Sync Log card below the app window.
- Sync Log remains fully inside the app content area and continues to respect system-bar insets.
- Preserves v1.28 sync preview, conflict protection, repository status, retry handling, Git LFS, repository creation prompt, validation, and delete warning.


## v1.31 UI consistency
- Added vector icons to every main action button.
- Added icons to all repository action buttons.
- Added Stop and Clear icons in the Sync Log toolbar.
- Replaced Unicode Add/Sync glyphs with consistent Android vector drawables.

## v1.32 button icon alignment
- Centers each main/log button icon + label as one group with a small 5dp gap.
- Uses a dark sync icon in the light repository-action Sync button while retaining the white sync icon on the purple Sync All button.

## v1.34 button vertical alignment
- Centers inline vector icons against the text font metrics instead of aligning them to the text bottom.
- Keeps icon and label as one centered group with the existing compact horizontal spacing.
- Applies to all main action buttons using the shared icon/text helper, including OAuth, sign in/out, add, sync all, STOP and CLEAR.
- Repository action buttons already use CENTER_VERTICAL and retain that alignment.


## v1.35 UI update
- Simplified top bar to show only myGitSync.
- Removed the header app icon and GitHub repository sync subtitle.
- Left-aligned the app name and reduced header height.
- Preserved v1.34 button alignment and existing sync functionality.

## v1.36
- Repository list now preserves its first visible item and exact vertical offset when UI status refreshes occur.
- Sync, upload, download, mirror, preview/status updates, edit/save, failures and completion no longer jump the repository list back to the top.
- After deletion, the list remains near the previous viewport and clamps safely when the last item is removed.

## v1.37 repository scroll-position fix
- Repository status/progress changes now update the visible status row in place.
- Sync Preview -> SYNC no longer rebuilds the repository ListView.
- Background progress and completion status no longer reset the repository viewport.
- Full list refresh remains for structural changes such as add/edit/delete.

## v1.38 Git server configuration
GitHub endpoint construction is centralized in `GitServerConfig.java`. To adapt the app to GitHub Enterprise or another GitHub-compatible server, start by changing `WEB_BASE_URL`, `API_BASE_URL`, OAuth URL construction, and (if required) LFS URL construction in that class. Sync/auth/UI code no longer contains hard-coded GitHub endpoint paths.

## v1.39 reliability and portability changes
- Added Git LFS download support: LFS pointer blobs are detected and the real large object is streamed to SAF storage for Download, two-way Sync, and Mirror server-to-local.
- LFS download reports progress, honors STOP/cancellation, validates the downloaded byte count, and avoids buffering the large object in memory.
- Repository profiles no longer persist OAuth access tokens. Authentication is injected at runtime from the central OAuth session.
- Remaining user-facing Git-server wording now uses `GitServerConfig.SERVER_NAME` in the main sync/auth flows, making future server changes less misleading.
- Sync Preview now shows file-level Upload, Download, and Conflict sections (up to 20 paths per section) in addition to the counts.
- Repository profile load/serialization exceptions are now written to Logcat instead of being silently swallowed.


## v1.40
- Removed the Clear button from the OAuth Settings dialog.
- OAuth Save and Cancel behavior remains unchanged.

## v1.41
- All AlertDialog popup windows can now be dragged by their title area.
- Dragging is limited to the title gesture so form fields, scrolling content, and buttons retain normal touch behavior.
- Mirror confirmation dialogs now use the same popup styling/drag behavior as other dialogs.


## v1.42
- Fixed STOP cancellation state persisting into later Sync Preview/Sync operations.
- STOP now uses operation generations: it cancels operations already running when STOP is pressed, while new operations can start normally afterward.
- Preserves safe behavior for concurrent Sync All operations.

## v1.43
- Fixed Sync All opening multiple Sync Preview dialogs at the same time.
- Sync All now processes repositories sequentially and displays only one preview popup at a time.
- Added SKIP to advance to the next repository without syncing it.
- STOP cancels the current operation and stops the remaining Sync All queue.
- Individual repository Sync behavior is unchanged.

## v1.44
- Moved STOP from the Sync Log header into a modal operation-progress popup.
- The progress popup appears only after a sync/download/upload/mirror operation starts.
- Progress messages are reflected in the popup while the full log remains available on the main screen.
- STOP cancels the active operation; during Sync All it also cancels the remaining queue.
- STOP changes to STOPPING... and is disabled after being pressed to prevent repeated cancellation requests.
- The operation popup is draggable by its title, consistent with other app dialogs.

## v1.45
- Repository transfer actions now open the progress/STOP popup immediately when checking begins.
- Sync and Sync All show `Checking...` during preview preparation, then transition to the preview confirmation.
- Download, Upload, and both Mirror directions show the progress popup from the beginning of their operation.
- Preview failures correctly close the checking popup; missing-repository handling continues in the same progress popup.

## v1.46 - Stop Entire Sync Workflow
- STOP during the initial Sync "Checking..." stage now cancels the complete Sync workflow.
- A cancelled check can no longer open Sync Preview afterward.
- A cancelled workflow can no longer start upload/download/finalization stages from a queued callback.
- Added UI workflow-generation guarding in addition to the engine cancellation generation.
- Sync All uses the same guard, and cancellation callbacks now correctly release the active-operation/progress popup state.
