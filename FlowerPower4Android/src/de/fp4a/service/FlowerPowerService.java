/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.fp4a.service;

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import de.fp4a.model.FlowerPower;
import de.fp4a.model.FlowerPowerMetadata.FlowerPowerColors;
import de.fp4a.util.FlowerPowerConstants;
import de.fp4a.util.Util;
import de.fp4a.util.ValueMapper;

/**
 * Service for managing connection and data communication with a GATT server
 * hosted on a given Bluetooth LE device.
 */
public class FlowerPowerService extends Service implements IFlowerPowerDevice
{
	private final static String TAG = FlowerPowerService.class.getSimpleName();

	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	private String mBluetoothDeviceAddress;
	private BluetoothGatt mBluetoothGatt;
	private int mConnectionState = STATE_DISCONNECTED;

	private static final int STATE_DISCONNECTED = 0;
	private static final int STATE_CONNECTING = 1;
	private static final int STATE_CONNECTED = 2;

	public final static String CONNECTED = "de.fp4a.ACTION_GATT_CONNECTED";
	public final static String DISCONNECTED = "de.fp4a.ACTION_GATT_DISCONNECTED";
	public final static String SERVICES_DISCOVERED = "de.fp4a.ACTION_GATT_SERVICES_DISCOVERED";
	public final static String DATA_AVAILABLE = "de.fp4a.ACTION_DATA_AVAILABLE";
	
	public final static String EXTRA_CHARACTERISTIC_NAME = "de.fp4a.EXTRA_CHARACTERISTIC_NAME";
	public final static String EXTRA_DATA_STRING = "de.fp4a.EXTRA_DATA";
	public final static String EXTRA_DATA_RAW = "de.fp4a.EXTRA_DATA_RAW";
	public final static String EXTRA_DATA_MODEL = "de.fp4a.EXTRA_DATA_MODEL";

	private FlowerPower flowerPower;
	private FlowerPowerServiceQueue queue;
	private Timer timer;
	
	// Implements callback methods for GATT events that the app cares about. For example,
	// connection change and services discovered.
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
		{
			Log.i(TAG, "FlowerPowerService.onConnectionChange()");
			
			if (newState == BluetoothProfile.STATE_CONNECTED)
			{
				mConnectionState = STATE_CONNECTED;
				broadcastUpdate(CONNECTED);
				Log.i(TAG, "Connected to GATT server.");
		
				// Attempts to discover services after successful connection
				mBluetoothGatt.discoverServices();
				
				flowerPower = new FlowerPower();
			}
			else if (newState == BluetoothProfile.STATE_DISCONNECTED)
			{
				mConnectionState = STATE_DISCONNECTED;
				Log.i(TAG, "Disconnected from GATT server.");
				broadcastUpdate(DISCONNECTED);
			}
		}

		public void onServicesDiscovered(BluetoothGatt gatt, int status)
		{
			Log.i(TAG, "FlowerPowerService.onServicesDiscovered()");
			if (status == BluetoothGatt.GATT_SUCCESS)
			{
				broadcastUpdate(SERVICES_DISCOVERED);
			}
			else
			{
				Log.w(TAG, "FlowerPowerService.onServicesDiscovered() not successful: received: " + status);
			}
		}

		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
		{
			queue.dequeueRead(characteristic); // remove the corresponding read task from the queue
			
			if (status == BluetoothGatt.GATT_SUCCESS)
			{
				Log.i(TAG, "FlowerPowerService.onCharacteristicRead() success");
				broadcastUpdate(DATA_AVAILABLE, characteristic);
			}
			else
				Log.w(TAG, "FlowerPowerService.onCharacteristicRead() NO success");
		}

