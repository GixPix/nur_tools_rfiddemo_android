package com.nordicid.rfiddemo;

import com.nordicid.nurapi.*;

public class NurApiListenerAdapter implements NurApiListener {
    @Override public void connectedEvent() {}
    @Override public void disconnectedEvent() {}
    @Override public void bootEvent(String s) {}
    @Override public void inventoryStreamEvent(NurEventInventory ev) {}
    @Override public void IOChangeEvent(NurEventIOChange e) {}
    @Override public void traceTagEvent(NurEventTraceTag e) {}
    @Override public void frequencyHopEvent(NurEventFrequencyHop e) {}
    @Override public void debugMessageEvent(String s) {}
    @Override public void inventoryExtendedStreamEvent(NurEventInventory e) {}
    @Override public void programmingProgressEvent(NurEventProgrammingProgress e) {}
    @Override public void deviceSearchEvent(NurEventDeviceInfo e) {}
    @Override public void clientConnectedEvent(NurEventClientInfo e) {}
    @Override public void clientDisconnectedEvent(NurEventClientInfo e) {}
    @Override public void epcEnumEvent(NurEventEpcEnum e) {}
    @Override public void autotuneEvent(NurEventAutotune e) {}
    @Override public void logEvent(int i, String s) {}
    @Override public void nxpEasAlarmEvent(NurEventNxpAlarm e) {}
    @Override public void tagTrackingScanEvent(NurEventTagTrackingData e) {}
    @Override public void tagTrackingChangeEvent(NurEventTagTrackingChange e) {}
    
    // Adding variations I've seen in errors to be safe.
    public void txLevelEvent(int i) {}
    public void txLevelEvent(int i, int i1) {}
    public void triggeredReadEvent(com.nordicid.nurapi.NurEventTriggeredRead nurEventTriggeredRead) {}

} 