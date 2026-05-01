package com.piesocket.channels;

import android.util.Log;

import com.piesocket.channels.misc.PieSocketEvent;
import com.piesocket.channels.misc.PieSocketEventListener;
import com.piesocket.channels.misc.Logger;
import com.piesocket.channels.misc.PieSocketException;
import com.piesocket.channels.misc.PieSocketOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class Channel extends WebSocketListener implements Callback {

    private static final int NORMAL_CLOSURE_STATUS = 1000;
    public String id;
    public WebSocket ws;
    public String uuid;

    private HashMap<String, ArrayList<PieSocketEventListener>> listeners;
    private Logger logger;
    private PieSocketOptions options;
    private JSONArray members;
    private Boolean shouldReconnect;

    public Channel(String roomId, PieSocketOptions pieSocketOptions, Logger logger){
        this.listeners = new HashMap<>();
        this.id = roomId;
        this.logger = logger;
        this.options = pieSocketOptions;
        this.uuid = UUID.randomUUID().toString();
        this.shouldReconnect = false;

        this.connect(roomId);
    }

    public Channel(String webSocketURL, boolean enableLogs){
        this.listeners = new HashMap<>();
        this.id = "standalone";
        this.logger = new Logger(enableLogs);
        this.uuid = UUID.randomUUID().toString();
        this.shouldReconnect = false;

        this.options = new PieSocketOptions();
        this.options.setWebSocketEndpoint(webSocketURL);

        this.connect(this.id);
    }

    public String buildEndpoint(){
        if(this.options.getWebSocketEndpoint() != null){
            return this.options.getWebSocketEndpoint();
        }

        String clusterDomain = this.options.getClusterDomain() != null
                ? this.options.getClusterDomain()
                : this.options.getClusterId() + ".piesocket.com";
        String protocol = this.options.getSsl() ? "wss" : "ws";
        String endpoint = protocol + "://" + clusterDomain + "/v" + this.options.getVersion() + "/" + this.id + "?api_key=" + this.options.getApiKey() + "&notify_self=" + this.options.getNotifySelf() + "&source=androidsdk&v=1&presence="+ this.options.getPresence();

        String jwt = this.getAuthToken();
        if(jwt != null){
            endpoint = endpoint + "&jwt=" + jwt;
        }

        if(this.options.getUserId() != null){
            endpoint = endpoint + "&user=" + this.options.getUserId();
        }

        //Add UUID
        endpoint = endpoint +"&uuid=" + this.uuid;

        return endpoint;
    }

    public Boolean isGuarded(){
        if(this.options.getForceAuth()){
            return true;
        }

        return this.id.startsWith("private-");
    }

    public String getAuthToken(){
        if(this.options.getJwt() != null){
            return this.options.getJwt();
        }

        if(this.isGuarded()){
            if(this.options.getAuthEndpoint() != null){
                getAuthTokenFromServer();
                throw new PieSocketException("JWT not provided, will fetch from authEndpoint.");
            }else{
                throw new PieSocketException("Neither JWT, nor authEndpoint is provided for private channel authentication.");
            }
        }

        return null;
    }

    public void getAuthTokenFromServer(){
        OkHttpClient okHttpClient = new OkHttpClient();

        Request request = new Request.Builder().url(this.options.getAuthEndpoint()).build();
        okHttpClient.newCall(request).enqueue(this);
    }

    //TODO: No param required
    public void connect(String roomId){
        logger.log("Connecting to: "+roomId);

        try{
            String endpoint = this.buildEndpoint();
            this.logger.log("WebSocket Endpoint: "+ endpoint);
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(0,  TimeUnit.MILLISECONDS)
                    .build();

            Request request = new Request
                    .Builder()
                    .url(endpoint)
                    .build();

            this.ws = client.newWebSocket(request, this);
        }catch(RuntimeException e){
            if(e.getMessage().contains("will fetch from authEndpoint")){
                logger.log("Defer connection: "+e.getMessage());
            }else{
                throw e;
            }
        }
    }

    public  void disconnect(){
        this.shouldReconnect = false;
        this.ws.close(NORMAL_CLOSURE_STATUS, null);
    }

    public  void reconnect(){
        if(this.shouldReconnect){
            this.connect(this.id);
        }
    }


    public void listen(String eventName, PieSocketEventListener callback){
        ArrayList<PieSocketEventListener> callbacks;

        if(this.listeners.containsKey(eventName)){
            callbacks = listeners.get(eventName);
        }else{
            callbacks = new ArrayList<>();
        }

        callbacks.add(callback);
        listeners.put(eventName, callbacks);
    }

    public void removeListener(String eventName, PieSocketEventListener callback){
        if(this.listeners.containsKey(eventName)){
            this.listeners.get(eventName).remove(callback);
        }
    }

    public void removeAllListeners(String eventName) {
        if (this.listeners.containsKey(eventName)) {
            this.listeners.remove(eventName);
        }
    }

    public void publish(PieSocketEvent event){
        this.ws.send(event.toString());
    }

    public void send(String text){
        this.ws.send(text);
    }

    private  void fireEvent(PieSocketEvent event){
        logger.log("Event: "+event.getEvent()+",  " +event.toString());

        if (this.listeners.containsKey(event.getEvent())) {
            doFireEvents(event.getEvent(), event);
        }

        if (this.listeners.containsKey("*")) {
            doFireEvents("*", event);
        }
    }

    private void doFireEvents(String listenerKey, PieSocketEvent event){
        ArrayList<PieSocketEventListener> callbacks = this.listeners.get(listenerKey);
        for(int i=0; i < callbacks.size(); i++){
            callbacks.get(i).handleEvent(event);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        PieSocketEvent event = new PieSocketEvent();
        event.setEvent("system:connected");
        this.fireEvent(event);

        this.shouldReconnect = true;
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {

        PieSocketEvent payload = new PieSocketEvent();

        if (this.listeners.containsKey("system:message")) {
            payload.setEvent("system:message");
            payload.setData(text);


            doFireEvents("system:message", payload);
        }

        try {
            JSONObject obj = new JSONObject(text);
            if(obj.has ("event")){
                String event = obj.getString("event");

                //Prepare payload
                payload.setEvent(event);

                if(obj.has("data")){
                    payload.setData(obj.getString("data"));
                }
                if(obj.has("meta")){
                    payload.setMeta(obj.getString("meta"));
                }


                //Trigger listeners
                this.handleSystemEvents(payload);
                this.fireEvent(payload);
            }
            if(obj.has("error")){
                this.shouldReconnect = false;

                payload.setEvent("system:error");
                payload.setData(obj.getString("error"));
                this.fireEvent(payload);
            }
        } catch (Throwable tx) {
            //Ignore error
        }

    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null);

        PieSocketEvent event = new PieSocketEvent();
        event.setEvent("system:closed");
        event.setData(reason);
        this.fireEvent(event);

        this.reconnect();
    }



    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        logger.log("Connection closed");
        PieSocketEvent event = new PieSocketEvent();
        event.setEvent("system:error");
        event.setData(t.getMessage());
        this.fireEvent(event);

        this.reconnect();
    }



    public JSONObject getMemberByUUID(String uuid){
        for (int i =0 ; i < this.members.length(); i++){
            try{
                JSONObject member = this.members.getJSONObject(i);
                if(member.getString("uuid").equals(uuid)){
                    return member;
                }

            }catch (Exception e){
                //Ignore errors, member can be a string and JSONException is possible
            }
        }

        return null;
    }

    public JSONObject getCurrentMember(){
        return this.getMemberByUUID(this.uuid);
    }

    public JSONArray getAllMembers(){
        return this.members;
    }

    public void handleSystemEvents(PieSocketEvent event){
        try{

            //Update members list
            if(
                    event.getEvent().equals("system:member_list") ||
                    event.getEvent().equals("system:member_joined") ||
                    event.getEvent().equals("system:member_left")
            ) {
                JSONObject data = new JSONObject(event.getData());
                this.members = data.getJSONArray("members");
            }


        }catch (Exception e){
            throw new PieSocketException(e.getMessage());
        }
    }

    @Override
    public void onFailure(Call call, IOException e) {
        throw  new PieSocketException("Auth Token Server Error"+ e.getMessage());
    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {
        String responseText = response.body().string();

        this.logger.log("Auth Token Server Response: "+ responseText);
        try{

            JSONObject serverResponse = new JSONObject(responseText);
            String jwt = serverResponse.getString("auth");

            if(jwt != null){
                this.logger.log("Auth token fetched, resuming connection");
                this.options.setJwt(jwt);
                this.connect(this.id);
            }

        }catch (Exception e){
            throw  new PieSocketException("Auth Token Response Parsing Error: "+ e.getMessage());
        }
    }
}
