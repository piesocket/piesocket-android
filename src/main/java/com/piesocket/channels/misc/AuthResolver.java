package com.piesocket.channels.misc;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Resolves the JWT for a channel — the configured token, or one fetched from
 * the auth endpoint for guarded ({@code private-} / {@code forceAuth})
 * channels.
 *
 * <p>{@link com.piesocket.channels.Channel}'s own standalone {@code connect()}
 * has an older, exception-based version of this same resolution (throw-to-defer,
 * reconnect once the token arrives) that predates this class and is left
 * untouched. This is the callback-based version used by v4's
 * {@link com.piesocket.channels.Connection} — {@code PieSocket.join()} must stay
 * synchronous even while an auth fetch is in flight.
 */
public class AuthResolver {

    /** Called with the token, or {@code null} if this channel needs none. */
    public interface OnReady {
        void call(String jwt);
    }

    public interface OnError {
        void call(Exception error);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static boolean isGuarded(String channelId, PieSocketOptions options) {
        if (Boolean.TRUE.equals(options.getForceAuth())) {
            return true;
        }
        return channelId.startsWith("private-");
    }

    public static void resolve(
            String channelId,
            String connectionUuid,
            PieSocketOptions options,
            Logger logger,
            OnReady onReady,
            OnError onError) {

        if (options.getJwt() != null && !options.getJwt().isEmpty()) {
            onReady.call(options.getJwt());
            return;
        }

        if (!isGuarded(channelId, options)) {
            onReady.call(null);
            return;
        }

        if (options.getAuthEndpoint() == null || options.getAuthEndpoint().isEmpty()) {
            onError.call(new PieSocketException(
                    "Neither JWT, nor authEndpoint is provided for private channel authentication."));
            return;
        }

        logger.log("Defer connection: fetching token from authEndpoint");
        fetchFromServer(channelId, connectionUuid, options, logger, onReady, onError);
    }

    private static void fetchFromServer(
            String channelId,
            String connectionUuid,
            PieSocketOptions options,
            Logger logger,
            OnReady onReady,
            OnError onError) {

        JSONObject body = new JSONObject();
        try {
            body.put("channel_name", channelId);
            body.put("connection_uuid", connectionUuid);
        } catch (Exception e) {
            onError.call(new PieSocketException("Auth request body error: " + e.getMessage()));
            return;
        }

        Request.Builder rb = new Request.Builder()
                .url(options.getAuthEndpoint())
                .post(RequestBody.create(JSON, body.toString()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        Map<String, String> headers = options.getAuthHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                rb.header(entry.getKey(), entry.getValue());
            }
        }

        new OkHttpClient().newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                onError.call(new PieSocketException("Auth Token Server Error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String text = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(text);
                    String jwt = json.isNull("auth") ? null : json.optString("auth", null);
                    if (jwt == null || jwt.isEmpty()) {
                        onError.call(new PieSocketException("Auth endpoint did not return a token"));
                        return;
                    }
                    // Deliberately not persisted onto `options` — it is shared by
                    // every channel on this PieSocket, and this token is scoped
                    // to `channelId`.
                    logger.log("Auth token fetched, resuming connection");
                    onReady.call(jwt);
                } catch (Exception e) {
                    onError.call(new PieSocketException(
                            "Auth Token Response Parsing Error: " + e.getMessage()));
                }
            }
        });
    }
}
