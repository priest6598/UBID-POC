package com.karnataka.ubid.model;

import java.time.LocalDate;

/**
 * A single record from one department system. The same real business
 * may appear as multiple BusinessRecord rows across different systems
 * with field-level variations.
 */
public record BusinessRecord(
        String recordId,           // department's own primary key
        String sourceSystem,       // SHOP_EST | FACTORIES | KSPCB | BWSSB
        String businessName,
        String address,
        String pinCode,
        String pan,                // may be null/blank
        String gstin,              // may be null/blank
        String sector,
        LocalDate registrationDate,
        // populated post-scrambling
        String scrambledName,
        String scrambledAddress,
        String scrambledPan,
        String scrambledGstin
) {
    public BusinessRecord withScrambled(String sn, String sa, String sp, String sg) {
        return new BusinessRecord(recordId, sourceSystem, businessName, address, pinCode,
                pan, gstin, sector, registrationDate, sn, sa, sp, sg);
    }
}
