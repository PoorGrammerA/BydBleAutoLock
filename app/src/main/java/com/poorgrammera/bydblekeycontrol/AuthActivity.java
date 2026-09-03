package com.poorgrammera.bydblekeycontrol;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.poorgrammera.bydautolock.bydapi.BydConfig;
import com.poorgrammera.bydautolock.bydapi.BydCountry;
import com.poorgrammera.bydautolock.bydapi.BydCountryRepository;
import com.poorgrammera.bydautolock.bydapi.BydWatchKeyService;
import com.poorgrammera.bydautolock.bydapi.WatchCredentialManager;
import com.poorgrammera.bydautolock.model.QrCodeInfo;
import com.poorgrammera.bydautolock.model.QrCodeState;
import com.poorgrammera.bydautolock.storage.StorageManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.io.OutputStream;

/** Entry point: requests Bluetooth permissions and binds this device with the BYD QR flow. */
public class AuthActivity extends AppCompatActivity {
    private static final String TAG = "AuthActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 2001;
    private static final long QR_POLL_MS = 2000L;
    private static final String BYD_AUTO_LINK_PACKAGE = "com.byd.bydautolink";
    private final List<BydCountry> countryOptions = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StorageManager storage;
    private BydWatchKeyService watchService;
    private ImageView qrImage;
    private TextView status;
    private AutoCompleteTextView regionDropdown;
    private TextView regionDomain;
    private View saveQrAndOpenBydButton;
    private View devTestButton;
    private String qrUuid;
    private Bitmap authQrBitmap;
    private boolean waitingForQr;
    private boolean provisioning;
    private int developerTitleTapCount;

