package com.nordicid.rfiddemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.nordicid.apptemplate.SubApp;
import com.nordicid.controllers.TemperatureController;
import com.nordicid.nurapi.NurApiListener;
import com.nordicid.nurapi.NurEventInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.util.Log;

public class TemperatureApp extends SubApp {

    // UI States
    public enum AppState {
        IDLE,       // Ready to scan or read
        SCANNING,   // Currently scanning for tags
        READING     // Currently reading selected tags
    }

    private ListView mTagsListView;
    private TagAdapter mAdapter;
    private ArrayList<HashMap<String, String>> mTagData = new ArrayList<>();
    private ArrayList<HashMap<String, String>> mOriginalTagData = new ArrayList<>(); // Backup for filtering
    private Button mScanButton, mReadTempButton, mExportButton;
    private TextView mStatusTextView, mModeIndicator, mListHeader, mSelectedCount;
    private Spinner mPowerSpinner;
    private TemperatureController mTemperatureController;
    
    // Historical readings storage - maintains complete history for each tag
    private HashMap<String, ArrayList<HashMap<String, String>>> mHistoricalReadings = new HashMap<>();
    
    // Current UI state
    private AppState mCurrentState = AppState.IDLE;
    
    // TX Power level storage
    private int mSelectedPower = 0; // Default to maximum power
    private NurApiListener mNurApiListener;

    public TemperatureApp() {
        super();
        mNurApiListener = new NurApiListenerAdapter() {
            @Override
            public void inventoryStreamEvent(NurEventInventory ev) {
                if (mTemperatureController != null) {
                    mTemperatureController.handleInventoryEvent(getNurApi().getStorage());
                    try {
                        getNurApi().clearIdBuffer();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void disconnectedEvent() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> handleInventoryStatus(false));
                }
            }
        };
    }

    @Override
    public String getAppName() {
        return "Temperature";
    }

    @Override
    public int getLayout() { return R.layout.app_temperature; }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTemperatureController = new TemperatureController(requireContext(), getNurApi());
        
        // Initialize UI elements
        mTagsListView = view.findViewById(R.id.tags_list_view);
        mScanButton = view.findViewById(R.id.scan_button);
        mReadTempButton = view.findViewById(R.id.read_temp_button);
        mExportButton = view.findViewById(R.id.export_button);
        mStatusTextView = view.findViewById(R.id.status_text_view);
        mModeIndicator = view.findViewById(R.id.mode_indicator);
        mListHeader = view.findViewById(R.id.list_header);
        mSelectedCount = view.findViewById(R.id.selected_count);
        mPowerSpinner = view.findViewById(R.id.power_spinner);
        
        setupControls();
        updateUIState(AppState.IDLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        registerBroadcastReceiver();
        if (mPowerSpinner != null) {
            setupTxPowerSpinner();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mTemperatureReceiver);
        if (mTemperatureController != null) {
            mTemperatureController.stopInventory();
        }
    }
    
    @Override
    public NurApiListener getNurApiListener() { return mNurApiListener; }
    
    private void setupControls() {
        mAdapter = new TagAdapter(requireContext(), mTagData);
        mTagsListView.setAdapter(mAdapter);
        
        // Pass historical readings to adapter
        mAdapter.setHistoricalReadings(mHistoricalReadings);
        
        // Set listener for adapter selection changes
        mAdapter.setOnSelectionChangedListener(() -> {
            updateListHeader();
            updateReadButtonState();
        });

        // Handle item click to toggle checkbox (only when not reading)
        mTagsListView.setOnItemClickListener((parent, view, position, id) -> {
            if (mCurrentState != AppState.READING) {
                HashMap<String, String> item = mTagData.get(position);
                boolean isSelected = Boolean.parseBoolean(item.get("isSelected"));
                item.put("isSelected", String.valueOf(!isSelected));
                mAdapter.notifyDataSetChanged();
                updateListHeader();
                updateReadButtonState(); // Enable/disable Read Temp button based on selection
            }
        });

        setupTxPowerSpinner();
        
        mScanButton.setOnClickListener(v -> toggleInventory());
        mReadTempButton.setOnClickListener(v -> handleReadButtonClick());
        mExportButton.setOnClickListener(v -> handleExportButtonClick());
    }
    
