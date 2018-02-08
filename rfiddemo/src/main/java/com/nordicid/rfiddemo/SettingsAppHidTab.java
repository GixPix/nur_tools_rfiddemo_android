package com.nordicid.rfiddemo;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import com.nordicid.apptemplate.AppTemplate;
import com.nordicid.nuraccessory.NurAccessoryConfig;
import com.nordicid.nuraccessory.NurAccessoryExtension;
import com.nordicid.nuraccessory.NurAccessoryVersionInfo;
import com.nordicid.nurapi.NurApi;
import com.nordicid.nurapi.NurApiListener;
import com.nordicid.nurapi.NurEventAutotune;
import com.nordicid.nurapi.NurEventClientInfo;
import com.nordicid.nurapi.NurEventDeviceInfo;
import com.nordicid.nurapi.NurEventEpcEnum;
import com.nordicid.nurapi.NurEventFrequencyHop;
import com.nordicid.nurapi.NurEventIOChange;
import com.nordicid.nurapi.NurEventInventory;
import com.nordicid.nurapi.NurEventNxpAlarm;
import com.nordicid.nurapi.NurEventProgrammingProgress;
import com.nordicid.nurapi.NurEventTagTrackingChange;
import com.nordicid.nurapi.NurEventTagTrackingData;
import com.nordicid.nurapi.NurEventTraceTag;
import com.nordicid.nurapi.NurEventTriggeredRead;

public class SettingsAppHidTab extends Fragment {
	SettingsAppTabbed mOwner;

	NurApi mApi;
	NurAccessoryExtension mExt;

	boolean mHasWirelessCharging = false;
	
	CheckBox mHidBarcodeCheckBox;
	CheckBox mHidRFIDCheckBox;
	CheckBox mWirelessChargingCheckBox;
	CheckBox mAllowPairingCheckBox;

	TextView mWirelessChargingLabel;
	
	private NurApiListener mThisClassListener = null;
	
	public NurApiListener getNurApiListener()
	{
		return mThisClassListener;
	}
	
	public SettingsAppHidTab() {
		mOwner = SettingsAppTabbed.getInstance();
		mApi = mOwner.getNurApi();
		mExt = AppTemplate.getAppTemplate().getAccessoryApi();
		
		mThisClassListener = new NurApiListener() {
			@Override
			public void connectedEvent() {
				if (isAdded()) {
					enableItems(true);
					readCurrentSetup();
				}
			}

			@Override
			public void disconnectedEvent() {
				if (isAdded()) {
					enableItems(false);
				}
			}

			@Override public void logEvent(int level, String txt) {}
			@Override public void bootEvent(String event) {}
			@Override public void inventoryStreamEvent(NurEventInventory event) { } 
			@Override public void IOChangeEvent(NurEventIOChange event) {}
			@Override public void traceTagEvent(NurEventTraceTag event) { } 
			@Override public void triggeredReadEvent(NurEventTriggeredRead event) {}
			@Override public void frequencyHopEvent(NurEventFrequencyHop event) {}
			@Override public void debugMessageEvent(String event) {}
			@Override public void inventoryExtendedStreamEvent(NurEventInventory event) { } 
			@Override public void programmingProgressEvent(NurEventProgrammingProgress event) {}
			@Override public void deviceSearchEvent(NurEventDeviceInfo event) {}
			@Override public void clientConnectedEvent(NurEventClientInfo event) {}
			@Override public void clientDisconnectedEvent(NurEventClientInfo event) {}
			@Override public void nxpEasAlarmEvent(NurEventNxpAlarm event) {}
			@Override public void epcEnumEvent(NurEventEpcEnum event) {}
			@Override public void autotuneEvent(NurEventAutotune event) {}

			@Override
			public void tagTrackingScanEvent(NurEventTagTrackingData event) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void tagTrackingChangeEvent(NurEventTagTrackingChange event) {
				// TODO Auto-generated method stub
				
			}			
		};
	}
	
	public void onVisibility(boolean val)
	{
		if (val)
		{
			if (isAdded()) {
				enableItems(mApi.isConnected());
				if (mApi.isConnected()) {
					readCurrentSetup();
				}
			}
		}
	}
	
	private void enableItems(boolean v) {
		mHidBarcodeCheckBox.setEnabled(v);
		mHidRFIDCheckBox.setEnabled(v);
		mWirelessChargingCheckBox.setEnabled(v);
		mAllowPairingCheckBox.setEnabled(v);
	}

	private String removeSpecificChars(String originalstring ,String removecharacterstring)
	{
		char[] orgchararray=originalstring.toCharArray();
		char[] removechararray=removecharacterstring.toCharArray();
		int start,end=0;

		//tempBoolean automatically initialized to false ,size 128 assumes ASCII
		boolean[]  tempBoolean = new boolean[128];

		//Set flags for the character to be removed
		for(start=0;start < removechararray.length;++start)
		{
			tempBoolean[removechararray[start]]=true;
		}

		//loop through all characters ,copying only if they are flagged to false
		for(start=0;start < orgchararray.length;++start)
		{
			if(!tempBoolean[orgchararray[start]])
			{
				orgchararray[end++]=orgchararray[start];
			}
		}


		return new String(orgchararray,0,end);
	}

