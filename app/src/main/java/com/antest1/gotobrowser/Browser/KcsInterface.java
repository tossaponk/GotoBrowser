package com.antest1.gotobrowser.Browser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.TextView;

import com.antest1.gotobrowser.ContentProvider.KcaPacketStore;
import com.antest1.gotobrowser.Helpers.KcUtils;
import com.antest1.gotobrowser.R;
import com.antest1.gotobrowser.Subtitle.KcSubtitleUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

import static com.antest1.gotobrowser.Constants.PREF_BROADCAST;
import static com.antest1.gotobrowser.ContentProvider.KcaContentProvider.BROADCAST_ACTION;
import static com.antest1.gotobrowser.ContentProvider.KcaPacketStore.PACKETSTORE_VERSION;

public class KcsInterface {
    public static final String GOTO_ANDROID = "GotoBrowser";
    public static final String AXIOS_INTERCEPT_SCRIPT = "axios.interceptors.response.use(function(response){if(response.config.url.includes(\"kcsapi\")){var url=response.config.url;var request=response.config.data;var data=response.data;GotoBrowser.kcs_xhr_intercept(url,request,data);}return response;},function(error){return Promise.reject(error);});";
    private Activity activity;
    private KcaPacketStore packetTable;
    private final Handler handler = new Handler();
    private boolean broadcast_mode = false;

    public KcsInterface(Activity ac) {
        activity = ac;
        packetTable = new KcaPacketStore(ac.getApplicationContext(), null, PACKETSTORE_VERSION);
        SharedPreferences sharedPref = activity.getSharedPreferences(
                activity.getString(R.string.preference_key), Context.MODE_PRIVATE);
        broadcast_mode = sharedPref.getBoolean(PREF_BROADCAST, false);
    }

    @JavascriptInterface
    public void kcs_xhr_intercept(String url, String requestHeader, String responseRaw) {
        if (url.contains("kcsapi") && responseRaw.contains("svdata=")) {
            Log.e("GOTO-XHR", url);
            handler.post(() -> processXHR(url, requestHeader, responseRaw));
        };
    }

    @JavascriptInterface
    public void kcs_axios_error(String error) {
        activity.runOnUiThread(() -> {
            String text = String.format(Locale.US, "[Error] %s", error);
            ((TextView) activity.findViewById(R.id.kc_error_text)).setText(text);
        });
    }

    public void processXHR(String url, String request, String response) {
        try {
            response = response.replace("svdata=", "");
            Log.e("GOTO", "response: " + url);
            JsonObject response_obj = new JsonParser().parse(response).getAsJsonObject();
            if (url.contains("api_start2")) {
                JsonObject api_data = response_obj.getAsJsonObject("api_data");
                JsonArray api_mst_shipgraph = api_data.getAsJsonArray("api_mst_shipgraph");
                JsonArray api_mst_ship = api_data.getAsJsonArray("api_mst_ship");
                JsonArray api_mst_mapbgm = api_data.getAsJsonArray("api_mst_mapbgm");
                KcSubtitleUtils.buildShipGraph(api_mst_ship);
                KcSubtitleUtils.buildMapBgmGraph(api_mst_mapbgm);
                for (JsonElement item : api_mst_shipgraph) {
                    JsonObject ship = item.getAsJsonObject();
                    String shipId = ship.get("api_id").getAsString();
                    String shipFn = ship.get("api_filename").getAsString();
                    KcSubtitleUtils.filenameToShipId.put(shipFn, shipId);
                }
                Log.e("GOTO", "filenameToShipId: " + KcSubtitleUtils.filenameToShipId.size());
            }

            String finalResponse = response;
            activity.runOnUiThread(() -> {
                int result = response_obj.get("api_result").getAsInt();
                String message = response_obj.get("api_result_msg").getAsString();
                String text = (result == 1) ? "" : String.format(Locale.US, "[%d] %s\n%s (%d)", result, message, url, finalResponse.length());
                ((TextView) activity.findViewById(R.id.kc_error_text)).setText(text);
            });
        } catch (Exception e) {
            e.printStackTrace();
            KcUtils.reportException(e);
        }
        if (broadcast_mode) sendBroadcast(url, request, response);
    }

    public void sendBroadcast(String url, String request, String response) {
        url = url.replace("/kcsapi", "");
        packetTable.record(url, request, response);
        Intent intent = new Intent();
        intent.setAction(BROADCAST_ACTION);

        Bundle bundle = new Bundle();
        bundle.putString("url", url);
        bundle.putString("request", request);
        byte[] responseBytes;
        responseBytes = response.getBytes();
        if (responseBytes.length > 150000) {
            try {
                responseBytes = KcUtils.gzipcompress(response);
                bundle.putBoolean("gzipped", true);
            } catch (Exception e) {
                // do nothing
                bundle.putBoolean("gzipped", false);
            }
        } else {
            bundle.putBoolean("gzipped", false);
        }
        bundle.putByteArray("response", responseBytes);
        intent.putExtras(bundle);

        // Make debug build works with kcanotify passive mode by removing debug suffix
        String act = intent.getAction();
        act = act.replace( ".debug", "" );
        intent.setAction( act );
        Log.e("GOTO", "broadcast sent: " + url);
        activity.sendBroadcast(intent);
    }
}
