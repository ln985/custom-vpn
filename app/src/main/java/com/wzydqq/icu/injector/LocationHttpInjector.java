package com.wzydqq.icu.injector;

import android.content.Context;
import android.util.Log;

import com.github.megatronking.netbare.NetBareUtils;
import com.github.megatronking.netbare.http.HttpBody;
import com.github.megatronking.netbare.http.HttpRequest;
import com.github.megatronking.netbare.http.HttpRequestHeaderPart;
import com.github.megatronking.netbare.http.HttpResponse;
import com.github.megatronking.netbare.http.HttpResponseHeaderPart;
import com.github.megatronking.netbare.injector.InjectorCallback;
import com.github.megatronking.netbare.injector.SimpleHttpInjector;
import com.github.megatronking.netbare.io.HttpBodyInputStream;
import com.github.megatronking.netbare.stream.ByteStream;
import com.wzydqq.icu.location.LocationStore;
import com.wzydqq.icu.location.SelectedLocation;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 位置伪装 HTTP 注入器
 * 拦截腾讯地图地理编码 API，修改请求和响应中的位置数据
 */
public class LocationHttpInjector extends SimpleHttpInjector {

    private static final String TAG = "LocationInjector";
    private static final String TARGET_HOST = "apis.map.qq.com";
    private static final String TARGET_PATH = "/ws/geocoder/v1";

    private final Context context;
    private boolean shouldIntercept = false;
    private HttpResponseHeaderPart mHoldResponseHeader;

    public LocationHttpInjector(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean sniffRequest(HttpRequest request) {
        String host = request.host();
        String url = request.url();
        shouldIntercept = host != null && host.contains(TARGET_HOST)
                && url != null && url.contains(TARGET_PATH);
        if (shouldIntercept) {
            Log.d(TAG, "Sniffed target request: " + url);
        }
        return shouldIntercept;
    }

    @Override
    public boolean sniffResponse(HttpResponse response) {
        return shouldIntercept;
    }

    @Override
    public void onRequestInject(HttpRequestHeaderPart header,
                                InjectorCallback callback) throws IOException {
        SelectedLocation location = LocationStore.get(context);
        if (location == null) {
            callback.onFinished(header);
            return;
        }

        // 修改请求 URL 中的 location 参数
        String url = header.uri().toString();
        if (url != null && url.contains("location=")) {
            String newLocation = location.getLat() + "," + location.getLng();
            url = url.replaceAll("location=[^&\\s]+", "location=" + newLocation);
            HttpRequestHeaderPart newHeader = header.newBuilder()
                    .uri(android.net.Uri.parse(url))
                    .build();
            callback.onFinished(newHeader);
        } else {
            callback.onFinished(header);
        }
    }

    @Override
    public void onResponseInject(HttpResponseHeaderPart header,
                                 InjectorCallback callback) throws IOException {
        // Hold the header for later modification (content-length may change)
        mHoldResponseHeader = header;
    }

    @Override
    public void onRequestInject(HttpRequest request, HttpBody body,
                                InjectorCallback callback) throws IOException {
        callback.onFinished(body);
    }

    @Override
    public void onResponseInject(HttpResponse response, HttpBody body,
                                 InjectorCallback callback) throws IOException {
        if (mHoldResponseHeader == null) {
            callback.onFinished(body);
            return;
        }

        SelectedLocation location = LocationStore.get(context);
        if (location == null) {
            callback.onFinished(mHoldResponseHeader);
            callback.onFinished(body);
            mHoldResponseHeader = null;
            return;
        }

        HttpBodyInputStream his = null;
        Reader reader = null;
        ByteArrayOutputStream bos = null;
        GZIPOutputStream gzos = null;

        try {
            // 检查是否 gzip 压缩
            String encoding = mHoldResponseHeader.header("Content-Encoding");
            boolean isGzip = encoding != null && encoding.toLowerCase().contains("gzip");

            his = new HttpBodyInputStream(body);

            String bodyStr;
            if (isGzip) {
                reader = new InputStreamReader(new GZIPInputStream(his), "UTF-8");
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int len;
                while ((len = reader.read(buf)) > 0) {
                    sb.append(buf, 0, len);
                }
                bodyStr = sb.toString();
            } else {
                reader = new InputStreamReader(his, "UTF-8");
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int len;
                while ((len = reader.read(buf)) > 0) {
                    sb.append(buf, 0, len);
                }
                bodyStr = sb.toString();
            }

            // 修改 JSON 响应中的位置数据
            String modified = modifyResponseBody(bodyStr, location);
            if (modified != null) {
                byte[] modifiedBytes = modified.getBytes("UTF-8");

                if (isGzip) {
                    bos = new ByteArrayOutputStream();
                    gzos = new GZIPOutputStream(bos);
                    gzos.write(modifiedBytes);
                    gzos.finish();
                    gzos.flush();
                    modifiedBytes = bos.toByteArray();
                }

                // 更新 header 的 content-length
                HttpResponseHeaderPart newHeader = mHoldResponseHeader.newBuilder()
                        .replaceHeader("Content-Length", String.valueOf(modifiedBytes.length))
                        .build();

                // 先发射修改后的 header
                callback.onFinished(newHeader);
                // 再发射修改后的响应体
                callback.onFinished(new ByteStream(modifiedBytes));
                Log.d(TAG, "Response body modified successfully");
            } else {
                // 没有修改，原样返回
                callback.onFinished(mHoldResponseHeader);
                callback.onFinished(body);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to modify response: " + e.getMessage());
            callback.onFinished(mHoldResponseHeader);
            callback.onFinished(body);
        } finally {
            NetBareUtils.closeQuietly(his);
            NetBareUtils.closeQuietly(reader);
            NetBareUtils.closeQuietly(gzos);
            NetBareUtils.closeQuietly(bos);
            mHoldResponseHeader = null;
        }
    }

    private String modifyResponseBody(String bodyStr, SelectedLocation location) {
        try {
            JSONObject json = new JSONObject(bodyStr);
            JSONObject result = json.optJSONObject("result");
            if (result == null) return null;

            // 修改 address_component
            JSONObject addressComponent = result.optJSONObject("address_component");
            if (addressComponent != null) {
                if (location.getProvinceName() != null && !location.getProvinceName().isEmpty())
                    addressComponent.put("province", location.getProvinceName());
                if (location.getCityName() != null && !location.getCityName().isEmpty())
                    addressComponent.put("city", location.getCityName());
                if (location.getDistrictName() != null && !location.getDistrictName().isEmpty())
                    addressComponent.put("district", location.getDistrictName());
            }

            // 修改 ad_info
            JSONObject adInfo = result.optJSONObject("ad_info");
            if (adInfo != null) {
                adInfo.put("adcode", String.valueOf(location.getAdcode()));
                if (location.getProvinceName() != null && !location.getProvinceName().isEmpty())
                    adInfo.put("province", location.getProvinceName());
                if (location.getCityName() != null && !location.getCityName().isEmpty())
                    adInfo.put("city", location.getCityName());
                if (location.getDistrictName() != null && !location.getDistrictName().isEmpty())
                    adInfo.put("district", location.getDistrictName());
            }

            // 修改 location
            JSONObject locationObj = result.optJSONObject("location");
            if (locationObj != null) {
                locationObj.put("lat", location.getLat());
                locationObj.put("lng", location.getLng());
            }

            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "JSON modification failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onRequestFinished(HttpRequest request) {
        shouldIntercept = false;
    }

    @Override
    public void onResponseFinished(HttpResponse response) {
        mHoldResponseHeader = null;
    }
}
