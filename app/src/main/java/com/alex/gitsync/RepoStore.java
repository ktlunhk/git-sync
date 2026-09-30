package com.alex.gitsync;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public class RepoStore {
    private static final String PREF = "repos";
    private static final String KEY = "list";

    public static ArrayList<RepoProfile> load(Context c) {
        ArrayList<RepoProfile> out = new ArrayList<RepoProfile>();
        try {
            String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]");
            JSONArray a = new JSONArray(s);
            int i;
            for (i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new RepoProfile(o.optString("name"), o.optString("url"), o.optString("branch", "main"), o.optString("localPath"), o.optString("username"), o.optString("token")));
            }
        } catch (Exception e) { }
        return out;
    }

    public static void save(Context c, ArrayList<RepoProfile> list) {
        JSONArray a = new JSONArray();
        try {
            int i;
            for (i = 0; i < list.size(); i++) {
                RepoProfile r = list.get(i);
                JSONObject o = new JSONObject();
                o.put("name", r.name); o.put("url", r.url); o.put("branch", r.branch); o.put("localPath", r.localPath); o.put("username", r.username); o.put("token", r.token);
                a.put(o);
            }
        } catch (Exception e) { }
        SharedPreferences.Editor ed = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        ed.putString(KEY, a.toString()); ed.apply();
    }
}
