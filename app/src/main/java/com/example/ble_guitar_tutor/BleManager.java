package com.example.ble_guitar_tutor;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

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
    private static final UUID SERVICE_UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214"); // BLE Service UUID
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214"); // BLE Characteristic UUID
    private static final UUID DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); // BLE Descriptor UUID
    private final int MAX_LENGTH = 300; // BLE 기기의 StringCharacteristic 최대 길이

    /* BLE Scan Field */
    private BluetoothAdapter mBluetoothAdapter;
    private Handler mHandler;
    private Context mContext;
    private boolean mScanning = false;
    private HashMap<String, BluetoothDevice> mScanResults;
    private BleScanCallback mScanCallback;
    private BluetoothLeScanner mBluetoothScanner;

    /* BLE Connect Field*/
    private BluetoothGatt mGatt;
    private boolean mConnected = false;
    private boolean mInitialized = false;
    private boolean mBeforeWrite = true; // "writeCharacteristic()"을 통해 데이터를 보내면 onChanged()가 onWrite()보다 먼저 호출되는 경우를 방지
    private String mSendMessage = "";

    /* Callback to transfer BLE Characteristic data */
    private Deliverable deliverable = null;
    private BluetoothGattService service;
    private BluetoothGattCharacteristic characteristic;

    /**
     * BLE Device로부터 받은 데이터를 외부 클래스에서 전달 받을 수 있도록 콜백 메소드를 정의한 인터페이스
     */
    public interface Deliverable {
        void onReceiveCharacteristicChanged(String data);
    }

    public void setDeliverable(Deliverable deliverable) {
        this.deliverable = deliverable;
    }

    /**
     * BLE 연결 상태를 반환하는 메소드
     *
     * @return BLE 연결 상태
     */
    public boolean isConnected() {
        return  mConnected;
    }

    /**
     * BLE Manager 생성자
     * 1. 현재 안드로이드 기기가 BLE 지원이 되는지 확인
     * 2. BluetoothAdapter 획득
     */
    public BleManager(Context context, Handler handler, BluetoothAdapter bluetoothAdapter) {
        mContext = context;
        mHandler = handler;
        mBluetoothAdapter = bluetoothAdapter;

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

        Toast.makeText(mContext, "BLE Scan Start", Toast.LENGTH_SHORT).show();

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
        Toast.makeText(mContext, "BLE Scan Stop", Toast.LENGTH_SHORT).show();

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
            Toast.makeText(mContext, "No BLE Device", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(mContext, "BLE Device Connected : " + device.getName(), Toast.LENGTH_SHORT).show();

        GattClientCallback gattClientCallback = new GattClientCallback();
        mGatt = device.connectGatt(mContext, false, gattClientCallback);
    }

    /**
     * GATT 통신에 필요한 콜백 메소드 구현
     */
    private class GattClientCallback extends BluetoothGattCallback {

        /**
         * GATT 통신 상태가 변경될 때 호출되는 콜백 메소드
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
                gatt.discoverServices(); // onServicesDiscovered 콜백 메소드로 넘어감.
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Toast.makeText(mContext, "블루투스 연결이 끊겼습니다.", Toast.LENGTH_SHORT).show();
                disconnectGattServer();
            }

            Log.d(TAG, "Connected with GattServer");
        }

        /**
         * 연결된 Device가 가지고 있는 GATT에서 Service 정보를 얻는 메소드
         * Service 획득 -> Service 안에 Characteristic 참조 + Characteristic을 구독하는 Descriptor 생성
         *
         * @param gatt 연결된 Device의 GATT
         * @param status 연결 상태
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }

            service = gatt.getService(SERVICE_UUID);
            characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);

            // Write 에 사용할 Characteristic 설정
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            mInitialized = gatt.setCharacteristicNotification(characteristic, true);
            Log.d(TAG, "GATT Initialized : " + mInitialized);

            // Characteristic 을 구독하는 Descriptor 설정
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean descriptorInitialized = mGatt.writeDescriptor(descriptor);
            Log.d(TAG, "Descriptor Initialized : " + descriptorInitialized);

            if (mInitialized && descriptorInitialized) {
                mHandler.post(() -> Toast.makeText(mContext, "블루투스 기기와 연결되었습니다.", Toast.LENGTH_SHORT).show());
            }
        }

        /**
         * Characteristic을 Write 하면 호출되는 콜백 메소드
         * 한글은 지원하지 않음(write 가능하지만, 반대편에서 읽지 못함)
         * CharacteristicWrite와 Changed가 거의 동시에 일어나면 혼선이 일어나는 것 같음.
         *
         * @param gatt 연결된 Device의 GATT
         * @param characteristic 연결된 Device와 공유하는 Characteristic
         * @param status 연결 상태
         */
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);

            mBeforeWrite = false;

            String characteristicStringValue = characteristic.getStringValue(0);
            Log.d(TAG, "onWrite : " + characteristicStringValue);
        }

        /**
         * Characteristic의 값이 변경되면 호출되는 메소드
         * 반드시 "Descriptor"를 설정 해줘야함! (onServicesDiscovered 참고)
         *
         * @param gatt 연결된 Device의 GATT
         * @param characteristic 연결된 Device와 공유하는 Characteristic
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if(mBeforeWrite) {
                return;
            }
            super.onCharacteristicChanged(gatt, characteristic);

            String receivedValue = characteristic.getStringValue(0);
            Log.d(TAG, "onChanged : " + receivedValue);
            mBeforeWrite = true;

            if(receivedValue.equals("") && !mSendMessage.isEmpty()) {
                writeCharacteristic(mSendMessage);
            }

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

            Log.d(TAG, "disconnectGattServer: Disconnected");
        }
    }

    /**
     * GATT의 Characteristic의 값을 설정하여 데이터를 보내는 메소드
     *
     * mSendingMessage : 보내려는 최종 문자열.
     * message : 인자값으로 들어온 문자열, mSendingMessage가 있을 경우에는 mSendingMessage의 나머지 부분으로 덮어쓰여짐.
     *
     * @param message 보내려는 String 값
     */
    public void writeCharacteristic(String message) {
        if (!mConnected || !mInitialized) {
            Toast.makeText(mContext, "BLE NOT CONNECTED!", Toast.LENGTH_SHORT).show();
            return;
        }

        if(mSendMessage.isEmpty()) {
            mSendMessage = message + "$";
        }

        if(mSendMessage.length() > MAX_LENGTH) {
            message = mSendMessage.substring(0, MAX_LENGTH);
            mSendMessage = mSendMessage.substring(MAX_LENGTH);
        } else {
            message = mSendMessage;
            mSendMessage = "";
        }

        byte[] messageBytes = (message).getBytes(StandardCharsets.UTF_8);
        characteristic.setValue(messageBytes);
        Log.d(TAG, "writeCharacteristic: " + message + " >> " + mGatt.writeCharacteristic(characteristic));
    }

    /**
     * 스캔 결과 BluetoothDevice의 이름과 주소를 AlertDialog로 보여주는 메소드
     *
     * @param deviceList 스캔된 주변 BluetoothDevice 목록
     */
    private void showScanResults(ArrayList<BluetoothDevice> deviceList) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);

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