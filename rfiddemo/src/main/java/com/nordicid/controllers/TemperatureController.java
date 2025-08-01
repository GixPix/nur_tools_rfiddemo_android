package com.nordicid.controllers;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.nordicid.nurapi.CustomExchangeParams;
import com.nordicid.nurapi.NurApi;
import com.nordicid.nurapi.NurIRConfig;
import com.nordicid.nurapi.NurInventoryExtended;
import com.nordicid.nurapi.NurInventoryExtendedFilter;
import com.nordicid.nurapi.NurRespInventory;
import com.nordicid.nurapi.NurTag;
import com.nordicid.nurapi.NurTagStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import android.os.Environment;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TemperatureController {

    public static final String ACTION_TAG_FOUND = "com.nordicid.controllers.TAG_FOUND";
    public static final String EXTRA_TAG_EPC = "com.nordicid.controllers.EXTRA_TAG_EPC";
    public static final String EXTRA_TAG_TID = "com.nordicid.controllers.EXTRA_TAG_TID";
    public static final String ACTION_INVENTORY_STATUS = "com.nordicid.controllers.INVENTORY_STATUS";
    public static final String EXTRA_INVENTORY_RUNNING = "com.nordicid.controllers.EXTRA_INVENTORY_RUNNING";
    public static final String ACTION_TEMP_READING_DONE = "com.nordicid.controllers.TEMP_READING_DONE";
    public static final String ACTION_TEMP_READING_FAILED = "com.nordicid.controllers.TEMP_READING_FAILED";
    public static final String EXTRA_READING_PAYLOAD = "com.nordicid.controllers.EXTRA_READING_PAYLOAD";
    public static final String ACTION_SENSOR_LOOP_STATUS = "com.nordicid.controllers.SENSOR_LOOP_STATUS";
    public static final String EXTRA_SENSOR_LOOP_RUNNING = "com.nordicid.controllers.EXTRA_SENSOR_LOOP_RUNNING";

    private static final String TAG = "TempController";

    private NurApi mApi;
    private boolean mInventoryRunning = false;
    private volatile boolean mSensorLoopRunning = false;
    private final NurTagStorage mTagStorage = new NurTagStorage();
    private final HashMap<String, TemperatureCalibration> mCalibrationCache = new HashMap<>();
    private LocalBroadcastManager mLocalBroadcastManager;

    public TemperatureController(Context context, NurApi api) {
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
        mApi = api;
    }

    // Called by TemperatureApp when inventory event occurs
    public void handleInventoryEvent(NurTagStorage tags) {
        // Target TID prefix for temperature sensors
        final String TARGET_TID_PREFIX = "E282403D000202DC01";
        
        for (int i=0; i < tags.size(); i++) {
            try {
                NurTag tag = tags.get(i);
                
                // Check if tag has TID data and matches our target prefix
                if (isValidTemperatureSensor(tag, TARGET_TID_PREFIX)) {
                    if (mTagStorage.addTag(tag)) {
                        String tidString = getTIDString(tag);
                        Log.d(TAG, "Valid temperature sensor found: EPC=" + tag.getEpcString() + 
                               ", TID=" + tidString);
                        Intent intent = new Intent(ACTION_TAG_FOUND);
                        intent.putExtra(EXTRA_TAG_EPC, tag.getEpcString());
                        intent.putExtra(EXTRA_TAG_TID, tidString);
                        mLocalBroadcastManager.sendBroadcast(intent);
                    }
                } else {
                    Log.d(TAG, "Filtered out tag: EPC=" + tag.getEpcString() + 
                           ", TID=" + getTIDString(tag) + " (doesn't match target prefix)");
                }
            } catch (Exception e) {
                Log.e(TAG, "handleTags: Error processing tag", e);
            }
        }
    }
    
    private boolean isValidTemperatureSensor(NurTag tag, String targetTidPrefix) {
        try {
            // Check if tag has IR data (TID)
            byte[] irData = tag.getIrData();
            if (irData == null || irData.length < 9) {
                return false; // Need at least 9 bytes for the prefix
            }
            
            // Convert TID bytes to hex string
            String tidHex = bytesToHexString(irData).toUpperCase();
            
            // Check if TID starts with target prefix
            return tidHex.startsWith(targetTidPrefix);
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking TID for tag " + tag.getEpcString(), e);
            return false;
        }
    }
    
    private String getTIDString(NurTag tag) {
        try {
            byte[] irData = tag.getIrData();
            if (irData != null && irData.length > 0) {
                return bytesToHexString(irData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting TID string", e);
        }
        return "No TID";
    }
    
    private String bytesToHexString(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public void startInventory() throws Exception {
        if (mInventoryRunning) return;
        mInventoryRunning = true;
        mTagStorage.clear();
        
        // Configure TID reading during inventory
        configureTIDReading();
        
        mApi.startInventoryStream();
        broadcastInventoryStatus(true);
    }
    
    private void configureTIDReading() throws Exception {
        // Configure Inventory Read (IR) to read TID bank during inventory
        NurIRConfig irConfig = new NurIRConfig();
        irConfig.IsRunning = true;
        irConfig.irType = NurApi.IRTYPE_EPCDATA;
        irConfig.irBank = NurApi.BANK_TID;  // Read TID bank
        irConfig.irAddr = 0;                // Start from address 0
        irConfig.irWordCount = 6;           // Read 6 words (12 bytes) to capture full TID prefix
        
        mApi.setIRConfig(irConfig);
        Log.d(TAG, "Configured TID reading during inventory: 6 words from TID bank");
    }

    public void stopInventory() {
        if (!mInventoryRunning) return;
        mInventoryRunning = false;
        try {
            if (mApi.isConnected()) {
                mApi.stopInventoryStream();
                
                // Disable TID reading configuration
                NurIRConfig irConfig = new NurIRConfig();
                irConfig.IsRunning = false;
                mApi.setIRConfig(irConfig);
            }
        } catch (Exception e) {
            Log.e(TAG, "stopInventory failed", e);
        }
        broadcastInventoryStatus(false);
    }

    public void stopSensorLoop() {
        mSensorLoopRunning = false;
    }

    public void readSensors(final ArrayList<String> epcsToRead, final int txLevel) {
        if (mInventoryRunning || mSensorLoopRunning) return;

        mSensorLoopRunning = true;
        broadcastSensorLoopStatus(true);

        new Thread(() -> {
            boolean txChanged = false;
            int originalTxLevel = 0;
            try {
                // First, ensure we have the selected tags in our storage
                // Do a quick inventory to populate mTagStorage with current tags
                Log.d(TAG, "Pre-reading inventory to populate storage with " + epcsToRead.size() + " selected tags");
                try {
                    mApi.clearIdBuffer();
                    mApi.inventory();
                    mApi.fetchTags();
                    
                    // Add found tags to our storage
                    NurTagStorage tempStorage = mApi.getStorage();
                    for (int i = 0; i < tempStorage.size(); i++) {
                        mTagStorage.addTag(tempStorage.get(i));
                    }
                    Log.d(TAG, "Storage now has " + mTagStorage.size() + " tags");
                } catch (Exception e) {
                    Log.w(TAG, "Pre-reading inventory failed: " + e.getMessage());
                }

                ArrayList<NurTag> tagsToRead = new ArrayList<>();
                // Filter mTagStorage to get only the NurTag objects for the selected EPCs
                for (int i=0; i<mTagStorage.size(); i++) {
                    try {
                        NurTag tag = mTagStorage.get(i);
                        if (epcsToRead.contains(tag.getEpcString())) {
                            tagsToRead.add(tag);
                            Log.d(TAG, "Found selected tag in storage: " + tag.getEpcString());
                        }
                    } catch(Exception ignored) {}
                }

                if (tagsToRead.isEmpty()) {
                    Log.e(TAG, "Selected tags not found in storage even after inventory. Expected EPCs: " + epcsToRead);
                    broadcastFailure("Selected tags not found. Make sure they are in range and try again.");
                    return;
                }

                originalTxLevel = mApi.getSetupTxLevel();
                if (originalTxLevel != txLevel) {
                    mApi.setSetupTxLevel(txLevel);
                    txChanged = true;
                }

                // Log adaptive timing configuration
                Log.d(TAG, "SIMPLIFIED TIMING: No additional delays - using only Nordic ID original sensor timing (3ms)");

                while (mSensorLoopRunning) {
                    for (NurTag tag : tagsToRead) {
                        if (!mSensorLoopRunning) break;

                        Log.d(TAG, "Attempting to read sensor for EPC: " + tag.getEpcString());
                        try {
                            TemperatureCalibration cal = mCalibrationCache.get(tag.getEpcString());
                            if (cal == null) {
                                Log.d(TAG, "Reading calibration data for " + tag.getEpcString());
                                short[] calWords = readMemBlockByEpc(tag, NurApi.BANK_USER, 8, 4);
                                cal = new TemperatureCalibration(calWords);
                                if (cal.valid) {
                                    Log.d(TAG, "CALIBRATION for " + tag.getEpcString() + ": Slope = " + cal.slope + ", Offset = " + cal.offset);
                                    mCalibrationCache.put(tag.getEpcString(), cal);
                                } else {
                                    Log.e(TAG, "CALIBRATION FAILED for " + tag.getEpcString());
                                }
                            }
                            
                            Log.d(TAG, "Reading sensor data for " + tag.getEpcString());
                            NurTag sensorTag = readSensor(mApi, tag);
                            if (sensorTag == null || sensorTag.getIrData() == null || sensorTag.getIrData().length < 3) {
                                throw new Exception("Sensor read returned incomplete data");
                            }
                            short[] sensorData = convertByteArrayToShortArray(sensorTag.getIrData());

                            int ocrssiCode = sensorData[1] & 0xFFFF;
                            int temperatureCode = sensorData[2] & 0xFFFF;
                            int rssi = sensorTag.getRssi();

                            Log.d(TAG, "RAW SENSOR DATA for " + tag.getEpcString() + ": OCRSSI Code = " + ocrssiCode + ", Temp Code = " + temperatureCode + ", RSSI = " + rssi);

                            // EXPANDED VALIDATIONS: Aligned with filter range (3-31) for maximum sensor utilization
                            if (ocrssiCode < 3) throw new Exception("Power too low for reliable reading (OCRSSI: " + ocrssiCode + ")");
                            if (ocrssiCode > 31) throw new Exception("Power too high for reliable reading (OCRSSI: " + ocrssiCode + ")");
                            
                            // Enhanced temperature code validation
                            if (temperatureCode < 1000 || temperatureCode > 4000) throw new Exception("Bad temp read (" + temperatureCode + ")");
                            
                            if (cal == null || !cal.valid) throw new Exception("Invalid calibration");

                            // RAW TEMPERATURE: Direct calculation without any filtering
                            double temperatureValue = cal.slope * temperatureCode + cal.offset;
                            
                            Log.d(TAG, "RAW TEMP for " + tag.getEpcString() + ": " + String.format("%.2f", temperatureValue) + "°C (no filtering applied)");
                            
                            // Send raw unfiltered temperature value with sensor code
                            String payload = String.format(Locale.US, "%s;%.2f;%d;%d;%d", tag.getEpcString(), temperatureValue, rssi, ocrssiCode, temperatureCode);
                            Intent intent = new Intent(ACTION_TEMP_READING_DONE);
                            intent.putExtra(EXTRA_READING_PAYLOAD, payload);
                            mLocalBroadcastManager.sendBroadcast(intent);
                            Log.d(TAG, "Successfully read EPC: " + tag.getEpcString());

                        } catch (Exception e) {
                            Log.e(TAG, "Failed to read sensor for EPC: " + tag.getEpcString(), e);
                            broadcastFailure(tag.getEpcString() + ": " + e.getMessage());
                        }
                        
                        // NO ADDITIONAL DELAYS - Testing performance without adaptive timing
                    }
                    
                    // NO INTER-ROUND DELAYS - Testing if they're actually needed
                }
            } catch (Exception e) {
                Log.e(TAG, "Generic error in readSensors loop", e);
                broadcastFailure(e.getMessage());
            } finally {
                if (txChanged) {
                    try { mApi.setSetupTxLevel(originalTxLevel); } catch (Exception e) { Log.e(TAG, "Failed to restore TX level", e); }
                }
                mSensorLoopRunning = false;
                broadcastSensorLoopStatus(false);
            }
        }).start();
    }

    /**
     * Calculate optimal inter-tag delay based on number of tags being read.
     * More tags require longer delays to reduce RF field instability and collisions.
     * 
     * @param tagCount Number of tags being read
     * @return Delay in milliseconds (50-200ms range)
     */
    private int calculateInterTagDelay(int tagCount) {
        if (tagCount <= 1) {
            return 50;  // Minimum delay for single tag
        }
        // Progressive increase: 50ms + (tagCount * 25ms), capped at 200ms
        int delay = 50 + (tagCount * 25);
        return Math.min(200, delay);
    }

    /**
     * Calculate optimal inter-round delay based on number of tags being read.
     * More tags require longer delays for thermal equilibrium and RF stabilization.
     * 
     * @param tagCount Number of tags being read
     * @return Delay in milliseconds (300-1000ms range)
     */
    private int calculateInterRoundDelay(int tagCount) {
        if (tagCount <= 1) {
            return 300;  // Minimum delay for single tag
        }
        // Progressive increase: 300ms + (tagCount * 100ms), capped at 1000ms
        int delay = 300 + (tagCount * 100);
        return Math.min(1000, delay);
    }

    private void broadcastInventoryStatus(boolean running) {
        Intent intent = new Intent(ACTION_INVENTORY_STATUS);
        intent.putExtra(EXTRA_INVENTORY_RUNNING, running);
        mLocalBroadcastManager.sendBroadcast(intent);
    }

    private void broadcastSensorLoopStatus(boolean running) {
        Intent intent = new Intent(ACTION_SENSOR_LOOP_STATUS);
        intent.putExtra(EXTRA_SENSOR_LOOP_RUNNING, running);
        mLocalBroadcastManager.sendBroadcast(intent);
    }

    private void broadcastFailure(String reason) {
        Intent intent = new Intent(ACTION_TEMP_READING_FAILED);
        intent.putExtra(EXTRA_READING_PAYLOAD, reason);
        mLocalBroadcastManager.sendBroadcast(intent);
    }

    private static void configureAntennas(NurApi reader, int[] antennas) throws Exception {
        int antennaMask = 0;
        for (int antenna: antennas) {
            antennaMask += 1 << (antenna - 1);
        }
        reader.setSetupAntennaMask(antennaMask);
        reader.setSetupAntennaMaskEx(antennaMask);
    }

    private static short[] readMemBlockByEpc(NurTag tag, int bank, int address, int length) throws Exception {
        NurApi api = tag.getAPI();
        byte[] epcBytes = tag.getEpc();
        NurInventoryExtendedFilter epcFilter = createInventoryExtendedSelect(NurApi.SESSION_SL, 0, NurApi.BANK_EPC, 0x20, epcBytes.length * 8, epcBytes);

        NurInventoryExtended invEx = new NurInventoryExtended();
        invEx.inventorySelState = NurApi.INVSELSTATE_SL;
        invEx.session = NurApi.SESSION_S0;
        invEx.Q = 1;

        NurIRConfig irConfig = new NurIRConfig();
        irConfig.irType = NurApi.IRTYPE_EPCDATA;
        irConfig.irBank = bank;
        irConfig.irAddr = address;
        irConfig.irWordCount = length;
        irConfig.IsRunning = true;
        
        short[] values = null;
        
        try {
            configureAntennas(api, new int[] { tag.getAntennaId() + 1 });
            api.setIRConfig(irConfig);
            for (int i=0; i < 3; i++) { // 3 attempts
                 api.clearIdBuffer();
                 NurRespInventory response = api.inventoryExtended(invEx, epcFilter);
                 if (response.numTagsFound > 0) {
                     api.fetchTags(true);
                     NurTagStorage tagStorage = api.getStorage();
                     if (tagStorage.size() > 0) {
                        for (int k=0; k < tagStorage.size(); k++) {
                            NurTag foundTag = tagStorage.get(k);
                            if (tag.getEpcString().equals(foundTag.getEpcString())) {
                                values = convertByteArrayToShortArray(foundTag.getIrData());
                                break;
                            }
                        }
                     }
                 }
                 if (values != null) break;
            }
        } finally {
            api.setIRState(false);
        }
        
        if (values == null) {
            throw new Exception("Tag not found or failed to read memory for calibration");
        }
        return values;
    }

    private static NurTag readSensor(NurApi api, NurTag tag) throws Exception {
        byte[] epcToRead = tag.getEpc();

        // CORRECTED FILTER LOGIC: Apply filters in correct order to avoid conflicts
        // 1. EPC filter first (action=0) - SELECT specific tag
        NurInventoryExtendedFilter epcFilter = createInventoryExtendedSelect(NurApi.SESSION_SL, 0, NurApi.BANK_EPC, 32, epcToRead.length * 8, epcToRead);
        
        // 2. OCRSSI filters (action=2) - DESELECT if power out of range (essential for sensor operation)
        byte ocrssiMin = 3;
        byte ocrssiMax = 31;
        NurInventoryExtendedFilter ocrssiMinFilter = createInventoryExtendedSelect(NurApi.SESSION_SL, 2, NurApi.BANK_USER, 0xD0, 8, new byte[] { (byte)(0x20 | (ocrssiMin - 1)) });
        NurInventoryExtendedFilter ocrssiMaxFilter = createInventoryExtendedSelect(NurApi.SESSION_SL, 2, NurApi.BANK_USER, 0xD0, 8, new byte[] { ocrssiMax });
        
        // 3. TID Filter (action=2) - DESELECT if not Magnus S3
        NurInventoryExtendedFilter tidFilter = createInventoryExtendedSelect(NurApi.SESSION_SL, 2, NurApi.BANK_TID, 0x00, 28, new byte[] { (byte)0xE2, (byte)0x82, (byte)0x40, (byte)0x30 });
        
        // Apply filters in logical order: SELECT specific EPC, then DESELECT based on conditions
        NurInventoryExtendedFilter[] filters = new NurInventoryExtendedFilter[] { epcFilter, ocrssiMinFilter, ocrssiMaxFilter, tidFilter };

        CustomExchangeParams temperatureEnable = createCustomExchangeSelect(NurApi.SESSION_SL, 5, NurApi.BANK_USER, 0xE0, 0, new byte[] { });

        NurIRConfig irConfig = new NurIRConfig();
        irConfig.irType = NurApi.IRTYPE_EPCDATA;
        irConfig.irBank = NurApi.BANK_PASSWD;
        irConfig.irAddr = 0xC;
        irConfig.irWordCount = 3;
        irConfig.IsRunning = true;
        
        NurInventoryExtended invEx = new NurInventoryExtended();
        invEx.inventorySelState = NurApi.INVSELSTATE_SL;
        invEx.session = NurApi.SESSION_S0;
        
        // IMPROVED: Removed api.inventory() call to prevent storage contamination
        // This eliminates the issue where all nearby tags were added to storage
        // before the filtered inventory, causing wrong tag selection
        Log.d("TempController", "TARGET EPC for sensor read: " + tag.getEpcString());
        api.clearIdBuffer();
        
        api.setIRConfig(irConfig);
        
        NurTag sensorTag = null;
        
        try {
            api.setExtendedCarrier(true);
            api.customExchange(NurApi.BANK_USER, 0, 0, new byte[0], temperatureEnable);
            // TIMING: Optimized timing as per Nordic ID original (3ms for CW while Temperature Sensor runs)
            Thread.sleep(3); // Aligned with Nordic ID original timing
            NurRespInventory response = api.inventoryExtended(invEx, filters, filters.length);
            
            Log.d("TempController", "INVENTORY EXTENDED result: " + response.numTagsFound + " tags found by filters");
            
            if (response.numTagsFound > 0) {
                api.fetchTags(true);
                NurTagStorage tagStorage = api.getStorage();
                Log.d("TempController", "STORAGE after fetchTags: " + tagStorage.size() + " tags in storage");
                
                if (tagStorage.size() > 0) {
                    // Enhanced verification: find the exact tag we're looking for
                    for (int i = 0; i < tagStorage.size(); i++) {
                        NurTag foundTag = tagStorage.get(i);
                        Log.d("TempController", "  Tag " + i + " in storage: " + foundTag.getEpcString());
                        if (tag.getEpcString().equals(foundTag.getEpcString())) {
                            sensorTag = foundTag;
                            Log.d("TempController", "PERFECT MATCH: Selected target tag " + foundTag.getEpcString());
                            break;
                        }
                    }
                    
                    // Fallback: if exact match not found, take the first (should not happen with proper filtering)
                    if (sensorTag == null) {
                        sensorTag = tagStorage.get(0);
                        Log.w("TempController", "NO EXACT MATCH: Using first tag " + sensorTag.getEpcString() + " instead of target " + tag.getEpcString());
                    }
                }
            } else {
                Log.w("TempController", "No tags found with filters for target EPC: " + tag.getEpcString());
            }
        } finally {
             api.setExtendedCarrier(false);
             irConfig.IsRunning = false;
             api.setIRConfig(irConfig);
        }
        
        if(sensorTag != null) {
            Log.d("TempController", "SENSOR READ SUCCESS: Returning tag " + sensorTag.getEpcString());
            return sensorTag;
        }

        throw new Exception("Sensor read failed");
    }

    private static NurInventoryExtendedFilter createInventoryExtendedSelect(int target, int action, int bank, int pointer, int length, byte[] mask){
        NurInventoryExtendedFilter select = new NurInventoryExtendedFilter();
        select.targetSession = target;
        select.action = action;
        select.bank = bank;
        select.address = pointer;
        select.maskBitLength = length;
        select.maskdata = mask;
        select.truncate = false;
        return select;
    }

    private static CustomExchangeParams createCustomExchangeSelect(int target, int action, int bank, int pointer, int length, byte[] mask) {
        CustomExchangeParams select = new CustomExchangeParams();
        try {
            select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, 0xA, 4, 0);
            select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, (long)target, 3, select.txLen);
            select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, (long)action, 3, select.txLen);
            select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, (long)bank, 2, select.txLen);
            select.txLen = NurApi.bitBufferAddEBV32(select.bitBuffer, pointer, select.txLen);
            select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, (long)length, 8, select.txLen);
            for (byte mask_byte: mask) {
                int bits = 8;
                if (length < 8) {
                    bits = length;
                    mask_byte = (byte)(mask_byte >> (8 - bits));
                }
                select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, mask_byte, bits, select.txLen);
                length -= bits;
            }
            select.txLen = NurApi.bitBufferAddValue(select.bitBuffer, 0x0, 1, select.txLen);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        select.asWrite = true;
        select.txOnly = true;
        select.noTxCRC = false;
        select.rxLen = 0;
        select.rxTimeout = 20;
        select.appendHandle = false;
        select.xorRN16 = false;
        select.noRxCRC = false;
        select.rxLenUnknown = false;
        select.txCRC5 = false;
        select.rxStripHandle = false;
        
        return select;
    }

    private static class TemperatureCalibration {
        final boolean valid;
        final double slope;
        final double offset;

        TemperatureCalibration(short[] calWords) {
            if (calWords == null || calWords.length < 4) {
                valid = false; slope = 0; offset = 0;
                return;
            }

            // Decode calibration words using the exact logic from the Magnus-Studio example
            short reg8 = calWords[0];
            short reg9 = calWords[1];
            short regA = calWords[2];
            short regB = calWords[3];
            
            int ver = regB & 0x0003;
            double temp2 = .1 * ((regB >> 2) & 0x07FF) - 80;
            int code2 = ((regA << 3) & 0x0FF8) | ((regB >> 13) & 0x0007);
            double temp1 = .1 * (((reg9 << 7) & 0x0780) | ((regA >> 9) & 0x007F)) - 80;
            int code1 = (reg9 >> 4) & 0x0FFF;
            int crc = reg8 & 0xFFFF;

            // Calculate CRC-16 over non-CRC bytes to compare with stored CRC-16
            byte[] calBytes = convertShortArrayToByteArray(new short[] {calWords[1], calWords[2], calWords[3]});
            int crcCalc = crc16(calBytes);

            if ((ver == 0) && (crc == crcCalc) && (code1 != code2)) {
                slope = (temp2 - temp1) / (double)(code2 - code1);
                offset = temp1 - (slope * (double)code1);
                valid = true;
            }
            else {
                valid = false;
                slope = 0;
                offset = 0;
            }
        }

        // EPC Gen2 CRC-16 Algorithm - from Magnus-Studio example
        private int crc16(byte[] inputBytes) {
            int crcVal = 0xFFFF;
            for (byte inputByte: inputBytes) {
                crcVal = (crcVal ^ (inputByte << 8));
                for (int i = 0; i < 8; i++) {
                    if ((crcVal & 0x8000) == 0x8000)
                    {
                        crcVal = (crcVal << 1) ^ 0x1021;
                    }
                    else
                    {
                        crcVal = (crcVal << 1);
                    }
                }
                crcVal = crcVal & 0xFFFF;
            }
            crcVal = (crcVal ^ 0xFFFF);
            return crcVal;
        }
    }

    // Helper method to convert short array to byte array for CRC calculation
    private static byte[] convertShortArrayToByteArray(short[] shortArray) {
        byte[] byteArray = new byte[shortArray.length * 2];
        for (int i = 0; i < shortArray.length; i++) {
            byteArray[2 * i] = (byte)((shortArray[i] >> 8) & 0xFF);
            byteArray[2 * i + 1] = (byte)(shortArray[i] & 0xFF);
        }
        return byteArray;
    }

    private static short[] convertByteArrayToShortArray(byte[] byteArray) {
        if (byteArray == null) return new short[0];
        short[] shortArray = new short[byteArray.length / 2];
        for (int i = 0; i < shortArray.length; i++) {
            shortArray[i] = (short) (((byteArray[2 * i] & 0xFF) << 8) | (byteArray[2 * i + 1] & 0xFF));
        }
        return shortArray;
    }
    
    public String exportTemperatureReadings(Context context, ArrayList<HashMap<String, String>> temperatureData) {
        try {
            String postfix = "";
            if (mApi.isConnected()) {
                try {
                    postfix = mApi.getReaderInfo().altSerial;
                    if (postfix.length() == 0) {
                        postfix = mApi.getReaderInfo().serial;
                    }
                    if (postfix.length() != 0) {
                        postfix += "_";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not get reader info for filename: " + e.getMessage());
                }
            }

            String filename = "temperature_export_";
            filename += postfix;
            filename += new SimpleDateFormat("ddMMyyyyHHmmss", Locale.getDefault()).format(new Date());
            filename += ".csv";

            // Use Downloads directory (default behavior like InventoryController)
            File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File outputFile = new File(path, filename);
            outputFile.createNewFile();

            FileWriter fileWriter = new FileWriter(outputFile, false);
            
            // Write CSV header
            fileWriter.write("firstseen;lastread;epc;tid;temperature_celsius;sensor_code;ocrssi;rssi_dbm;status\n");

            // Write data rows
            for (HashMap<String, String> tag : temperatureData) {
                fileWriter.append(getStringValue(tag, "firstSeenTime") + ";");
                fileWriter.append(getStringValue(tag, "lastReadTime") + ";");
                fileWriter.append(getStringValue(tag, "epc") + ";");
                fileWriter.append(getStringValue(tag, "tid") + ";");
                fileWriter.append(getStringValue(tag, "temperature") + ";");
                fileWriter.append(getStringValue(tag, "sensorCode") + ";");
                fileWriter.append(getStringValue(tag, "ocrssi") + ";");
                fileWriter.append(getStringValue(tag, "rssi") + ";");
                fileWriter.append(getStringValue(tag, "status") + "\n");
            }
            
            fileWriter.close();
            Log.i(TAG, "Temperature data exported to: " + outputFile.getAbsolutePath());
            return ""; // Success
            
        } catch (Exception e) {
            Log.e(TAG, "Error exporting temperature data", e);
            return e.getMessage();
        }
    }
    
    private String getStringValue(HashMap<String, String> map, String key) {
        String value = map.get(key);
        return (value != null) ? value : "";
    }
    
    public String exportTemperatureHistoricalReadings(Context context, ArrayList<HashMap<String, String>> tagData, HashMap<String, ArrayList<HashMap<String, String>>> historicalReadings) {
        try {
            String postfix = "";
            if (mApi.isConnected()) {
                try {
                    postfix = mApi.getReaderInfo().altSerial;
                    if (postfix.length() == 0) {
                        postfix = mApi.getReaderInfo().serial;
                    }
                    if (postfix.length() != 0) {
                        postfix += "_";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not get reader info for filename: " + e.getMessage());
                }
            }

            String filename = "temperature_history_";
            filename += postfix;
            filename += new SimpleDateFormat("ddMMyyyyHHmmss", Locale.getDefault()).format(new Date());
            filename += ".csv";

            // Use Downloads directory
            File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File outputFile = new File(path, filename);
            outputFile.createNewFile();

            FileWriter fileWriter = new FileWriter(outputFile, false);
            
            // Write CSV header with additional columns for historical data
            fileWriter.write("epc;tid;timestamp;temperature_celsius;sensor_code;ocrssi;rssi_dbm;status;reading_number;total_readings_for_tag\n");

            // Write all historical readings for each tag
            int totalReadingsExported = 0;
            for (HashMap<String, String> tag : tagData) {
                String epc = getStringValue(tag, "epc");
                String tid = getStringValue(tag, "tid");
                
                ArrayList<HashMap<String, String>> tagHistory = historicalReadings.get(epc);
                if (tagHistory != null && !tagHistory.isEmpty()) {
                    int readingNumber = 1;
                    int totalReadingsForTag = tagHistory.size();
                    
                    for (HashMap<String, String> reading : tagHistory) {
                        fileWriter.append(epc + ";");
                        fileWriter.append(tid + ";");
                        fileWriter.append(getStringValue(reading, "timestamp") + ";");
                        fileWriter.append(getStringValue(reading, "temperature") + ";");
                        fileWriter.append(getStringValue(reading, "sensorCode") + ";");
                        fileWriter.append(getStringValue(reading, "ocrssi") + ";");
                        fileWriter.append(getStringValue(reading, "rssi") + ";");
                        fileWriter.append(getStringValue(reading, "status") + ";");
                        fileWriter.append(readingNumber + ";");
                        fileWriter.append(totalReadingsForTag + "\n");
                        
                        readingNumber++;
                        totalReadingsExported++;
                    }
                } else {
                    // Tag found but no readings taken - export basic info
                    fileWriter.append(epc + ";");
                    fileWriter.append(tid + ";");
                    fileWriter.append(getStringValue(tag, "firstSeenTime") + ";");
                    fileWriter.append(";;;;;;"); // Empty temperature data
                    fileWriter.append("Found - No readings;");
                    fileWriter.append("0;0\n");
                }
            }
            
            fileWriter.close();
            Log.i(TAG, "Temperature history exported to: " + outputFile.getAbsolutePath() + " (" + totalReadingsExported + " readings)");
            return ""; // Success
            
        } catch (Exception e) {
            Log.e(TAG, "Error exporting temperature history", e);
            return e.getMessage();
        }
    }
} 