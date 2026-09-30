package com.alex.gitsync;

public final class OAuthConfig {
    private OAuthConfig() { }

    // Developer configuration. End users never enter these values in the app.
    // Register your own GitHub OAuth App and paste its values here once before building.
    public static final String GITHUB_CLIENT_ID = "Ov23liNymGuPfvw46LIy";
    public static final String GITHUB_CLIENT_SECRET = "df7517682a167b417628c49edb05aa1bb6270018";
    public static final String GITHUB_REDIRECT_URI = "alexgitsync://auth";
}
