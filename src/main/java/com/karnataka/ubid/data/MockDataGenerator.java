package com.karnataka.ubid.data;

import com.karnataka.ubid.model.BusinessRecord;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates mock master data across 4 Karnataka department systems.
 *
 * Each "canonical business" is seeded once, then projected into 1..4 systems
 * with field-level noise applied per projection — exactly the cross-system
 * fragmentation pattern the platform needs to resolve.
 *
 * Random seed is fixed so the demo is reproducible.
 */
public final class MockDataGenerator {

    public static final String SHOP_EST = "SHOP_EST";
    public static final String FACTORIES = "FACTORIES";
    public static final String KSPCB = "KSPCB";
    public static final String BWSSB = "BWSSB";

    private static final String[] SYSTEMS = {SHOP_EST, FACTORIES, KSPCB, BWSSB};

    private final Random rng;

    public MockDataGenerator(long seed) {
        this.rng = new Random(seed);
    }

    public List<BusinessRecord> generate() {
        List<CanonicalBusiness> businesses = seedCanonicalBusinesses();
        List<BusinessRecord> records = new ArrayList<>();
        int counter = 1;

        for (CanonicalBusiness biz : businesses) {
            // Each canonical business appears in 1..4 systems
            int systemCount = 1 + rng.nextInt(4);
            List<String> chosenSystems = pickSystems(systemCount);

            for (String system : chosenSystems) {
                records.add(project(biz, system, counter++));
            }
        }

        // Add some "lone" records that don't have duplicates anywhere — to
        // verify the matcher doesn't over-link.
        for (int i = 0; i < 8; i++) {
            CanonicalBusiness solo = soloBusiness(i);
            records.add(project(solo, SYSTEMS[rng.nextInt(SYSTEMS.length)], counter++));
        }

        // Ambiguous cases: same business across 2 systems but with NO PAN/GSTIN
        // in either — forces the matcher to lean on name + address + pin, which
        // (for typo'd / variant names) lands the pair in the REVIEW zone.
        records.addAll(ambiguousCases(counter));

        return records;
    }

    /**
     * Hand-crafted ambiguous pairs — these exist to demonstrate the human-review
     * loop. Without identifier anchors, the matcher must lean on noisy fields
     * and produce a probability that falls between the auto-link and no-link
     * thresholds.
     */
    private List<BusinessRecord> ambiguousCases(int startCounter) {
        List<BusinessRecord> out = new ArrayList<>();
        int c = startCounter;

        // Pair 1 — typo-level name drift, same pin code, no PAN
        out.add(new BusinessRecord(SHOP_EST + "-" + String.format("%05d", c++),
                SHOP_EST, "Shree Annapurna Sweets",
                "44 Jayanagar 4th Block, Bangalore", "560011",
                null, null, "Food Processing",
                LocalDate.of(2018, 6, 12), null, null, null, null));
        out.add(new BusinessRecord(BWSSB + "-" + String.format("%05d", c++),
                BWSSB, "Sri Annapoorna Sweets",
                "44 Jayanagar 4th Blk, Bangalore", "560011",
                null, null, "Food Processing",
                LocalDate.of(2018, 7, 3), null, null, null, null));

        // Pair 2 — VERY similar names but conflicting addresses and dates,
        // no anchor identifiers. Genuinely ambiguous — could be the same
        // business that moved, or two different businesses with similar names.
        // A reviewer must disambiguate.
        out.add(new BusinessRecord(SHOP_EST + "-" + String.format("%05d", c++),
                SHOP_EST, "Sharma Stationery",
                "12 BTM 1st Stage, Bangalore", "560029",
                null, null, "Retail",
                LocalDate.of(2016, 4, 15), null, null, null, null));
        out.add(new BusinessRecord(KSPCB + "-" + String.format("%05d", c++),
                KSPCB, "Sharma Stationary",  // typo'd, different pin
                "78 Jayanagar 9th Block, Bangalore", "560069",
                null, null, "Retail",
                LocalDate.of(2019, 11, 8), null, null, null, null));

        // Pair 3 — calibrated to land in REVIEW zone. Same first token
        // (metaphone match), high name JW, but conflicting pin AND
        // divergent addresses. Could be the same business with two outlets,
        // or two unrelated businesses with similar names — needs a human.
        out.add(new BusinessRecord(SHOP_EST + "-" + String.format("%05d", c++),
                SHOP_EST, "Ravi Auto Parts",
                "5 Banashankari 2nd Stage, Bangalore", "560050",
                null, null, "Automotive",
                LocalDate.of(2017, 3, 10), null, null, null, null));
        out.add(new BusinessRecord(FACTORIES + "-" + String.format("%05d", c++),
                FACTORIES, "Ravi Auto Spares",
                "12 Bannerghatta Road, Bangalore", "560078",
                null, null, "Automotive",
                LocalDate.of(2017, 8, 15), null, null, null, null));

        return out;
    }

