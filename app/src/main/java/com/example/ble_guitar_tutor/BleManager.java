package com.example.ble_guitar_tutor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Bluetooth Low Energy 기기 스캔 및 통신을 보조하는 객체
 */
public class BleManager {
    /* Constant */
    private final String TAG = "BleManager";
    private static final long SCAN_PERIOD = 1000;  // 스캔 제한 시간
    private static final UUID SERVICE_UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214");
    private static final UUID DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /* BLE Scan Field */
    private BluetoothAdapter mBluetoothAdapter;
    private Handler mHandler;
    private Activity mActivity;
    private boolean mScanning = false;
    private HashMap<String, BluetoothDevice> mScanResults;
    private BleScanCallback mScanCallback;
    private BluetoothLeScanner mBluetoothScanner;
    private View mProgressView;

    /* BLE Connect Field*/
    private BluetoothGatt mGatt;
    private boolean mConnected = false;
    private boolean mInitialized = false;

    /* Callback to transfer BLE Characteristic data */
    private Deliverable deliverable = null;

    public interface Deliverable {
        void onReceiveCharacteristicChanged(String data);
    }

    public void setDeliverable(Deliverable deliverable) {
        this.deliverable = deliverable;
    }
    public boolean ismConnected() {
        return mConnected;
    }

    /**
     * BLE Manager 생성자
     * 1. 현재 안드로이드 기기가 BLE 지원이 되는지 확인
     * 2. BluetoothAdapter 획득
     */
    public BleManager(Activity activity, Handler handler, BluetoothAdapter bluetoothAdapter, View progressView) {
        mActivity = activity;
        mHandler = handler;
        mBluetoothAdapter = bluetoothAdapter;
        mProgressView = progressView;

        Log.d(TAG, "Created BleManager");
    }

    /**
     * BLE 기기를 스캔하는 메소드
     * 1. Bluetooth 권한 획득 : hasPermission()
     * 2. 스캔
     * 3. SCAN_PERIOD 후에 stopScan() 호출
     */
    public void startScan() {
        if (mScanning) {
            return;
        }
        mProgressView.setVisibility(View.VISIBLE);

        Log.d(TAG, "Scan Start");

        List<ScanFilter> filters = new ArrayList<>();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        mScanResults = new HashMap<>();
        mScanCallback = new BleScanCallback();
        mBluetoothScanner = mBluetoothAdapter.getBluetoothLeScanner();
        mBluetoothScanner.startScan(filters, settings, mScanCallback);
        mScanning = true;
        mHandler.postDelayed(this::stopScan, SCAN_PERIOD);
    }

    /**
     * 스캔을 종료하고 Scan 에 사용된 필드값들을 초기화
     */
    private void stopScan() {
        Log.d(TAG, "Scan Stop");
        mProgressView.setVisibility(View.GONE);

        if (mScanning && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mBluetoothScanner != null) {
            mBluetoothScanner.stopScan(mScanCallback);
            scanComplete();
        }

        mScanCallback = null;
        mScanning = false;
    }

    /**
     * 스캔이 끝난 후 검색된 BluetoothDevice 를 처리하는 메소드
     */
    private void scanComplete() {
        Log.d(TAG, "Scan Complete");
        if (mScanResults.isEmpty()) {
            Toast.makeText(mActivity, "No BLE Device", Toast.LENGTH_SHORT).show();
            return;
        }

        for (String deviceAddress : mScanResults.keySet()) {
            Log.d(TAG, "Found Device Address : " + deviceAddress);
        }

        showScanResults(new ArrayList<>(mScanResults.values()));
    }

