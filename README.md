# myGitSync v1.52 — UI/Text Consistency

Based on v1.51. Synchronization algorithms are unchanged.

Changes:
- Repository-specific destructive/confirmation dialogs now include the repository name.
- Mirror confirmation titles use the same `Action — Repository` pattern as Sync Preview/progress.
- Repository-not-found dialog identifies the exact owner/repository.
- Dialog action buttons use a consistent uppercase convention (SAVE, CANCEL, MIRROR, CREATE REPOSITORY, etc.).
- Checking terminology is standardized between repository status, progress popup, and Sync All.
- Upload/Download/Mirror successful repository status now says `Completed just now`; Sync continues to say `Synced just now`.
- Failure status punctuation standardized to `Failed — tap to retry`.
- Git server naming remains derived from `GitServerConfig.SERVER_NAME`.

No changes were made to LFS comparison, transfer rules, conflict rules, or foreground-service synchronization behavior.