    private void setupTxPowerSpinner() {
        if (getNurApi().isConnected()) {
            try {
                List<String> txLevels = getTxLevelsFromDevice();
                ArrayAdapter<String> powerAdapter = new ArrayAdapter<>(requireContext(),
                        android.R.layout.simple_spinner_item, txLevels);
                powerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mPowerSpinner.setAdapter(powerAdapter);
                mPowerSpinner.setSelection(0, false);
                
                mPowerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        mSelectedPower = position;
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> parent) { }
                });
            } catch (Exception e) {
                setupFallbackTxPowerSpinner();
            }
        } else {
            setupFallbackTxPowerSpinner();
        }
    }
    
    private void setupFallbackTxPowerSpinner() {
        ArrayAdapter<CharSequence> powerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.tx_level_entries_1W, android.R.layout.simple_spinner_item);
        powerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPowerSpinner.setAdapter(powerAdapter);
        mPowerSpinner.setSelection(0, false);
        
        mPowerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedPower = position;
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }
    
    private List<String> getTxLevelsFromDevice() {
        java.text.DecimalFormat df = new java.text.DecimalFormat("#");
        List<String> list = new ArrayList<>();
        try {
            com.nordicid.nurapi.NurRespDevCaps caps = getNurApi().getDeviceCaps();
            for(int x = 0; x < caps.txSteps; x++) {
                double dBm = caps.maxTxdBm - (x * caps.txAttnStep);
                list.add(df.format(dBm) + " dBm");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private void updateUIState(AppState newState) {
        mCurrentState = newState;
        
        switch (newState) {
            case IDLE:
                mStatusTextView.setText("Ready to scan");
                mModeIndicator.setText("IDLE");
                mScanButton.setContentDescription("Scan for temperature sensors");
                mScanButton.setEnabled(true);
                mReadTempButton.setContentDescription("Read temperature from selected sensors");
                mReadTempButton.setEnabled(mAdapter.getSelectedCount() > 0);
                mExportButton.setEnabled(mTagData.size() > 0);
                mPowerSpinner.setEnabled(true);
                mAdapter.setReadingMode(false);
                restoreOriginalTagData();
                break;
                
            case SCANNING:
                mStatusTextView.setText("Scanning for temperature sensors...");
                mModeIndicator.setText("SCAN");
                mScanButton.setContentDescription("Stop scanning");
                mScanButton.setEnabled(true);
                mReadTempButton.setEnabled(false);
                mExportButton.setEnabled(false);
                mPowerSpinner.setEnabled(false);
                mAdapter.setReadingMode(false);
                restoreOriginalTagData(); // Make sure we show all found tags during scanning
                break;
                
            case READING:
                mStatusTextView.setText("Reading temperatures...");
                mModeIndicator.setText("READ");
                mScanButton.setEnabled(false);
                mReadTempButton.setContentDescription("Stop reading temperatures");
                mReadTempButton.setEnabled(true);
                mExportButton.setEnabled(false);
                mPowerSpinner.setEnabled(false);
                mAdapter.setReadingMode(true);
                filterTagsForReading(); // Show only selected tags during reading
                break;
        }
        
        updateListHeader();
    }

    private void filterTagsForReading() {
        // Get selected tags BEFORE clearing data
        List<HashMap<String, String>> selectedTags = mAdapter.getSelectedTags();
        
        // Backup original data
        mOriginalTagData.clear();
        mOriginalTagData.addAll(mTagData);
        
        // Show only selected tags during reading
        mTagData.clear();
        mTagData.addAll(selectedTags);
        mAdapter.notifyDataSetChanged();
    }

    private void restoreOriginalTagData() {
        // Restore original data when not in reading mode
        if (!mOriginalTagData.isEmpty()) {
            mTagData.clear();
            mTagData.addAll(mOriginalTagData);
            mAdapter.notifyDataSetChanged();
        }
    }

    private void updateListHeader() {
        int totalTags = mCurrentState == AppState.READING ? mOriginalTagData.size() : mTagData.size();
        int selectedTags = mCurrentState == AppState.READING ? mTagData.size() : mAdapter.getSelectedCount();
        int totalReadings = getTotalHistoricalReadingsCount();
        
        switch (mCurrentState) {
            case IDLE:
                String headerText = String.format(Locale.getDefault(), "Temperature Tags (%d)", totalTags);
                if (totalReadings > 0) {
                    headerText += String.format(Locale.getDefault(), " - %d readings", totalReadings);
                }
                mListHeader.setText(headerText);
                if (selectedTags > 0) {
                    mSelectedCount.setText(String.format(Locale.getDefault(), "%d selected", selectedTags));
                    mSelectedCount.setVisibility(View.VISIBLE);
                } else {
                    mSelectedCount.setVisibility(View.GONE);
                }
                break;
            case SCANNING:
                mListHeader.setText(String.format(Locale.getDefault(), "Scanning... (%d found)", mTagData.size()));
                mSelectedCount.setVisibility(View.GONE);
                break;
            case READING:
                mListHeader.setText(String.format(Locale.getDefault(), "Reading Temperatures (%d)", selectedTags));
                mSelectedCount.setVisibility(View.GONE);
                break;
        }
    }
    
    private void updateReadButtonState() {
        // Only update button state when in IDLE mode
        if (mCurrentState == AppState.IDLE && mAdapter != null) {
            boolean hasSelection = mAdapter.getSelectedCount() > 0;
            boolean hasData = mTagData.size() > 0;
            mReadTempButton.setEnabled(hasSelection);
            mExportButton.setEnabled(hasData);
        }
    }

    private void toggleInventory() {
        try {
            if (mCurrentState == AppState.SCANNING) {
                mTemperatureController.stopInventory();
            } else {
                mTemperatureController.startInventory();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleReadButtonClick() {
        if (mCurrentState == AppState.READING) {
            mTemperatureController.stopSensorLoop();
        } else {
            readSelectedSensors();
        }
    }

    private void readSelectedSensors() {
        ArrayList<String> selectedEpc = mAdapter.getSelectedEpc();
        if (selectedEpc.isEmpty()) {
            Toast.makeText(getContext(), "No tags selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Mark selected tags as "Reading..." BEFORE filtering
        for (HashMap<String, String> map : mTagData) {
            if (Boolean.parseBoolean(map.get("isSelected"))) {
                map.put("result", "Reading...");
            }
        }
        mAdapter.notifyDataSetChanged();

        // Now update UI state (this will trigger filtering)
        updateUIState(AppState.READING);
        
        // Validate power level
        int apiPowerLevel = mSelectedPower;
        if (apiPowerLevel < 0 || apiPowerLevel > 30) {
            Toast.makeText(getContext(), "Invalid power level. Using default (max power).", Toast.LENGTH_SHORT).show();
            apiPowerLevel = 0;
        }
        
        mTemperatureController.readSensors(selectedEpc, apiPowerLevel);
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(TemperatureController.ACTION_TAG_FOUND);
        filter.addAction(TemperatureController.ACTION_INVENTORY_STATUS);
        filter.addAction(TemperatureController.ACTION_TEMP_READING_DONE);
        filter.addAction(TemperatureController.ACTION_TEMP_READING_FAILED);
        filter.addAction(TemperatureController.ACTION_SENSOR_LOOP_STATUS);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mTemperatureReceiver, filter);
    }

    private BroadcastReceiver mTemperatureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case TemperatureController.ACTION_TAG_FOUND:
                        String epc = intent.getStringExtra(TemperatureController.EXTRA_TAG_EPC);
                        String tid = intent.getStringExtra(TemperatureController.EXTRA_TAG_TID);
                        handleTagFound(epc, tid);
                        break;
                    case TemperatureController.ACTION_INVENTORY_STATUS:
                        handleInventoryStatus(intent.getBooleanExtra(TemperatureController.EXTRA_INVENTORY_RUNNING, false));
                        break;
                    case TemperatureController.ACTION_SENSOR_LOOP_STATUS:
                        handleSensorLoopStatus(intent.getBooleanExtra(TemperatureController.EXTRA_SENSOR_LOOP_RUNNING, false));
                        break;
                    case TemperatureController.ACTION_TEMP_READING_DONE:
                        handleTempRead(intent.getStringExtra(TemperatureController.EXTRA_READING_PAYLOAD));
                        break;
                    case TemperatureController.ACTION_TEMP_READING_FAILED:
                        handleTempFail(intent.getStringExtra(TemperatureController.EXTRA_READING_PAYLOAD));
                        break;
                }
            });
        }
    };
    
    public void handleTagFound(String epc, String tid) {
        if (epc == null) return;
        
        // Check if tag already exists
        for (HashMap<String, String> map : mTagData) {
            if (map.get("epc").equals(epc)) return;
        }
        
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        
        HashMap<String, String> map = new HashMap<>();
        map.put("epc", epc);
        map.put("tid", tid != null ? tid : "Unknown");
        map.put("result", "N/A");
        map.put("isSelected", "false");
        map.put("firstSeenTime", currentTime);
        map.put("lastReadTime", "");
        map.put("temperature", "");
        map.put("sensorCode", "");
        map.put("ocrssi", "");
        map.put("rssi", "");
        map.put("status", "Found");
        mTagData.add(map);
        
        // Initialize historical readings for this tag
        mHistoricalReadings.put(epc, new ArrayList<HashMap<String, String>>());
        
        mAdapter.notifyDataSetChanged();
        updateListHeader();
        updateReadButtonState(); // Update button state when new tags are found
    }

    public void handleInventoryStatus(boolean isRunning) {
        if (isRunning) {
            updateUIState(AppState.SCANNING);
            clearTagList();
        } else {
            updateUIState(AppState.IDLE);
        }
        updateReadButtonState(); // Update button state when inventory status changes
    }

    private void handleSensorLoopStatus(boolean isRunning) {
        if (!isRunning && mCurrentState == AppState.READING) {
            updateUIState(AppState.IDLE);
        }
    }

    private void handleTempRead(String payload) {
        if (payload == null) return;
        String[] parts = payload.split(";");
        if (parts.length < 5) return;  // Now expecting 5 parts: EPC, temp, rssi, ocrssi, sensorCode
        String epc = parts[0];
        String temp = String.format(Locale.US, "%.2f °C", Double.parseDouble(parts[1]));
        String rssi = parts[2] + " dBm";
        String ocrssi = "OCRSSI: " + parts[3];
        String sensorCode = "Code: " + parts[4];
        
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        // Update both current display and original data with all information
        updateTagResult(epc, temp + " / " + sensorCode + " / " + rssi + " / " + ocrssi);
        
        // Update individual fields for CSV export
        updateTagFields(epc, parts[1], parts[4], parts[3], parts[2], "Success", currentTime);
    }
    
    private void updateTagFields(String epc, String temperature, String sensorCode, String ocrssi, String rssi, String status, String readTime) {
        // Update current display
        for (HashMap<String, String> map : mTagData) {
            if (map.get("epc").equals(epc)) {
                map.put("temperature", temperature);
                map.put("sensorCode", sensorCode);
                map.put("ocrssi", ocrssi);
                map.put("rssi", rssi);
                map.put("status", status);
                map.put("lastReadTime", readTime);
                break;
            }
        }
        
        // Update original data if different
        if (mCurrentState == AppState.READING) {
            for (HashMap<String, String> map : mOriginalTagData) {
                if (map.get("epc").equals(epc)) {
                    map.put("temperature", temperature);
                    map.put("sensorCode", sensorCode);
                    map.put("ocrssi", ocrssi);
                    map.put("rssi", rssi);
                    map.put("status", status);
                    map.put("lastReadTime", readTime);
                    break;
                }
            }
        }
        
        // Add this reading to historical data
        addHistoricalReading(epc, temperature, sensorCode, ocrssi, rssi, status, readTime);
    }
    
    private void addHistoricalReading(String epc, String temperature, String sensorCode, String ocrssi, String rssi, String status, String readTime) {
        ArrayList<HashMap<String, String>> tagHistory = mHistoricalReadings.get(epc);
        if (tagHistory == null) {
            tagHistory = new ArrayList<>();
            mHistoricalReadings.put(epc, tagHistory);
        }
        
        HashMap<String, String> reading = new HashMap<>();
        reading.put("timestamp", readTime);
        reading.put("temperature", temperature);
        reading.put("sensorCode", sensorCode);
        reading.put("ocrssi", ocrssi);
        reading.put("rssi", rssi);
        reading.put("status", status);
        
        tagHistory.add(reading);
        
        // Update adapter with new historical data
        if (mAdapter != null) {
            mAdapter.setHistoricalReadings(mHistoricalReadings);
        }
        
        Log.d("TemperatureApp", "Added historical reading for " + epc + ": " + tagHistory.size() + " total readings");
    }

    private void handleTempFail(String payload) {
        if (payload == null) return;
        
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        
        String[] parts = payload.split(":", 2);
        if (parts.length > 1) {
            String epc = parts[0];
            String errorMessage = parts[1].trim();
            updateTagResult(epc, "Error: " + errorMessage);
            // Update individual fields for CSV export
            updateTagFields(epc, "", "", "", "", "Error: " + errorMessage, currentTime);
        } else {
            // General error
            mStatusTextView.setText("Error: " + payload);
        }
    }

    private void updateTagResult(String epc, String result) {
        // Update current display
        for (HashMap<String, String> map : mTagData) {
            if (map.get("epc").equals(epc)) {
                map.put("result", result);
                break;
            }
        }
        
        // Update original data if different
        if (mCurrentState == AppState.READING) {
            for (HashMap<String, String> map : mOriginalTagData) {
                if (map.get("epc").equals(epc)) {
                    map.put("result", result);
                    break;
                }
            }
        }
        
        mAdapter.notifyDataSetChanged();
    }
    
    private void clearTagList() {
        mTagData.clear();
        mOriginalTagData.clear();
        mHistoricalReadings.clear(); // Clear historical readings as well
        mAdapter.notifyDataSetChanged();
        updateListHeader();
        updateReadButtonState(); // Update button state when tag list is cleared
    }
    
    private void handleExportButtonClick() {
        if (mTagData.isEmpty()) {
            Toast.makeText(getContext(), "No temperature data to export", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (mCurrentState != AppState.IDLE) {
            Toast.makeText(getContext(), "Stop current operation before exporting", Toast.LENGTH_SHORT).show();
            return;
        }
        
        int totalReadings = getTotalHistoricalReadingsCount();
        int totalTags = mTagData.size();
        
        if (totalReadings == 0) {
            Toast.makeText(getContext(), String.format("Found %d tags but no temperature readings to export", totalTags), Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(getContext(), String.format("Exporting %d readings from %d tags...", totalReadings, totalTags), Toast.LENGTH_SHORT).show();
        exportTemperatureData();
    }
    
    private void exportTemperatureData() {
        try {
            String result = mTemperatureController.exportTemperatureHistoricalReadings(requireContext(), mTagData, mHistoricalReadings);
            if (result.isEmpty()) {
                int totalReadings = getTotalHistoricalReadingsCount();
                Toast.makeText(getContext(), "Temperature history exported successfully (" + totalReadings + " readings)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Export failed: " + result, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Export error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private int getTotalHistoricalReadingsCount() {
        int total = 0;
        for (ArrayList<HashMap<String, String>> tagHistory : mHistoricalReadings.values()) {
            total += tagHistory.size();
        }
        return total;
    }
} 