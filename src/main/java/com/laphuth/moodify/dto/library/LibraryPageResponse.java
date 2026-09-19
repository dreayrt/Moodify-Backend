package com.laphuth.moodify.dto.library;

import java.util.List;

public class LibraryPageResponse {
    private List<LibraryTrackResponse> tracks;
    private int currentPage;
    private int totalPages;
    private long totalElements;
    private int pageSize;

    public LibraryPageResponse() {
    }

    public LibraryPageResponse(
        List<LibraryTrackResponse> tracks,
        int currentPage,
        int totalPages,
        long totalElements,
        int pageSize
    ) {
        this.tracks = tracks;
        this.currentPage = currentPage;
        this.totalPages = totalPages;
        this.totalElements = totalElements;
        this.pageSize = pageSize;
    }

    public List<LibraryTrackResponse> getTracks() {
        return tracks;
    }

    public void setTracks(List<LibraryTrackResponse> tracks) {
        this.tracks = tracks;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
