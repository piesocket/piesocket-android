package com.piesocket.channels.misc;

import java.util.HashMap;

public class PieSocketOptions {

    private String apiKey;
    private String clusterId;
    private Boolean enableLogs;
    private Boolean notifySelf;
    private String jwt;
    private Boolean presence;
    private  String authEndpoint;
    private HashMap<String, String> authHeaders;
    private Boolean forceAuth;
    private String userId;
    private String version;
    private String webSocketEndpoint;
    private String clusterDomain;
    private Boolean ssl;

    public PieSocketOptions(){
        this.version = "3";
        this.enableLogs = true;
        this.notifySelf = true;
        this.presence = false;
        this.forceAuth = false;
        this.ssl = true;
    }

    public String getWebSocketEndpoint() {
        return webSocketEndpoint;
    }

    public void setWebSocketEndpoint(String webSocketEndpoint) {
        this.webSocketEndpoint = webSocketEndpoint;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public Boolean getEnableLogs() {
        return enableLogs;
    }

    public void setEnableLogs(Boolean enableLogs) {
        this.enableLogs = enableLogs;
    }

    public int getNotifySelf() {
        return notifySelf ? 1:0;
    }

    public void setNotifySelf(Boolean notifySelf) {
        this.notifySelf = notifySelf;
    }

    public String getJwt() {
        return jwt;
    }

    public void setJwt(String jwt) {
        this.jwt = jwt;
    }

    public int getPresence() {
        return presence ? 1:0;
    }

    public void setPresence(Boolean presence) {
        this.presence = presence;
    }

    public String getAuthEndpoint() {
        return authEndpoint;
    }

    public void setAuthEndpoint(String authEndpoint) {
        this.authEndpoint = authEndpoint;
    }

    public HashMap<String, String> getAuthHeaders() {
        return authHeaders;
    }

    public void setAuthHeaders(HashMap<String, String> authHeaders) {
        this.authHeaders = authHeaders;
    }

    public Boolean getForceAuth() {
        return forceAuth;
    }

    public void setForceAuth(Boolean forceAuth) {
        this.forceAuth = forceAuth;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getClusterDomain() {
        return clusterDomain;
    }

    public void setClusterDomain(String clusterDomain) {
        this.clusterDomain = clusterDomain;
    }

    public Boolean getSsl() {
        return ssl;
    }

    public void setSsl(Boolean ssl) {
        this.ssl = ssl;
    }

}
