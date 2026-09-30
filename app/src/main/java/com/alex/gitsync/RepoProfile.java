package com.alex.gitsync;

public class RepoProfile {
    public String name;
    public String url;
    public String branch;
    public String localPath;
    public String username;
    public String token;

    public RepoProfile(String name, String url, String branch, String localPath, String username, String token) {
        this.name = name; this.url = url; this.branch = branch; this.localPath = localPath; this.username = username; this.token = token;
    }
}