		/**
		 * Called upon a notification change. In contrast to a simple read, this method is continuously called if notifications are enabled.
		 */
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
		{
			Log.i(TAG, "FlowerPowerService.onCharacteristicChanged()");
			broadcastUpdate(DATA_AVAILABLE, characteristic);
		}
		
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
		{
			// remove the corresponding notify task from the queue 
			// (this has only an effect the first time this method is called for a registered notification)
			queue.dequeueNotify(characteristic);
			
			if (status == BluetoothGatt.GATT_SUCCESS)
			{
				Log.i(TAG, "FlowerPowerService.onCharacteristicWrite() success");
			}
			else 
				Log.w(TAG, "FlowerPowerService.onCharacteristicWrite() NO success");
		}
	};

	private void broadcastUpdate(final String action)
	{
		final Intent intent = new Intent(action);
		sendBroadcast(intent);
	}

	private void broadcastUpdate(final String action, final BluetoothGattCharacteristic characteristic)
	{
		final Intent intent = new Intent(action);
		final byte[] data = characteristic.getValue();
		
		if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_SUNLIGHT))
		{
			int i = data[0] + ((data[1] & 0xFF) * 256);
			double sunlight = ValueMapper.getInstance(this).mapSunlight(i);
			flowerPower.setSunlight(sunlight);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_TEMPERATURE))
		{
			int i = data[1] * 256 + (data[0] & 0xFF);
			int temperature = ValueMapper.getInstance(this).mapTemperature(i);
			flowerPower.setTemperature(temperature);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_SOIL_MOISTURE))
		{
			int i = data[1] * 256 + (data[0] & 0xFF);
			double soilMoisture = ValueMapper.getInstance(this).mapSoilMoisture(i);
			flowerPower.setSoilMoisture(soilMoisture);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_BATTERY_LEVEL))
		{
			int batteryLevel = new Byte(data[0]).intValue();
			flowerPower.setBatteryLevel(batteryLevel);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_SYSTEM_ID)) 
		{
			String systemId = Util.data2hex(data);
			flowerPower.getMetadata().setSystemId(systemId.substring(0, systemId.length()-2)); // cut off the last ':'
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_MODEL_NR)) 
		{
			String str = new String(data);
			flowerPower.getMetadata().setModelNr(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_SERIAL_NR)) 
		{
			String str = new String(data);  
			flowerPower.getMetadata().setSerialNr(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_FIRMWARE_REVISION)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setFirmwareRevision(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_HARDWARE_REVISION)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setHardwareRevision(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_SOFTWARE_REVISION)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setSoftwareRevision(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_MANUFACTURER_NAME)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setManufacturerName(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_CERT_DATA)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setCertData(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_PNP_ID)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setPnpId(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_FRIENDLY_NAME)) 
		{
			String str = new String(data); 
			flowerPower.getMetadata().setFriendlyName(str);
		}
		else if (characteristic.getUuid().toString().equals(FlowerPowerConstants.CHARACTERISTIC_UUID_COLOR))
		{
			FlowerPowerColors color = null;
			int i = data[1] * 256 + data[0];
			switch(i)
			{
				case 4: color = FlowerPowerColors.BROWN; break;
				case 6: color = FlowerPowerColors.GREEN; break;
				case 7: color = FlowerPowerColors.BLUE; break;
				default: color = FlowerPowerColors.UNKNOWN; 
			}	
			flowerPower.getMetadata().setColor(color);
		}
		else
		{
			Log.w(TAG, "FlowerPowerService broadcastUpdate: unknown characterisitic: " + characteristic.getUuid().toString());
		}
		
		intent.putExtra(EXTRA_DATA_RAW, data);
		intent.putExtra(EXTRA_CHARACTERISTIC_NAME, FlowerPowerConstants.getCharacteristicName(characteristic, this));
		intent.putExtra(EXTRA_DATA_MODEL, flowerPower);
		if (data != null && data.length > 0)
		{
			intent.putExtra(EXTRA_DATA_STRING, new String(data) + "\n" + Util.data2hex(data));
		}
		
		// inform all broadcast receivers
		sendBroadcast(intent);
	}

	public class LocalBinder extends Binder
	{
		FlowerPowerService getService()
		{
			return FlowerPowerService.this;
		}
	}

	@Override
	public void onCreate()
	{
		Log.i(TAG, "FlowerPowerService.onCreate()");
		queue = new FlowerPowerServiceQueue(this);
		timer = new Timer();
	}
	
	@Override
	public void onDestroy()
	{
		Log.i(TAG, "FlowerPowerService.onDestroy()");
		close();
	}
	
	@Override
	public IBinder onBind(Intent intent)
	{
		Log.i(TAG, "FlowerPowerService.onBind()");
		return mBinder;
	}

	@Override
	public boolean onUnbind(Intent intent)
	{
		Log.i(TAG, "FlowerPowerService.onUnbind()");
		return super.onUnbind(intent);
	}

	private final IBinder mBinder = new LocalBinder();

	/**
	 * Initializes a reference to the local Bluetooth adapter.
	 * 
	 * @return Return true if the initialization is successful.
	 */
	public boolean initialize()
	{
		// For API level 18 and above, get a reference to BluetoothAdapter through BluetoothManager.
		if (mBluetoothManager == null)
		{
			mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager == null)
			{
				Log.e(TAG, "Unable to initialize BluetoothManager.");
				return false;
			}
		}

		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if (mBluetoothAdapter == null)
		{
			Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
			return false;
		}

		return true;
	}

	/**
	 * Connects to the GATT server hosted on the Bluetooth LE device.
	 * 
	 * @param address
	 *            The device address of the destination device.
	 * 
	 * @return Return true if the connection is initiated successfully. The
	 *         connection result is reported asynchronously through the
	 *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 *         callback.
	 */
	public boolean connect(final String address)
	{
		Log.i(TAG, "FlowerPowerService.connect() to " + address);
		
		if (mBluetoothAdapter == null || address == null)
		{
			Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
			return false;
		}

		// Previously connected device. Try to reconnect.
		if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress) && mBluetoothGatt != null)
		{
			Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
			if (mBluetoothGatt.connect())
			{
				mConnectionState = STATE_CONNECTING;
				return true;
			}
			else
			{
				return false;
			}
		}

		final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		if (device == null)
		{
			Log.w(TAG, "Device not found.  Unable to connect.");
			return false;
		}
		// We want to directly connect to the device, so we are setting the autoConnect parameter to false.
		mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
		Log.d(TAG, "Trying to create a new connection.");
		mBluetoothDeviceAddress = address;
		mConnectionState = STATE_CONNECTING;
		return true;
	}

	/**
	 * Disconnects an existing connection or cancel a pending connection. The
	 * disconnection result is reported asynchronously through the
	 * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 * callback.
	 */
	public void disconnect()
	{
		Log.i(TAG, "FlowerPowerService.disconnect()");
		
		if (mBluetoothAdapter == null || mBluetoothGatt == null)
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.disconnect();
		
		timer.cancel();
	}
	
	public boolean isConnected()
	{
		return mConnectionState == STATE_CONNECTED;
	}

	/**
	 * After using a given BLE device, the app must call this method to ensure
	 * resources are released properly.
	 */
	public void close()
	{
		Log.i(TAG, "FlowerPowerService.close()");
		
		if (mBluetoothGatt == null)
		{
			return;
		}
		disconnect();
		mBluetoothGatt.close();
		mBluetoothGatt = null;
	}

	/**
	 * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported asynchronously through the
	 * {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
	 * callback.
	 * 
	 * @param characteristic
	 *            The characteristic to read from.
	 */
	public void readCharacteristic(BluetoothGattCharacteristic characteristic)
	{
		if (mBluetoothAdapter == null || mBluetoothGatt == null)
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.readCharacteristic(characteristic);
	}

	public void writeCharacteristic(BluetoothGattCharacteristic characteristic)
	{
		// for enable live mode new Buffer([0x01] for disable new Buffer([0x00]
		if (mBluetoothAdapter == null || mBluetoothGatt == null)
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.writeCharacteristic(characteristic);
	}

			
	/**
	 * Enables or disables notification on a give characteristic.
	 * 
	 * @param characteristic
	 *            Characteristic to act on.
	 * @param enabled
	 *            If true, enable notification. False otherwise.
	 */
	public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled)
	{
		if (mBluetoothAdapter == null || mBluetoothGatt == null)
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
	}
	
	public BluetoothGattService getDeviceInformationService()
	{
		if (mBluetoothGatt == null)
			return null;

		BluetoothGattService b = mBluetoothGatt.getService(UUID.fromString(FlowerPowerConstants.SERVICE_UUID_DEVICE_INFORMATION));
		return b;
	}
	
	public BluetoothGattService getSomeOtherService()
	{
		if (mBluetoothGatt == null)
			return null;

		BluetoothGattService b = mBluetoothGatt.getService(UUID.fromString(FlowerPowerConstants.SERVICE_UUID_SOME_OTHER_SERVICE));
		return b;
	}

	public BluetoothGattService getSomeOtherService2()
	{
		if (mBluetoothGatt == null)
			return null;

		BluetoothGattService b = mBluetoothGatt.getService(UUID.fromString(FlowerPowerConstants.SERVICE_UUID_SOME_OTHER_SERVICE_2));
		return b;
	}
	
	public BluetoothGattService getFlowerPowerService()
	{
		if (mBluetoothGatt == null)
			return null;

		BluetoothGattService b = mBluetoothGatt.getService(UUID.fromString(FlowerPowerConstants.SERVICE_UUID_FLOWER_POWER));
		return b;
	}
	
	public void readTemperature()
	{
		Log.i(TAG, "Read temperature");
		BluetoothGattCharacteristic chara = getFlowerPowerService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_TEMPERATURE));
		queue.enqueueRead(chara);
	}
	
	public void readSunlight()
	{
		Log.i(TAG, "Read sunlight");
		BluetoothGattCharacteristic chara = getFlowerPowerService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_SUNLIGHT));
		queue.enqueueRead(chara);
	}
	
	public void readSoilMoisture()
	{
		Log.i(TAG, "Read soil moisture");
		BluetoothGattCharacteristic chara = getFlowerPowerService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_SOIL_MOISTURE));
		queue.enqueueRead(chara);
	}
	
	public void readBatteryLevel()
	{
		Log.i(TAG, "Read battery");
		BluetoothGattCharacteristic chara = getSomeOtherService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_BATTERY_LEVEL));
		queue.enqueueRead(chara);
	}
	
	public void readSystemId()
	{
		Log.i(TAG, "Read system id");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_SYSTEM_ID));
		queue.enqueueRead(chara);
	}
	
	public void readModelNr()
	{
		Log.i(TAG, "Read model nr");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_MODEL_NR));
		queue.enqueueRead(chara);
	}

	public void readSerialNr()
	{
		Log.i(TAG, "Read serial nr");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_SERIAL_NR));
		queue.enqueueRead(chara);
	}

	public void readFirmwareRevision()
	{
		Log.i(TAG, "Read firmware rev");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_FIRMWARE_REVISION));
		queue.enqueueRead(chara);
	}

	public void readHardwareRevision()
	{
		Log.i(TAG, "Read hardware rev");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_HARDWARE_REVISION));
		queue.enqueueRead(chara);
	}

	public void readSoftwareRevision()
	{
		Log.i(TAG, "Read software rev");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_SOFTWARE_REVISION));
		queue.enqueueRead(chara);
	}

	public void readManufacturerName()
	{
		Log.i(TAG, "Read manufacturer name");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_MANUFACTURER_NAME));
		queue.enqueueRead(chara);
	}

	public void readCertData()
	{
		Log.i(TAG, "Read cert data");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_CERT_DATA));
		queue.enqueueRead(chara);
	}

	public void readPnpId()
	{
		Log.i(TAG, "Read pnp id");
		BluetoothGattCharacteristic chara = getDeviceInformationService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_PNP_ID));
		queue.enqueueRead(chara);
	}
	
	public void readFriendlyName()
	{
		Log.i(TAG, "Read friendly name");
		BluetoothGattCharacteristic chara = getSomeOtherService2().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_FRIENDLY_NAME));
		queue.enqueueRead(chara);
	}
	
	public void readColor()
	{
		Log.i(TAG, "Read color");
		BluetoothGattCharacteristic chara = getSomeOtherService2().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_COLOR));
		queue.enqueueRead(chara);
	}
	
	public void notify(final BluetoothGattCharacteristic characteristic, final boolean enable)
	{
		Log.i(TAG, (enable ? "Enable" : "Disable") + " Notification for " + FlowerPowerConstants.getCharacteristicName(characteristic, this));
		
		UUID uuidLiveMode = UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_LIVE_MODE); // that's 39e1fa06-84a8-11e2-afba-0002a5d5c51b
		
		BluetoothGattCharacteristic liveModeChara = getFlowerPowerService().getCharacteristic(uuidLiveMode);
		liveModeChara.setValue(enable ? new byte[] {0x01} : new byte[] {0x00});
		writeCharacteristic(liveModeChara);
			
		setCharacteristicNotification(characteristic, enable); 
	}
	
	public void notifyTemperature(boolean enable)
	{
		queue.enqueueNotify(getFlowerPowerService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_TEMPERATURE)), enable);
	}
	
	public void notifySunlight(boolean enable)
	{
		queue.enqueueNotify(getFlowerPowerService().getCharacteristic(UUID.fromString(FlowerPowerConstants.CHARACTERISTIC_UUID_SUNLIGHT)), enable);
	}
	
	private TimerTask timerTaskNotifySoilMoisture;
	public void notifySoilMoisture(boolean enable)
	{
		// for some reason, the 'classic' notification mechanism does not work for this characteristic
		// hence use a timer for periodically update the notification
		
		if (timerTaskNotifySoilMoisture != null) // if task already exists, cancel
		{
			timerTaskNotifySoilMoisture.cancel();
			timerTaskNotifySoilMoisture = null;
			timer.purge();
		}
		
		if (enable) // create and schedule a new task if required
		{
			timerTaskNotifySoilMoisture = new TimerTask() {
				public void run()
				{
					readSoilMoisture();
				} 
			};
			timer.schedule(timerTaskNotifySoilMoisture, 0, 1000);
		}
	}
	
	private TimerTask timerTaskNotifyBatteryLevel;
	public void notifyBatteryLevel(boolean enable)
	{
		// for some reason, the 'classic' notification mechanism does not work for this characteristic
		// hence use a timer for periodically update the notification
		
		if (timerTaskNotifyBatteryLevel != null) // if task already exists, cancel
		{
			timerTaskNotifyBatteryLevel.cancel();
			timerTaskNotifyBatteryLevel = null;
			timer.purge();
		}
		
		if (enable) // create and schedule a new task if required
		{
			timerTaskNotifyBatteryLevel = new TimerTask() {
				public void run()
				{
					readBatteryLevel();
				}
			};
			timer.schedule(timerTaskNotifyBatteryLevel, 0, 1000);
		}
	}
}