    /**
     * BLE Scan 중에 호출되는 콜백 메소드 구현
     */
    private class BleScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            addScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                addScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE Scan Failed with code : " + errorCode);
        }

        private void addScanResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            mScanResults.put(deviceAddress, device);
        }
    }

    /**
     * BLE 기기와 연결하는 메소드
     * BluetoothDevice.connectGatt()를 호출하면, 인자로 넣은 GattClientCallback 콜백 메소드가 상황에 맞게 호출됨
     *
     * @param device BLE Device
     */
    public void connectDevice(BluetoothDevice device) {
        Log.d(TAG, "Device Name : " + device.getName());
        Log.d(TAG, "Device UUID : " + Arrays.toString(device.getUuids()));

        GattClientCallback gattClientCallback = new GattClientCallback();
        mGatt = device.connectGatt(mActivity, false, gattClientCallback);
    }

    /**
     * GATT 통신에 필요한 콜백 메소드 구현
     */
    private class GattClientCallback extends BluetoothGattCallback {
        /**
         * GATT 통신 상태가 변경될 때 호출되는 콜백 메소드1
         *
         * @param gatt     GATT
         * @param status   기존의 연결 상태
         * @param newState 변경된 연결 상태
         */
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            Log.d(TAG, "BluetoothGatt : " + gatt);
            Log.d(TAG, "Status : " + status);
            Log.d(TAG, "New Status : " + newState);

            if (status == BluetoothGatt.GATT_FAILURE) {
                disconnectGattServer();
                return;
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectGattServer();
                return;
            }

            // After receiving GATT_SUCCESS and STATE_CONNECTED
            // We Must DISCOVER the services of the GATT Server
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnected = true;
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectGattServer();
            }

            Log.d(TAG, "Connected with GattServer");
        }

        // 연결된 Device가 가지고 있는 GATT에서 Service 정보를 얻는 메소드
        // Service 획득 -> Service 안에 Characteristic 참조 + Characteristic을 구독하는 Descriptor 생성
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);

            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            mInitialized = gatt.setCharacteristicNotification(characteristic, true);
            Log.d(TAG, "GATT Initialized : " + mInitialized);

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean descriptorInitialized = mGatt.writeDescriptor(descriptor);
            Log.d(TAG, "Descriptor Initialized : " + descriptorInitialized);

            if (mInitialized && descriptorInitialized) {
                mHandler.post(() -> {
                    writeCharacteristic("O");
                    Toast.makeText(mActivity, "블루투스 기기와 연결되었습니다.", Toast.LENGTH_SHORT).show();
                });
            }
        }

        // Characteristic을 Write 하면 호출되는 콜백 메소드
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);

            String characteristicStringValue = characteristic.getStringValue(0);
            Log.d(TAG, "onWrite : " + characteristicStringValue);
        }

        // Characteristic의 값이 변경되면 호출되는 메소드
        // 반드시 Descriptor를 설정 해줘야함!
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            Log.d(TAG, "onChanged : " + characteristic.getStringValue(0));

            if (deliverable != null) {
                deliverable.onReceiveCharacteristicChanged(characteristic.getStringValue(0));
            }
        }

        /**
         * GATT Server와 통신을 끊는 메소드
         */
        private void disconnectGattServer() {
            mConnected = false;
            mInitialized = false;
            if (mGatt != null) {
                mGatt.disconnect();
                mGatt.close();
            }
        }
    }

    /**
     * GATT의 Characteristic의 값을 설정하여 데이터를 보내는 메소드
     *
     * @param message 보내려는 문자열
     */
    public void writeCharacteristic(String message) {
        if (!mConnected || !mInitialized) {
            Toast.makeText(mActivity, "BLE NOT CONNECTED!", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothGattService service = mGatt.getService(SERVICE_UUID);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);

        byte[] messageBytes = (message).getBytes(StandardCharsets.UTF_8);
        characteristic.setValue(messageBytes);
        Log.d(TAG, "writeCharacteristic: " + message);

        if (mGatt.writeCharacteristic(characteristic)) {
         }
    }

    public void showScanResults(ArrayList<BluetoothDevice> deviceList) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);

        ArrayList<String> deviceNameAddressList = new ArrayList<>();
        for (BluetoothDevice tmpDevice : deviceList) {
            String name = tmpDevice.getName();
            String address = tmpDevice.getAddress();
            String nameAddress = name + " / " + address;
            if (!deviceNameAddressList.contains(nameAddress)) {
                deviceNameAddressList.add(nameAddress);
            }
        }

        CharSequence[] dialogItems = deviceNameAddressList.toArray(new CharSequence[0]);

        builder.setTitle("BLE Device Scan Result")
                .setItems(dialogItems, ((dialogInterface, i) -> {
                    String selectedItem = dialogItems[i].toString();
                    String[] nameAddress = selectedItem.split(" / ");
                    BluetoothDevice selectedDevice = mScanResults.get(nameAddress[1]);
                    connectDevice(selectedDevice);
                }));

        AlertDialog dialog = builder.create();
        dialog.show();
    }
}