    private List<String> pickSystems(int n) {
        List<String> all = new ArrayList<>(List.of(SYSTEMS));
        java.util.Collections.shuffle(all, rng);
        return all.subList(0, Math.min(n, all.size()));
    }

    private BusinessRecord project(CanonicalBusiness biz, String system, int counter) {
        String name = noisyName(biz.name, system);
        String address = noisyAddress(biz.address, system);

        // Different systems may capture different identifiers
        String pan = biz.pan;
        String gstin = biz.gstin;
        if (system.equals(BWSSB) && rng.nextDouble() < 0.6) {
            pan = null;        // utility records often miss PAN
            gstin = null;
        } else if (system.equals(SHOP_EST) && rng.nextDouble() < 0.25) {
            gstin = null;      // older shop est records pre-GST
        }

        // Mild registration date drift across systems (different first-touch dates)
        LocalDate regDate = biz.registrationDate.plusDays(rng.nextInt(120) - 60);

        String recordId = system + "-" + String.format("%05d", counter);
        return new BusinessRecord(
                recordId, system, name, address, biz.pinCode,
                pan, gstin, biz.sector, regDate,
                null, null, null, null
        );
    }

    private String noisyName(String name, String system) {
        // System-specific naming conventions create natural variation
        double r = rng.nextDouble();
        return switch (system) {
            case SHOP_EST -> r < 0.4 ? "M/s " + name : name;
            case FACTORIES -> r < 0.5 ? name + " Pvt Ltd" : (r < 0.7 ? name + " Pvt. Ltd." : name);
            case KSPCB -> r < 0.3 ? abbreviate(name) : name;
            case BWSSB -> r < 0.3 ? name.toUpperCase() : name;
            default -> name;
        };
    }

    private String abbreviate(String name) {
        // KSPCB sometimes records short forms — e.g. "Ramesh Textiles" -> "Ramesh Tex"
        return name.replace("Textiles", "Tex")
                   .replace("Industries", "Ind")
                   .replace("Engineering", "Engg")
                   .replace("Manufacturing", "Mfg");
    }

    private String noisyAddress(String addr, String system) {
        double r = rng.nextDouble();
        if (r < 0.2) {
            // abbreviation
            return addr.replace("Road", "Rd").replace("Cross", "Cr");
        } else if (r < 0.35) {
            // missing component
            int comma = addr.indexOf(',');
            return comma > 0 ? addr.substring(comma + 1).trim() : addr;
        } else {
            return addr;
        }
    }

    /* ------------------------- canonical seed data ------------------------- */

