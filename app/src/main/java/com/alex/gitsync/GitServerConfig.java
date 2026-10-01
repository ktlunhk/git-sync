package com.alex.gitsync;

import java.net.URLEncoder;

/**
 * Central location for Git-server URLs used by myGitSync.
 * Change these values/methods when moving to GitHub Enterprise or another
 * GitHub-compatible server instead of searching through the sync code.
 */
public final class GitServerConfig {
    private GitServerConfig() { }

    public static final String SERVER_NAME = "GitHub";
    public static final String WEB_BASE_URL = "https://github.com";
    public static final String API_BASE_URL = "https://api.github.com";
    public static final String OAUTH_BASE_URL = WEB_BASE_URL + "/login/oauth";
    public static final String LFS_SPEC_URL = "https://git-lfs.github.com/spec/v1";

    public static String oauthAuthorizeUrl() { return OAUTH_BASE_URL + "/authorize"; }
    public static String oauthAccessTokenUrl() { return OAUTH_BASE_URL + "/access_token"; }
    public static String currentUserUrl() { return API_BASE_URL + "/user"; }
    public static String createUserRepoUrl() { return API_BASE_URL + "/user/repos"; }
    public static String createOrgRepoUrl(String owner) throws Exception { return API_BASE_URL + "/orgs/" + enc(owner) + "/repos"; }

    public static String repositoryWebPrefix() { return WEB_BASE_URL + "/"; }
    public static String repositoryExampleUrl() { return WEB_BASE_URL + "/user/repo.git"; }
    public static boolean isRepositoryUrl(String value) {
        if (value == null) return false;
        String s = value.trim();
        return s.startsWith(WEB_BASE_URL + "/") || s.startsWith(toHttp(WEB_BASE_URL) + "/");
    }

    public static String repoApiUrl(String owner, String repo) throws Exception {
        return API_BASE_URL + "/repos/" + enc(owner) + "/" + enc(repo);
    }
    public static String treeUrl(String owner, String repo, String branch) throws Exception {
        return repoApiUrl(owner, repo) + "/git/trees/" + enc(branch) + "?recursive=1";
    }
    public static String blobUrl(String owner, String repo, String sha) throws Exception {
        return repoApiUrl(owner, repo) + "/git/blobs/" + enc(sha);
    }
    public static String contentsUrl(String owner, String repo, String path) throws Exception {
        return repoApiUrl(owner, repo) + "/contents/" + encodePath(path);
    }
    public static String lfsBaseUrl(String owner, String repo) throws Exception {
        return WEB_BASE_URL + "/" + enc(owner) + "/" + enc(repo) + ".git/info/lfs";
    }


    /** Canonical owner/repository identity used for duplicate and operation checks. */
    public static String repositoryKey(String value) {
        String[] a = parseRepositoryUrl(value);
        if (a == null) return null;
        return a[0].trim().toLowerCase(java.util.Locale.US) + "/" + a[1].trim().toLowerCase(java.util.Locale.US);
    }

    /** Normalized URL suitable for storing after validation. */
    public static String normalizeRepositoryUrl(String value) {
        String[] a = parseRepositoryUrl(value);
        if (a == null) return value == null ? "" : value.trim();
        return WEB_BASE_URL + "/" + a[0] + "/" + a[1] + ".git";
    }

    public static String[] parseRepositoryUrl(String value) {
        if (value == null) return null;
        String s = value.trim();
        String httpsPrefix = WEB_BASE_URL + "/";
        String httpPrefix = toHttp(WEB_BASE_URL) + "/";
        if (s.startsWith(httpsPrefix)) s = s.substring(httpsPrefix.length());
        else if (s.startsWith(httpPrefix)) s = s.substring(httpPrefix.length());
        else return null;
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String[] a = s.split("/");
        if (a.length != 2 || a[0].length() == 0 || a[1].length() == 0) return null;
        return a;
    }

    private static String toHttp(String url) {
        return url.startsWith("https://") ? "http://" + url.substring(8) : url;
    }
    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
    private static String encodePath(String path) throws Exception {
        String[] parts = path.split("/");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append('/');
            b.append(enc(parts[i]));
        }
        return b.toString();
    }
}
