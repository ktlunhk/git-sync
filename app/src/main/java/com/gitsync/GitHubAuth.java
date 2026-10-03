package com.gitsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class GitHubAuth {
    private static final String PREF = "github_auth";

    public interface AuthCallback { void done(boolean ok, String message); }

    public static String clientId(Context c) { String v = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("client_id", "").trim(); return v.length() > 0 ? v : OAuthConfig.GITHUB_CLIENT_ID.trim(); }
    public static String clientSecret(Context c) { String v = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("client_secret", "").trim(); return v.length() > 0 ? v : OAuthConfig.GITHUB_CLIENT_SECRET.trim(); }
    public static String redirectUri(Context c) { String v = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("redirect_uri", "").trim(); return v.length() > 0 ? v : OAuthConfig.GITHUB_REDIRECT_URI.trim(); }
    public static boolean isConfigured(Context c) { String id = clientId(c); String secret = clientSecret(c); return id.length() > 10 && secret.length() > 10 && id.indexOf("PASTE_") < 0 && secret.indexOf("PASTE_") < 0; }
    public static void saveOAuthSettings(Context c, String id, String secret, String redirect) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("client_id", id == null ? "" : id.trim()).putString("client_secret", secret == null ? "" : secret.trim()).putString("redirect_uri", redirect == null ? "" : redirect.trim()).remove("token").remove("oauth_state").remove("pkce_verifier").apply(); }
    public static void clearOAuthSettings(Context c) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("client_id").remove("client_secret").remove("redirect_uri").remove("token").remove("oauth_state").remove("pkce_verifier").apply(); }

    public static String createAuthorizationUrl(Context c) throws Exception {
        if (!isConfigured(c)) throw new Exception(GitServerConfig.SERVER_NAME + " OAuth Client ID and Client Secret are not configured. Open Settings.");
        String clientId = clientId(c); String redirect = redirectUri(c);
        String state = randomUrlSafe(32); String verifier = randomUrlSafe(64); String challenge = sha256UrlSafe(verifier);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("oauth_state", state).putString("pkce_verifier", verifier).apply();
        return GitServerConfig.oauthAuthorizeUrl() + "?client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirect) + "&scope=" + enc("repo user:email") + "&state=" + enc(state) + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256&prompt=select_account";
    }

    public static void exchangeCode(final Context c, final String code, final String returnedState, final AuthCallback cb) {
        new Thread(new Runnable() { public void run() { try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE); String expected = p.getString("oauth_state", ""); String verifier = p.getString("pkce_verifier", "");
            if (expected.length() == 0 || returnedState == null || !expected.equals(returnedState)) throw new Exception("OAuth state validation failed. Please sign in again.");
            String id = clientId(c); String secret = clientSecret(c); String redirect = redirectUri(c); String body = "client_id=" + enc(id) + "&client_secret=" + enc(secret) + "&code=" + enc(code) + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + enc(verifier);
            JSONObject result = post(GitServerConfig.oauthAccessTokenUrl(), body); String token = result.optString("access_token", "");
            if (token.length() == 0) throw new Exception(errorMessage(result, GitServerConfig.SERVER_NAME + " did not return an access token."));
            p.edit().putString("token", token).remove("oauth_state").remove("pkce_verifier").apply(); cb.done(true, "Signed in to " + GitServerConfig.SERVER_NAME);
        } catch (Exception e) { cb.done(false, e.getMessage() == null ? e.toString() : e.getMessage()); } }}).start();
    }

    public static String token(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("token", ""); }
    public static boolean signedIn(Context c) { return token(c).length() > 0; }
    public static void signOut(Context c) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("token").remove("oauth_state").remove("pkce_verifier").apply(); }

    private static JSONObject post(String address, String body) throws Exception { HttpURLConnection h = (HttpURLConnection)new URL(address).openConnection(); h.setRequestMethod("POST"); h.setDoOutput(true); h.setConnectTimeout(20000); h.setReadTimeout(30000); h.setRequestProperty("Accept", "application/json"); h.setRequestProperty("User-Agent", "AIDE-GitSync-Android"); h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); OutputStream os = h.getOutputStream(); os.write(body.getBytes("UTF-8")); os.close(); int rc = h.getResponseCode(); InputStream in = rc >= 200 && rc < 300 ? h.getInputStream() : h.getErrorStream(); String text = read(in); h.disconnect(); if (text.length() == 0) throw new Exception(GitServerConfig.SERVER_NAME + " returned HTTP " + rc + " with an empty response."); JSONObject json = new JSONObject(text); if (rc < 200 || rc >= 300) throw new Exception(GitServerConfig.SERVER_NAME + " OAuth failed (HTTP " + rc + "): " + errorMessage(json, text)); return json; }
    private static String errorMessage(JSONObject o, String fallback) { String d = o.optString("error_description", ""); if (d.length() > 0) return d; String e = o.optString("error", ""); return e.length() > 0 ? e : fallback; }
    private static String randomUrlSafe(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String sha256UrlSafe(String s) throws Exception { MessageDigest md = MessageDigest.getInstance("SHA-256"); return Base64.encodeToString(md.digest(s.getBytes("UTF-8")), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
    private static String read(InputStream in) throws Exception { if (in == null) return ""; BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8")); StringBuilder b = new StringBuilder(); String x; while ((x = r.readLine()) != null) b.append(x); r.close(); return b.toString(); }
}