    private List<CanonicalBusiness> seedCanonicalBusinesses() {
        List<CanonicalBusiness> list = new ArrayList<>();

        // Pin code 560001 — central Bangalore
        list.add(new CanonicalBusiness("Ramesh Textiles", "12 MG Road, Bangalore",
                "560001", "AAACR1234K", "29AAACR1234K1Z5", "Textiles", LocalDate.of(2015, 3, 14)));
        list.add(new CanonicalBusiness("Bharat Industries", "45 Brigade Road, Bangalore",
                "560001", "AABCB5678L", "29AABCB5678L1Z9", "Manufacturing", LocalDate.of(2010, 7, 22)));
        list.add(new CanonicalBusiness("Krishna Engineering Works", "8 Commercial Street, Bangalore",
                "560001", "AAGCK9012M", "29AAGCK9012M1Z3", "Engineering", LocalDate.of(2018, 1, 5)));
        list.add(new CanonicalBusiness("Sunrise Foods", "23 St Marks Road, Bangalore",
                "560001", "AADCS3456N", "29AADCS3456N1Z7", "Food Processing", LocalDate.of(2019, 11, 30)));

        // Pin code 560068 — Electronic City
        list.add(new CanonicalBusiness("Vikram Manufacturing", "Plot 14 Phase 1, Electronic City",
                "560068", "AAFCV7890P", "29AAFCV7890P1Z2", "Electronics", LocalDate.of(2012, 6, 18)));
        list.add(new CanonicalBusiness("Ganesh Industries", "Plot 22 Phase 2, Electronic City",
                "560068", "AAHCG1122Q", "29AAHCG1122Q1Z6", "Industrial", LocalDate.of(2008, 4, 9)));
        list.add(new CanonicalBusiness("Bharath Steel Works", "Plot 9 Phase 1, Electronic City",
                "560068", "AAJCB3344R", "29AAJCB3344R1Z8", "Steel", LocalDate.of(2014, 9, 12)));

        // Pin code 560100 — Sarjapur
        list.add(new CanonicalBusiness("Anand Auto Components", "Sarjapur Main Road, Bangalore",
                "560100", "AAKCA5566S", "29AAKCA5566S1Z4", "Automotive", LocalDate.of(2016, 2, 28)));
        list.add(new CanonicalBusiness("Lakshmi Foods", "12 Wipro Junction, Sarjapur",
                "560100", "AALCL7788T", "29AALCL7788T1Z1", "Food Processing", LocalDate.of(2017, 8, 17)));
        list.add(new CanonicalBusiness("Mahesh Polymers", "5 Doddakannelli, Sarjapur",
                "560100", "AAMCM9900U", "29AAMCM9900U1Z0", "Plastics", LocalDate.of(2013, 12, 3)));

        // Hard cases — similar names but DIFFERENT businesses
        list.add(new CanonicalBusiness("Bharat Trading Co", "78 KR Market, Bangalore",
                "560002", "AANCB1234V", "29AANCB1234V1Z9", "Trading", LocalDate.of(2011, 5, 20)));
        list.add(new CanonicalBusiness("Bharath Electricals", "Plot 30 Peenya Industrial Area",
                "560058", "AAOCB5678W", "29AAOCB5678W1Z3", "Electricals", LocalDate.of(2009, 10, 14)));

        // Pin code 560058 — Peenya
        list.add(new CanonicalBusiness("Suresh Industries", "Plot 11 Peenya 2nd Stage, Bangalore",
                "560058", "AAPCS9012X", "29AAPCS9012X1Z5", "Industrial", LocalDate.of(2007, 3, 8)));

        return list;
    }

    private CanonicalBusiness soloBusiness(int idx) {
        String[] names = {"Solo Trading", "Unique Crafts", "Singular Spices", "Lonely Looms",
                "Standalone Steel", "Onlyone Optics", "Sole Suppliers", "Distinct Designs"};
        String[] pins = {"560003", "560004", "560005", "560006", "560007", "560008", "560009", "560010"};
        return new CanonicalBusiness(
                names[idx % names.length],
                "Address " + idx + " Some Road, Bangalore",
                pins[idx % pins.length],
                "AAQCS" + (1000 + idx) + "Y",
                "29AAQCS" + (1000 + idx) + "Y1Z" + (idx % 10),
                "Misc",
                LocalDate.of(2015 + (idx % 5), 1 + (idx % 11), 1 + (idx % 27))
        );
    }

    private record CanonicalBusiness(
            String name, String address, String pinCode,
            String pan, String gstin, String sector,
            LocalDate registrationDate
    ) {}
}
