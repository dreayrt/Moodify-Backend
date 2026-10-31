package com.laphuth.moodify.dto.offline;

public class OfflineConfirmRequest {
    private String downloadToken;
    private String fileHash;
    private Long fileSize;
    private String localPath;

    public OfflineConfirmRequest() {}

    public String getDownloadToken() { return downloadToken; }
    public void setDownloadToken(String downloadToken) { this.downloadToken = downloadToken; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
}
