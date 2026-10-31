package com.laphuth.moodify.dto.offline;

import java.util.List;

public class OfflineBatchRequest {
    private String deviceUuid;
    private List<String> trackIds;
    private String deviceName;
    private String platform;

    public OfflineBatchRequest() {}

    public String getDeviceUuid() { return deviceUuid; }
    public void setDeviceUuid(String deviceUuid) { this.deviceUuid = deviceUuid; }

    public List<String> getTrackIds() { return trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
}
