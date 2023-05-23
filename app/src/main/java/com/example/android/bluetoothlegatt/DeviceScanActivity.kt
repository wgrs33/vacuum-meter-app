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
package com.example.android.bluetoothlegatt

import android.Manifest
import android.app.ListActivity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
class DeviceScanActivity : ListActivity() {
    private var mLeDeviceListAdapter: LeDeviceListAdapter? = null
    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mScanning = false
    private var mHandler: Handler? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar!!.setTitle(R.string.title_devices)
        mHandler = Handler(Looper.getMainLooper())

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1001)
            finish()
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = bluetoothManager.adapter

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mBluetoothLeScanner = mBluetoothAdapter!!.bluetoothLeScanner
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).isVisible = false
            menu.findItem(R.id.menu_scan).isVisible = true
            menu.findItem(R.id.menu_refresh).actionView = null
        } else {
            menu.findItem(R.id.menu_stop).isVisible = true
            menu.findItem(R.id.menu_scan).isVisible = false
            menu.findItem(R.id.menu_refresh).setActionView(
                R.layout.actionbar_indeterminate_progress
            )
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scan -> {
                mLeDeviceListAdapter!!.clear()
                scanLeDevice(true)
            }

            R.id.menu_stop -> scanLeDevice(false)
        }
        return true
    }

    override fun onResume() {
        super.onResume()

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.

        if (!mBluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }

        // Initializes list view adapter.
        mLeDeviceListAdapter = LeDeviceListAdapter()
        listAdapter = mLeDeviceListAdapter
        scanLeDevice(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_CANCELED) {
            finish()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        scanLeDevice(false)
        mLeDeviceListAdapter!!.clear()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val device = mLeDeviceListAdapter!!.getDevice(position)
        val intent = Intent(this, DeviceControlActivity::class.java)
        try {
            intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_NAME, device.name)
            intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_ADDRESS, device.address)
            if (mScanning) {
                mBluetoothLeScanner!!.stopScan(scanCallback)
                mScanning = false
            }
            startActivity(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun scanLeDevice(enable: Boolean) {
        try {
            if (enable) {
                // Stops scanning after a pre-defined scan period.
                mHandler!!.postDelayed({
                    mScanning = false
                    mBluetoothLeScanner!!.stopScan(scanCallback)
                    invalidateOptionsMenu()
                }, SCAN_PERIOD)
                mScanning = true
                mBluetoothLeScanner!!.startScan(scanCallback)
            } else {
                mScanning = false
                mBluetoothLeScanner!!.stopScan(scanCallback)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        invalidateOptionsMenu()
    }

    private fun hasPermissions(): Boolean {
        return (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED)
    }

    // Adapter for holding devices found through scanning.
    private inner class LeDeviceListAdapter : BaseAdapter() {
        private val mLeDevices: ArrayList<BluetoothDevice> = ArrayList()
        private val mInflator: LayoutInflater = this@DeviceScanActivity.layoutInflater

        fun addDevice(device: BluetoothDevice) {
            try {
                if (!mLeDevices.contains(device) && device.name != null) {
                    Log.d("LeDeviceListAdapter", "Added new device: $device")
                    mLeDevices.add(device)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }

        fun getDevice(position: Int): BluetoothDevice {
            return mLeDevices[position]
        }

        fun clear() {
            mLeDevices.clear()
        }

        override fun getCount(): Int {
            return mLeDevices.size
        }

        override fun getItem(i: Int): Any {
            return mLeDevices[i]
        }

        override fun getItemId(i: Int): Long {
            return i.toLong()
        }

        override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View? {
            var deviceView = view
            val viewHolder: ViewHolder
            if (deviceView == null) {
                deviceView = mInflator.inflate(R.layout.listitem_device, null)
                viewHolder = ViewHolder()
                viewHolder.deviceAddress = deviceView.findViewById<View>(R.id.device_address) as TextView
                viewHolder.deviceName = deviceView.findViewById<View>(R.id.device_name) as TextView
                deviceView.tag = viewHolder
            } else {
                viewHolder = deviceView.tag as ViewHolder
            }
            val device = mLeDevices[i]
            try {
                val deviceName = device.name
                if (!deviceName.isNullOrEmpty())
                    viewHolder.deviceName!!.text = deviceName
                else
                    viewHolder.deviceName!!.setText(R.string.unknown_device)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
            viewHolder.deviceAddress!!.text = device.address
            return deviceView
        }
    }

    // Device scan callback.
    private val scanCallback = object:ScanCallback() {
        private val TAG: String = "ScanCallback"
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            if (result?.device == null)
                return
            runOnUiThread {
                mLeDeviceListAdapter!!.addDevice(result.device)
                mLeDeviceListAdapter!!.notifyDataSetChanged()
            }
        }

        override fun onBatchScanResults(results:List<ScanResult>?){
            Log.d(TAG, "Scanning...")
            super.onBatchScanResults(results)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.d(TAG, "Discovery onScanFailed: $errorCode")
            super.onScanFailed(errorCode)
        }
    }

    internal class ViewHolder {
        var deviceName: TextView? = null
        var deviceAddress: TextView? = null
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1

        // Stops scanning after 10 seconds.
        private const val SCAN_PERIOD: Long = 10000
    }
}