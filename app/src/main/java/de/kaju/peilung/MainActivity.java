package de.kaju.peilung;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/web/index.html";
    private static final int REQ_PERMS = 41;

    private WebView web;
    private WebViewAssetLoader assets;

    private PermissionRequest pendingMedia;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private float insetTop = 0f;
    private float insetBottom = 0f;

    /* Sensoren direkt von Android: Lage im Raum (Rotationsvektor) mit Kursgenauigkeit */
    private SensorManager sensors;
    private Sensor rotSensor, magSensor;
    private final float[] rotMat = new float[9];
    private final float[] rotVec = new float[4];
    private long lastOriPush = 0;
    private int magAccuracy = -1;

    /* GPS direkt, ohne Umweg über den Browser */
    private LocationManager locMgr;
    private LocationListener locListener;
    private GnssStatus.Callback gnssCallback;
    private int satsUsed = -1, satsSeen = -1;
    private boolean locRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        assets = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E1113"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        web.setOnApplyWindowInsetsListener((v, insets) -> {
            float d = getResources().getDisplayMetrics().density;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetTop = i.top / d;
                insetBottom = i.bottom / d;
            } else {
                insetTop = insets.getSystemWindowInsetTop() / d;
                insetBottom = insets.getSystemWindowInsetBottom() / d;
            }
            pushInsets();
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " Peilung/1.0 (de.kaju.peilung)");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternalUri(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsVideo = true;
                    }
                    if (!wantsVideo) {
                        request.deny();
                        return;
                    }
                    if (has(Manifest.permission.CAMERA)) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingMedia = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMS);
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "Native");

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            rotSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            magSensor = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        locMgr = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        askStartPermissions();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void askStartPermissions() {
        List<String> need = new ArrayList<>();
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;

        if (pendingMedia != null) {
            if (has(Manifest.permission.CAMERA)) {
                pendingMedia.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingMedia.deny();
            }
            pendingMedia = null;
        }
        if (pendingGeoCallback != null) {
            boolean ok = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
            pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
        startLocation();
        if (web != null) {
            web.evaluateJavascript("window.onNativePermissions && window.onNativePermissions()", null);
        }
    }

    /* ---------- Sensoren ---------- */
    private void startSensors() {
        if (sensors == null) return;
        if (rotSensor != null) sensors.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME);
        if (magSensor != null) sensors.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopSensors() {
        if (sensors != null) sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR || web == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastOriPush < 30) return;
        lastOriPush = now;
        int n = Math.min(4, e.values.length);
        for (int i = 0; i < 4; i++) rotVec[i] = i < n ? e.values[i] : 0f;
        if (n < 4) {
            float w = 1f - rotVec[0] * rotVec[0] - rotVec[1] * rotVec[1] - rotVec[2] * rotVec[2];
            rotVec[3] = w > 0 ? (float) Math.sqrt(w) : 0f;
        }
        SensorManager.getRotationMatrixFromVector(rotMat, rotVec);
        float headAcc = e.values.length > 4 && e.values[4] >= 0 ? (float) Math.toDegrees(e.values[4]) : -1f;
        // Rückkamera = negative Z-Achse des Geräts, Oberkante = Y-Achse; Welt: Ost, Nord, Oben
        String js = String.format(Locale.US,
                "window.onNativeOri&&window.onNativeOri(%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.2f,%d)",
                -rotMat[2], -rotMat[5], -rotMat[8], rotMat[1], rotMat[4], rotMat[7], headAcc, magAccuracy);
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy;
    }

    /* ---------- GPS ---------- */
    private void startLocation() {
        if (locRunning || locMgr == null) return;
        boolean fine = has(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!fine && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return;
        if (locListener == null) {
            locListener = new LocationListener() {
                @Override public void onLocationChanged(Location l) { pushLocation(l); }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
                @SuppressWarnings("deprecation")
                @Override public void onStatusChanged(String p, int s, Bundle b) { }
            };
        }
        try {
            if (fine && locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locListener, Looper.getMainLooper());
                Location last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null && System.currentTimeMillis() - last.getTime() < 60000) pushLocation(last);
            }
            if (locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locMgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locListener, Looper.getMainLooper());
            }
            if (fine) {
                if (gnssCallback == null) {
                    gnssCallback = new GnssStatus.Callback() {
                        @Override
                        public void onSatelliteStatusChanged(GnssStatus st) {
                            int used = 0, n = st.getSatelliteCount();
                            for (int i = 0; i < n; i++) if (st.usedInFix(i)) used++;
                            satsUsed = used;
                            satsSeen = n;
                        }
                    };
                }
                locMgr.registerGnssStatusCallback(gnssCallback, new Handler(Looper.getMainLooper()));
            }
            locRunning = true;
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private void stopLocation() {
        if (!locRunning || locMgr == null) return;
        try {
            locMgr.removeUpdates(locListener);
            if (gnssCallback != null) locMgr.unregisterGnssStatusCallback(gnssCallback);
        } catch (SecurityException ignored) {
        }
        locRunning = false;
    }

    private void pushLocation(Location l) {
        if (web == null || l == null) return;
        String js = String.format(Locale.US,
                "window.onNativeLoc&&window.onNativeLoc(%.8f,%.8f,%.2f,%.2f,%.2f,'%s',%d,%d,%d)",
                l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 999f,
                l.hasAltitude() ? l.getAltitude() : 0d, l.hasSpeed() ? l.getSpeed() : -1f,
                LocationManager.GPS_PROVIDER.equals(l.getProvider()) ? "gps" : "net",
                l.getTime(), satsUsed, satsSeen);
        web.evaluateJavascript(js, null);
    }

    private void pushInsets() {
        if (web == null) return;
        String js = String.format(Locale.US,
                "window.setInsets && window.setInsets(%.1f,%.1f)", insetTop, insetBottom);
        web.evaluateJavascript(js, null);
    }

    private void openExternalUri(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.handleBack ? window.handleBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSensors();
        stopLocation();
        if (web != null) {
            web.evaluateJavascript("window.onAppPause && window.onAppPause()", null);
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSensors();
        startLocation();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Wird im JavaScript als window.Native angesprochen. */
    private class Bridge {

        /** Magnetische Missweisung in Grad (Ost positiv) – exakt nach World Magnetic Model. */
        @JavascriptInterface
        public double declination(double lat, double lon, double altMeters) {
            GeomagneticField f = new GeomagneticField(
                    (float) lat, (float) lon, (float) altMeters, System.currentTimeMillis());
            return f.getDeclination();
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> openExternalUri(Uri.parse(url)));
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(send, "Teilen"));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Peilung", text));
            });
        }

        @JavascriptInterface
        public void haptic() {
            runOnUiThread(() -> {
                if (web != null) {
                    int c = Build.VERSION.SDK_INT >= 30
                            ? HapticFeedbackConstants.CONFIRM
                            : HapticFeedbackConstants.VIRTUAL_KEY;
                    web.performHapticFeedback(c);
                }
            });
        }

        @JavascriptInterface
        public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        @JavascriptInterface
        public boolean hasNativeSensors() {
            return rotSensor != null;
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
