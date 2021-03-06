package com.example.ble_guitar_tutor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.gun0912.tedpermission.PermissionListener;
import com.gun0912.tedpermission.TedPermission;

import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "MainActivityTag";
    private static final int REQUEST_ENABLE_BT = 5005;

    private BluetoothAdapter bluetoothAdapter;
    protected BleManager bleManager;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkBluetoothActive();

        Button button1 = findViewById(R.id.main_ble_send_button1);
        Button button2 = findViewById(R.id.main_ble_send_button2);
        Button button3 = findViewById(R.id.main_ble_send_button3);
        Button button4 = findViewById(R.id.main_ble_send_button4);
        Button button5 = findViewById(R.id.main_ble_send_button5);

        button1.setOnClickListener(this);
        button2.setOnClickListener(this);
        button3.setOnClickListener(this);
        button4.setOnClickListener(this);
        button5.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.main_ble_send_button1 :
                bleManager.writeCharacteristic(getResources().getString(R.string.test_string_1));
                break;
            case R.id.main_ble_send_button2 :
                bleManager.writeCharacteristic(getResources().getString(R.string.test_string_2));
                break;
            case R.id.main_ble_send_button3 :
                bleManager.writeCharacteristic(getResources().getString(R.string.test_string_3));
                break;
            case R.id.main_ble_send_button4 :
                bleManager.writeCharacteristic(getResources().getString(R.string.test_string_4));
                break;
            case R.id.main_ble_send_button5 :
                bleManager.writeCharacteristic(getResources().getString(R.string.test_string_5));
                break;
        }
    }

    /* ******************** BLE 권한 승인 관련 코드 ******************** */
    private void checkBluetoothActive() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            Toast.makeText(this, "블루투스 기능을 지원하지 않습니다.", Toast.LENGTH_SHORT).show();
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            Log.d(TAG, "Requested user enables Bluetooth. Try starting the scan again.");
        } else {
            checkPermission();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ENABLE_BT:
                if (resultCode == RESULT_OK) {
                    checkPermission();
                } else {
                    Toast.makeText(this, "블루투스를 활성화 해주세요.", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
        }
    }

    private void checkPermission() {
        TedPermission.with(this)
                .setPermissionListener(new PermissionListener() {
                    @Override
                    public void onPermissionGranted() {
                        handler = new Handler();
                        bleManager = new BleManager(MainActivity.this, handler, bluetoothAdapter);
                    }

                    @Override
                    public void onPermissionDenied(List<String> deniedPermissions) {
                        Toast.makeText(MainActivity.this, "Permission Denied\n" + deniedPermissions.toString(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setDeniedMessage(R.string.ble_denied_info)
                .setPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
                .check();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_ble_scan:
                if (bleManager != null) {
                    bleManager.startScan();
                } else {
                    Toast.makeText(this, "블루투스 권한이 없습니다.", Toast.LENGTH_SHORT).show();
                }
                break;
        }
        return super.onOptionsItemSelected(item);
    }
}