	private void readCurrentSetup() 
	{
		mHidBarcodeCheckBox.setOnCheckedChangeListener(null);
		mHidRFIDCheckBox.setOnCheckedChangeListener(null);
		mWirelessChargingCheckBox.setOnCheckedChangeListener(null);
		mAllowPairingCheckBox.setOnCheckedChangeListener(null);

		if (!AppTemplate.getAppTemplate().getAccessorySupported()) {
			enableItems(false);
			return;
		}

		NurAccessoryConfig cfg;
		try {
			cfg = mExt.getConfig();
			NurAccessoryVersionInfo info = mExt.getFwVersion();
			String appver=removeSpecificChars(info.getApplicationVersion(),".");

			int ver = Integer.parseInt(appver);
			if(ver >= 221)
			{
				mAllowPairingCheckBox.setEnabled(true);
				Log.e("Acc","Enable");
			}
			else
			{
				mAllowPairingCheckBox.setEnabled(false);
				Log.e("Acc","Disable");
			}


		} catch (Exception e) {
			e.printStackTrace();
			enableItems(false);
			return;
		}

		try {
			mHidBarcodeCheckBox.setChecked(cfg.getHidBarCode());
			mHidRFIDCheckBox.setChecked(cfg.getHidRFID());
			mWirelessChargingCheckBox.setEnabled(cfg.hasWirelessCharging());
			mAllowPairingCheckBox.setChecked(cfg.getAllowPairingState());
		} catch (Exception e) {
			e.printStackTrace();
		}

		mHidBarcodeCheckBox.setOnCheckedChangeListener(mOnCheckedChangeListener);
		mHidRFIDCheckBox.setOnCheckedChangeListener(mOnCheckedChangeListener);
		mWirelessChargingCheckBox.setOnCheckedChangeListener(mWirelessChargingChangeListener);
		mAllowPairingCheckBox.setOnCheckedChangeListener(mAllowPairingChangeListener);
	}
	
	void setNewHidConfig()
	{
		try {
			NurAccessoryConfig cfg = mExt.getConfig();
			cfg.setHidBarcode(mHidBarcodeCheckBox.isChecked());
			cfg.setHidRFID(mHidRFIDCheckBox.isChecked());
			mExt.setConfig(cfg);
			readCurrentSetup();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			enableItems(false);
		}
	}

	void setNewAllowPairingConfig()
	{
		try {
			NurAccessoryConfig cfg = mExt.getConfig();
			cfg.setAllowPairingState(mAllowPairingCheckBox.isChecked());
			mExt.setConfig(cfg);
			readCurrentSetup();
			Toast.makeText(AppTemplate.getAppTemplate(),"Rebooting device..", Toast.LENGTH_SHORT).show();
			mExt.restartBLEModule();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			enableItems(false);
		}
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		return inflater.inflate(R.layout.tab_settings_hid, container, false);
	}
	
	OnCheckedChangeListener mOnCheckedChangeListener = new OnCheckedChangeListener() {		
		@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
			setNewHidConfig();
		}
	};

	OnCheckedChangeListener mAllowPairingChangeListener = new OnCheckedChangeListener() {
		@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
		{
			setNewAllowPairingConfig();
		}
	};



	OnCheckedChangeListener mWirelessChargingChangeListener = new OnCheckedChangeListener() {
		@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
			try {
				String msg;
				int result = mExt.setWirelessChargingOn(mWirelessChargingCheckBox.isChecked());
				Log.d("SetWirelessCharging","result " + result);
				switch (result)
				{
					case NurAccessoryExtension.WIRELESS_CHARGING_FAIL:
					case NurAccessoryExtension.WIRELESS_CHARGING_REFUSED:
						msg = "Failed to set wireless charging value";
						break;
					case NurAccessoryExtension.WIRELESS_CHARGING_NOT_SUPPORTED:
						msg = "Wireless Charging not supported";
						break;
					default:
						msg = "Wireless charging turned " + ((result == NurAccessoryExtension.WIRELESS_CHARGING_ON) ? "On" : "Off");
						break;
				}
				mWirelessChargingCheckBox.setOnCheckedChangeListener(null);
				mWirelessChargingCheckBox.setChecked(mExt.isWirelessChargingOn());
				Toast.makeText(AppTemplate.getAppTemplate(),msg, Toast.LENGTH_SHORT).show();
			} catch (Exception e)
			{
				// TODO
			}
			mWirelessChargingCheckBox.setOnCheckedChangeListener(mWirelessChargingChangeListener);
		}
	};
	
	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		
		mHidBarcodeCheckBox = (CheckBox)view.findViewById(R.id.hid_barcode_checkbox);
		mHidRFIDCheckBox = (CheckBox)view.findViewById(R.id.hid_rfid_checkbox);
		mWirelessChargingCheckBox = (CheckBox)view.findViewById(R.id.hid_wireless_charging_checkbox);
		mAllowPairingCheckBox = (CheckBox)view.findViewById(R.id.allow_pairing_checkbox);
		mWirelessChargingLabel = (TextView)view.findViewById(R.id.hid_wireless_charging_label);

		mHidBarcodeCheckBox.setOnCheckedChangeListener(mOnCheckedChangeListener);
		mHidRFIDCheckBox.setOnCheckedChangeListener(mOnCheckedChangeListener);
		mWirelessChargingCheckBox.setOnCheckedChangeListener(mWirelessChargingChangeListener);
		mAllowPairingCheckBox.setOnCheckedChangeListener(mAllowPairingChangeListener);

		enableItems(mApi.isConnected());
		if (mApi.isConnected()) {
            readCurrentSetup();
        }
	}
}
