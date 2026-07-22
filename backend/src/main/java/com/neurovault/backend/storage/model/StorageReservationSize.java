package com.neurovault.backend.storage.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Enumeration of allowed storage reservation sizes.
 * Host owners select one of these predefined sizes when reserving disk space
 * for the distributed storage network.
 */
public enum StorageReservationSize {

    MB_500("500 MB", 500L * 1024 * 1024),
    GB_1("1 GB", 1L * 1024 * 1024 * 1024),
    GB_2("2 GB", 2L * 1024 * 1024 * 1024),
    GB_5("5 GB", 5L * 1024 * 1024 * 1024),
    GB_10("10 GB", 10L * 1024 * 1024 * 1024),
    GB_20("20 GB", 20L * 1024 * 1024 * 1024);

    private final String displayName;
    private final long bytes;

    StorageReservationSize(String displayName, long bytes) {
        this.displayName = displayName;
        this.bytes = bytes;
    }

    /**
     * Returns the exact byte count for this reservation size.
     */
    public long getBytes() {
        return bytes;
    }

    /**
     * Returns a human-readable display name (e.g., "500 MB", "1 GB").
     */
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static StorageReservationSize fromString(Object input) {
        if (input == null) return GB_5;
        String val = input.toString().toUpperCase().trim();
        if (val.isEmpty()) return GB_5;
        if (val.contains("500MB") || val.equals("MB_500")) return MB_500;
        if (val.contains("10") || val.contains("LARGE")) return GB_10;
        if (val.contains("20")) return GB_20;
        if (val.contains("2") && !val.contains("20")) return GB_2;
        if (val.contains("1") && !val.contains("10") || val.contains("SMALL")) return GB_1;
        if (val.contains("5") && !val.contains("50") || val.contains("MEDIUM")) return GB_5;
        return GB_5;
    }
}
