package com.laphuth.moodify.dto.offline;

import java.util.List;

public class OfflineVerifyRequest {
    private String deviceUuid;
    private List<String> trackIds;

    public OfflineVerifyRequest() {}

    public String getDeviceUuid() { return deviceUuid; }
    public void setDeviceUuid(String deviceUuid) { this.deviceUuid = deviceUuid; }

    public List<String> getTrackIds() { return trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }
}
