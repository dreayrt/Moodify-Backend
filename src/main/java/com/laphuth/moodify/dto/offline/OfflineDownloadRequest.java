package com.laphuth.moodify.dto.offline;

public class OfflineDownloadRequest {
    private String trackId;
    private String deviceUuid;
    private String deviceName;
    private String platform;

    public OfflineDownloadRequest() {}

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getDeviceUuid() { return deviceUuid; }
    public void setDeviceUuid(String deviceUuid) { this.deviceUuid = deviceUuid; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
}
