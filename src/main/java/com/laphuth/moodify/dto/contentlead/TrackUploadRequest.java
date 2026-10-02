package com.laphuth.moodify.dto.contentlead;

public class TrackUploadRequest {
    private String title;
    private String genre;
    private String featuredArtists;
    private String albumName;
    private String status = "draft";
    private String visibility = "public";
    private boolean explicit;
    private String lyricsPlain;
    private String description;

    // License Info
    private String licenseType = "DIGITAL_STREAMING";
    private String copyrightOwner;
    private Long distributorId;
    private Long distributionContractId;
    private String issueDate;
    private String expiryDate;
    private boolean isPerpetual;

    // Getters and Setters
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public String getFeaturedArtists() { return featuredArtists; }
    public void setFeaturedArtists(String featuredArtists) { this.featuredArtists = featuredArtists; }

    public String getAlbumName() { return albumName; }
    public void setAlbumName(String albumName) { this.albumName = albumName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public boolean isExplicit() { return explicit; }
    public void setExplicit(boolean explicit) { this.explicit = explicit; }

    public String getLyricsPlain() { return lyricsPlain; }
    public void setLyricsPlain(String lyricsPlain) { this.lyricsPlain = lyricsPlain; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLicenseType() { return licenseType; }
    public void setLicenseType(String licenseType) { this.licenseType = licenseType; }

    public String getCopyrightOwner() { return copyrightOwner; }
    public void setCopyrightOwner(String copyrightOwner) { this.copyrightOwner = copyrightOwner; }

    public Long getDistributorId() { return distributorId; }
    public void setDistributorId(Long distributorId) { this.distributorId = distributorId; }

    public Long getDistributionContractId() { return distributionContractId; }
    public void setDistributionContractId(Long distributionContractId) { this.distributionContractId = distributionContractId; }

    public String getIssueDate() { return issueDate; }
    public void setIssueDate(String issueDate) { this.issueDate = issueDate; }

    public String getExpiryDate() { return expiryDate; }
    public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }

    public boolean isPerpetual() { return isPerpetual; }
    public void setPerpetual(boolean perpetual) { isPerpetual = perpetual; }
}