    private final Runnable pollQr = new Runnable() {
        @Override public void run() {
            if (!waitingForQr || qrUuid == null) return;
            watchService.getQrCodeStatus(qrUuid, new BydWatchKeyService.Callback<QrCodeState>() {
                @Override public void onSuccess(QrCodeState result) {
                    String codeStatus = result == null ? null : result.getCodeStatus();
                    runOnUiThread(() -> {
                        if (!waitingForQr) return;
                        if ("2".equals(codeStatus)) {
                            waitingForQr = false;
                            status.setText(R.string.auth_complete_storing_key);
                            provisionBluetoothKey();
                        } else {
                            status.setText(getString(R.string.auth_scan_qr_status,
                                    codeStatus == null ? getString(R.string.auth_checking_status) : codeStatus));
                            handler.postDelayed(pollQr, QR_POLL_MS);
                        }
                    });
                }
                @Override public void onError(String message, Throwable error) {
                    runOnUiThread(() -> {
                        if (!waitingForQr) return;
                        status.setText(R.string.auth_retrying_status);
                        handler.postDelayed(pollQr, QR_POLL_MS);
                    });
                }
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        qrImage = findViewById(R.id.qrImage);
        status = findViewById(R.id.authStatus);
        regionDropdown = findViewById(R.id.authRegionDropdown);
        regionDomain = findViewById(R.id.authRegionDomain);
        saveQrAndOpenBydButton = findViewById(R.id.saveQrAndOpenBydButton);
        devTestButton = findViewById(R.id.authDevTestButton);
        storage = new StorageManager(this);
        findViewById(R.id.retryAuthButton).setOnClickListener(v -> startQrBinding());
        saveQrAndOpenBydButton.setOnClickListener(v -> exportQrAndOpenBydApp());
        findViewById(R.id.authDeveloperModeTrigger).setOnClickListener(v -> enableDeveloperModeAfterFiveTaps());
        devTestButton.setOnClickListener(v -> startActivity(new Intent(this, DevTestActivity.class)));
        renderDeveloperMode();
        setupRegionSelector();
    }

    @Override protected void onResume() {
        super.onResume();
        if (storage != null && storage.hasBleKey()) {
            openVehicleControl();
        } else if (waitingForQr && qrUuid != null) {
            handler.removeCallbacks(pollQr);
            handler.post(pollQr);
        } else if (hasBluetoothPermissions()) {
            if (!waitingForQr && !provisioning) showQrReady();
        } else {
            requestBluetoothPermissions();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollQr);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private void requestBluetoothPermissions() {
        status.setText(R.string.auth_permission_explanation);
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                      @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return;
        if (hasBluetoothPermissions()) {
            showQrReady();
        } else {
            status.setText(R.string.auth_permission_required);
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show();
        }
    }

    private void startQrBinding() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (provisioning) return;
        deleteExportedQr();
        waitingForQr = false;
        handler.removeCallbacks(pollQr);
        qrImage.setVisibility(View.GONE);
        saveQrAndOpenBydButton.setVisibility(View.GONE);
        authQrBitmap = null;
        setRegionSelectionEnabled(false);
        ((TextView) findViewById(R.id.retryAuthButton)).setText(R.string.auth_create_qr);
        status.setText(R.string.auth_qr_creating);
        watchService.createQrCode(new BydWatchKeyService.Callback<QrCodeInfo>() {
            @Override public void onSuccess(QrCodeInfo result) {
                runOnUiThread(() -> {
                    if (result == null || result.getUuid() == null || result.getUuid().trim().isEmpty()) {
                        status.setText(R.string.auth_qr_create_failed);
                        setRegionSelectionEnabled(true);
                        return;
                    }
                    qrUuid = result.getUuid();
                    storage.setWatchQrUuid(qrUuid);
                    authQrBitmap = createQrBitmap(watchService.buildQrCodeContent(qrUuid, watchService.getWatchImei()));
                    if (authQrBitmap == null) {
                        status.setText(R.string.auth_qr_render_failed);
                        setRegionSelectionEnabled(true);
                        return;
                    }
                    qrImage.setImageBitmap(authQrBitmap);
                    qrImage.setVisibility(View.VISIBLE);
                    saveQrAndOpenBydButton.setVisibility(isBydAutoLinkInstalled() ? View.VISIBLE : View.GONE);
                    waitingForQr = true;
                    status.setText(R.string.auth_qr_scan_instruction);
                    handler.post(pollQr);
                });
            }
            @Override public void onError(String message, Throwable error) {
                runOnUiThread(() -> {
                    status.setText(getString(R.string.auth_qr_create_failed_detail, message));
                    setRegionSelectionEnabled(true);
                });
            }
        });
    }

    private void provisionBluetoothKey() {
        if (provisioning) return;
        provisioning = true;
        WatchCredentialManager.refreshFromSavedQr(this, new WatchCredentialManager.Callback() {
            @Override public void onSuccess(com.poorgrammera.bydautolock.model.TokenInfoBean token) {
                runOnUiThread(() -> {
                    provisioning = false;
                    deleteExportedQr();
                    status.setText(R.string.auth_key_saved);
                    VehicleAccessService.start(AuthActivity.this);
                    openVehicleControl();
                });
            }
            @Override public void onError(String message, Throwable error) {
                runOnUiThread(() -> {
                    provisioning = false;
                    status.setText(getString(R.string.auth_key_save_failed, message));
                    setRegionSelectionEnabled(true);
                });
            }
        });
    }

    private void setupRegionSelector() {
        countryOptions.clear();
        countryOptions.addAll(BydCountryRepository.getInstance().getCountries(this));

        ArrayAdapter<BydCountry> adapter = new ArrayAdapter<>(this, R.layout.item_region_dropdown, countryOptions);
        adapter.setDropDownViewResource(R.layout.item_region_dropdown);
        regionDropdown.setAdapter(adapter);

        String initialRegion = storage.hasRegion() ? supportedRegion(storage.getRegion()) : detectDeviceRegion();
        applyRegion(initialRegion, true);

        regionDropdown.setOnItemClickListener((parent, view, position, id) -> {
            if (waitingForQr || provisioning) {
                Toast.makeText(this, R.string.auth_region_change_locked, Toast.LENGTH_SHORT).show();
                return;
            }
            BydCountry selectedCountry = adapter.getItem(position);
            if (selectedCountry != null && selectedCountry.getDomain() != null) {
                applyRegion(selectedCountry.getDomain(), true);
                showQrReady();
            }
        });
    }

    private void applyRegion(String region, boolean persist) {
        String selected = supportedRegion(region);
        if (persist) storage.setRegion(selected);

        BydCountry country = BydCountryRepository.getInstance().findByDomain(this, selected);
        if (country != null) {
            regionDropdown.setText(country.getDisplayName(), false);
        } else if ("EU".equalsIgnoreCase(selected)) {
            regionDropdown.setText("Europe (EU)", false);
        } else {
            regionDropdown.setText(selected, false);
        }

        BydConfig config = BydConfig.fromRegion(selected);
        watchService = new BydWatchKeyService(this, config);
        regionDomain.setText(getString(R.string.auth_region_server, config.getBaseUrl()));
    }

    private void showQrReady() {
        if (waitingForQr || provisioning) return;
        qrImage.setVisibility(View.GONE);
        saveQrAndOpenBydButton.setVisibility(View.GONE);
        setRegionSelectionEnabled(true);
        ((TextView) findViewById(R.id.retryAuthButton)).setText(R.string.auth_create_qr_for_region);
        status.setText(R.string.auth_region_instruction);
    }

    private void setRegionSelectionEnabled(boolean enabled) {
        regionDropdown.setEnabled(enabled);
    }

    private void enableDeveloperModeAfterFiveTaps() {
        if (storage.isDeveloperModeEnabled()) return;
        developerTitleTapCount++;
        if (developerTitleTapCount < 5) return;
        storage.setDeveloperModeEnabled(true);
        renderDeveloperMode();
        Toast.makeText(this, R.string.developer_mode_enabled, Toast.LENGTH_LONG).show();
    }

    private void renderDeveloperMode() {
        devTestButton.setVisibility(storage.isDeveloperModeEnabled() ? View.VISIBLE : View.GONE);
    }

    /** Uses device country first, then language and timezone. GPS permission is intentionally not required. */
    private String detectDeviceRegion() {
        String rawCountry = Locale.getDefault().getCountry();
        String country = BydCountryRepository.normalizeCountryCode(rawCountry);
        if (country != null && BydCountryRepository.getInstance().findByDomain(this, country) != null) {
            return country;
        }
        if (rawCountry != null && isEuropeanCountry(rawCountry.toUpperCase(Locale.US))) return "EU";

        String language = Locale.getDefault().getLanguage().toLowerCase(Locale.US);
        if ("ko".equals(language)) return "KR";
        if ("ja".equals(language)) return "JP";
        if ("pt".equals(language)) return "BR";
        if ("es".equals(language)) return "MX";
        if ("no".equals(language)) return "NO";
        if ("id".equals(language) || "in".equals(language)) return "ID";
        if ("vi".equals(language)) return "VNM";
        if ("tr".equals(language)) return "TR";

        String zone = TimeZone.getDefault().getID();
        if (zone.startsWith("Asia/Seoul")) return "KR";
        if (zone.startsWith("Asia/Tokyo")) return "JP";
        if (zone.startsWith("Asia/Singapore")) return "SG";
        if (zone.startsWith("Australia/")) return "AU";
        if (zone.startsWith("America/Sao_Paulo")) return "BR";
        if (zone.startsWith("America/Mexico")) return "MX";
        if (zone.startsWith("Europe/")) return "EU";
        return "KR";
    }

    private boolean isEuropeanCountry(String country) {
        return "AT BE BG HR CY CZ DK EE FI FR DE GR HU IE IT LV LT LU MT NL PL PT RO SK SI ES SE"
                .contains(" " + country + " ");
    }

    private String supportedRegion(String region) {
        if (region == null || region.trim().isEmpty()) return "KR";
        String normalized = BydCountryRepository.normalizeCountryCode(region);
        if (BydCountryRepository.getInstance().findByDomain(this, normalized) != null) {
            return normalized;
        }
        if ("EU".equalsIgnoreCase(normalized)) {
            return "EU";
        }
        return "KR";
    }

    private void openVehicleControl() {
        VehicleAccessService.startIfEnabled(this);
        Intent intent = new Intent(this, VehicleControlActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private Bitmap createQrBitmap(String content) {
        try {
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 720, 720, hints);
            Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.RGB_565);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bitmap;
        } catch (Exception error) {
            Toast.makeText(this, R.string.auth_qr_render_failed, Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private boolean isBydAutoLinkInstalled() {
        try {
            getPackageManager().getPackageInfo(BYD_AUTO_LINK_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void exportQrAndOpenBydApp() {
        if (authQrBitmap == null) {
            Toast.makeText(this, R.string.auth_qr_export_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        Bitmap exportBitmap = createQrExportBitmap(timestamp);
        if (exportBitmap == null || !saveQrExport(exportBitmap, timestamp)) return;
        Toast.makeText(this, R.string.auth_qr_export_saved, Toast.LENGTH_SHORT).show();
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(BYD_AUTO_LINK_PACKAGE);
        if (launchIntent == null) {
            Toast.makeText(this, R.string.auth_byd_app_not_available, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(launchIntent);
    }

    /** Creates a shareable QR sheet: QR, localized scan path, app identity, and creation timestamp. */
    private Bitmap createQrExportBitmap(String timestamp) {
        final int width = 1080;
        final int height = 1600;
        final int qrSize = 864;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint qrPaint = new Paint();
        qrPaint.setFilterBitmap(false);
        int qrLeft = (width - qrSize) / 2;
        canvas.drawBitmap(authQrBitmap, null, new Rect(qrLeft, 180, qrLeft + qrSize, 180 + qrSize), qrPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(42f);
        canvas.drawText(getString(R.string.auth_qr_export_instruction), width / 2f, 1165f, textPaint);

        String appName = getString(R.string.app_name);
        textPaint.setTextSize(48f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        float iconSize = 96f;
        float gap = 24f;
        float groupWidth = iconSize + gap + textPaint.measureText(appName);
        float groupLeft = (width - groupWidth) / 2f;
        Drawable appIcon = getApplicationInfo().loadIcon(getPackageManager());
        appIcon.setBounds((int) groupLeft, 1230, (int) (groupLeft + iconSize), 1326);
        appIcon.draw(canvas);
        canvas.drawText(appName, groupLeft + iconSize + gap, 1300f, textPaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(26f);
        canvas.drawText(timestamp, width / 2f, 1530f, textPaint);
        return bitmap;
    }

    private boolean saveQrExport(Bitmap bitmap, String timestamp) {
        deleteExportedQr();
        String fileStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "BLE_AutoLock_QR_" + fileStamp + ".png");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Toast.makeText(this, R.string.auth_qr_export_failed, Toast.LENGTH_LONG).show();
            return false;
        }
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Could not write QR image");
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            storage.setExportedQrUri(uri.toString());
            return true;
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            Toast.makeText(this, R.string.auth_qr_export_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Removes the temporary QR sheet after successful provisioning or before replacing it. */
    private void deleteExportedQr() {
        if (storage == null) return;
        String storedUri = storage.getExportedQrUri();
        if (storedUri == null || storedUri.trim().isEmpty()) return;
        try {
            getContentResolver().delete(Uri.parse(storedUri), null, null);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to delete the exported authentication QR", error);
        } finally {
            storage.setExportedQrUri(null);
        }
    }
}
