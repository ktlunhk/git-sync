package com.gitsync;

import java.util.ArrayList;

/** One place for repository-profile identity/duplicate validation. */
public final class RepositoryValidator {
    private RepositoryValidator() { }
    public static String duplicateName(ArrayList<RepoProfile> repos, int editing, String name) {
        String n = name == null ? "" : name.trim();
        for (int i=0;i<repos.size();i++) if (i!=editing) {
            RepoProfile r=repos.get(i);
            if (r!=null && r.name!=null && r.name.trim().equalsIgnoreCase(n)) return "Repository name already exists";
        }
        return null;
    }
    public static String duplicateRemote(ArrayList<RepoProfile> repos, int editing, String url, String branch) {
        String key=GitServerConfig.repositoryKey(url);
        String b=branch==null?"":branch.trim();
        if(key==null) return null;
        for(int i=0;i<repos.size();i++) if(i!=editing) {
            RepoProfile r=repos.get(i); if(r==null) continue;
            String other=GitServerConfig.repositoryKey(r.url);
            if(key.equals(other) && b.equals(r.branch==null?"":r.branch.trim())) return "This repository and branch are already configured";
        }
        return null;
    }
    public static String duplicateLocal(ArrayList<RepoProfile> repos, int editing, String local) {
        String p=local==null?"":local.trim();
        for(int i=0;i<repos.size();i++) if(i!=editing) {
            RepoProfile r=repos.get(i);
            if(r!=null && r.localPath!=null && r.localPath.trim().equals(p)) return "This local folder is already used by another repository";
        }
        return null;
    }
}
