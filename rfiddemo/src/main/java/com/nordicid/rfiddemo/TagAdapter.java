package com.nordicid.rfiddemo;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TagAdapter extends ArrayAdapter<HashMap<String, String>> {

    public interface OnSelectionChangedListener {
        void onSelectionChanged();
    }

    private LayoutInflater mInflater;
    private boolean mIsReadingMode = false;
    private Context mContext;
    private OnSelectionChangedListener mSelectionChangedListener;
    private HashMap<String, ArrayList<HashMap<String, String>>> mHistoricalReadings = new HashMap<>();

    public TagAdapter(Context context, List<HashMap<String, String>> data) {
        super(context, R.layout.temperature_list_row, data);
        mInflater = LayoutInflater.from(context);
        mContext = context;
    }
    
    public void setHistoricalReadings(HashMap<String, ArrayList<HashMap<String, String>>> historicalReadings) {
        mHistoricalReadings = historicalReadings;
        notifyDataSetChanged();
    }

    /**
     * Set whether the adapter is in reading mode.
     */
    public void setReadingMode(boolean isReading) {
        mIsReadingMode = isReading;
        notifyDataSetChanged();
    }

    public boolean isReadingMode() {
        return mIsReadingMode;
    }
    
    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        mSelectionChangedListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.temperature_list_row, parent, false);
            holder = new ViewHolder();
            holder.epcTextView = convertView.findViewById(R.id.tag_epc_text);
            holder.tidTextView = convertView.findViewById(R.id.tag_tid_text);
            holder.tempTextView = convertView.findViewById(R.id.tag_temp_text);
            holder.checkBox = convertView.findViewById(R.id.tag_checkbox);
            holder.checkBoxArea = (FrameLayout) convertView.findViewById(R.id.tag_checkbox).getParent(); // FrameLayout wrapper
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        HashMap<String, String> item = getItem(position);
        String epc = item.get("epc");
        String tid = item.get("tid");
        String result = item.get("result");
        boolean isSelected = Boolean.parseBoolean(item.get("isSelected"));

        // Format EPC - show only last 12 characters for compactness
        holder.epcTextView.setText(formatEpcCompact(epc));
        
        // Show TID during reading mode, hide during normal mode
        if (mIsReadingMode && tid != null && !tid.equals("Unknown")) {
            holder.tidTextView.setText(tid.length() > 16 ? tid.substring(0, 16) + "..." : tid);
            holder.tidTextView.setVisibility(View.VISIBLE);
        } else {
            holder.tidTextView.setVisibility(View.GONE);
        }
        
        // Format temperature and set status indicator color
        TempStatus status = formatTemperatureWithStatus(result);
        
        // Add historical reading count if available
        String temperatureText = status.temperature;
        ArrayList<HashMap<String, String>> tagHistory = mHistoricalReadings.get(epc);
        if (tagHistory != null && tagHistory.size() > 0) {
            temperatureText += " (" + tagHistory.size() + " readings)";
        }
        
        holder.tempTextView.setText(temperatureText);
        
        // Status indicator removed - no longer setting colors
        
        holder.checkBox.setChecked(isSelected);

        // In reading mode: disable checkboxes
        holder.checkBox.setEnabled(!mIsReadingMode);
        
        // Setup checkbox area click listener (better touch area)
        holder.checkBoxArea.setOnClickListener(v -> {
            if (!mIsReadingMode) {
                boolean currentState = holder.checkBox.isChecked();
                holder.checkBox.setChecked(!currentState);
                item.put("isSelected", String.valueOf(!currentState));
                
                // Notify the listener that selection changed
                if (mSelectionChangedListener != null) {
                    mSelectionChangedListener.onSelectionChanged();
                }
            }
        });

        return convertView;
    }

    private String formatEpcCompact(String epc) {
        if (epc == null || epc.length() <= 12) {
            return epc != null ? epc : "Unknown";
        }
        // Show only last 12 characters for compactness
        String compact = epc.substring(epc.length() - 12);
        // Add spaces every 4 characters for readability
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < compact.length(); i += 4) {
            if (i > 0) formatted.append(" ");
            formatted.append(compact.substring(i, Math.min(i + 4, compact.length())));
        }
        return formatted.toString();
    }
    


    private static class TempStatus {
        String temperature;
        String statusText;
        StatusType statusType;
        
        TempStatus(String temp, String status, StatusType type) {
            this.temperature = temp;
            this.statusText = status;
            this.statusType = type;
        }
    }
    
    private enum StatusType {
        COLD, NORMAL, WARM, HOT, ERROR, READING, UNKNOWN
    }

    private TempStatus formatTemperatureWithStatus(String result) {
        if (result == null || result.equals("N/A") || result.isEmpty()) {
            return new TempStatus("--°", "N/A", StatusType.UNKNOWN);
        }
        if (result.equals("Reading...")) {
            return new TempStatus("--°", "READING", StatusType.READING);
        }
        if (result.startsWith("Error:")) {
            return new TempStatus("ERR", "ERROR", StatusType.ERROR);
        }
        if (result.contains("°C")) {
            // Extract temperature value and additional sensor data
            String[] parts = result.split("/");
            if (parts.length > 0) {
                String tempPart = parts[0].trim();
                try {
                    // Extract numeric value
                    String numStr = tempPart.replace("°C", "").trim();
                    double temp = Double.parseDouble(numStr);
                    
                    // Determine status based on temperature
                    StatusType status;
                    String statusText;
                    if (temp < 10) {
                        status = StatusType.COLD;
                        statusText = "COLD";
                    } else if (temp > 30) {
                        status = StatusType.HOT;
                        statusText = "HOT";
                    } else if (temp > 25) {
                        status = StatusType.WARM;
                        statusText = "WARM";
                    } else {
                        status = StatusType.NORMAL;
                        statusText = "NORMAL";
                    }
                    
                    // Format temperature (remove decimal if .0)
                    String formattedTemp;
                    if (temp == (int) temp) {
                        formattedTemp = String.format("%.0f°", temp);
                    } else {
                        formattedTemp = String.format("%.1f°", temp);
                    }
                    
                    // Extract sensor code and OCRSSI if available
                    String additionalInfo = "";
                    if (parts.length >= 2) {
                        // Look for sensor code (Code: XXXX)
                        for (String part : parts) {
                            if (part.trim().startsWith("Code:")) {
                                String sensorCode = part.trim().substring(5).trim();
                                additionalInfo += " C:" + sensorCode;
                                break;
                            }
                        }
                        // Look for OCRSSI
                        for (String part : parts) {
                            if (part.trim().startsWith("OCRSSI:")) {
                                String ocrssi = part.trim().substring(7).trim();
                                additionalInfo += " O:" + ocrssi;
                                break;
                            }
                        }
                    }
                    
                    return new TempStatus(formattedTemp + additionalInfo, statusText, status);
                } catch (NumberFormatException e) {
                    return new TempStatus(tempPart, "UNKNOWN", StatusType.UNKNOWN);
                }
            }
        }
        return new TempStatus(result, "UNKNOWN", StatusType.UNKNOWN);
    }
    
    private int getStatusColor(StatusType status) {
        switch (status) {
            case COLD:
                return ContextCompat.getColor(mContext, R.color.temp_cold);
            case NORMAL:
                return ContextCompat.getColor(mContext, R.color.temp_normal);
            case WARM:
                return ContextCompat.getColor(mContext, R.color.temp_warm);
            case HOT:
                return ContextCompat.getColor(mContext, R.color.temp_hot);
            case ERROR:
                return ContextCompat.getColor(mContext, R.color.temp_error);
            case READING:
                return ContextCompat.getColor(mContext, R.color.temp_reading);
            default:
                return ContextCompat.getColor(mContext, R.color.temp_on_surface_variant);
        }
    }
    


    public ArrayList<String> getSelectedEpc() {
        ArrayList<String> selectedEpc = new ArrayList<>();
        for (int i = 0; i < getCount(); i++) {
            HashMap<String, String> item = getItem(i);
            if (Boolean.parseBoolean(item.get("isSelected"))) {
                selectedEpc.add(item.get("epc"));
            }
        }
        return selectedEpc;
    }

    public int getSelectedCount() {
        return getSelectedEpc().size();
    }

    /**
     * Get filtered data showing only selected tags (for reading mode)
     */
    public List<HashMap<String, String>> getSelectedTags() {
        List<HashMap<String, String>> selectedTags = new ArrayList<>();
        for (int i = 0; i < getCount(); i++) {
            HashMap<String, String> item = getItem(i);
            if (Boolean.parseBoolean(item.get("isSelected"))) {
                selectedTags.add(item);
            }
        }
        return selectedTags;
    }

    private static class ViewHolder {
        TextView epcTextView;
        TextView tidTextView;
        TextView tempTextView;
        CheckBox checkBox;
        FrameLayout checkBoxArea; // FrameLayout wrapper for better touch area
    }
} 