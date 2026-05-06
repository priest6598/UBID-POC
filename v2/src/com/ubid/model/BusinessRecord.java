package com.ubid.model;

public record BusinessRecord(
    String recordId,
    String source,
    String businessName,
    String pan,
    String gstin,
    String address,
    String pinCode,
    String sector,
    String phone,
    String registrationDate
) {
    public String toJson() {
        return String.format(
            "{\"recordId\":\"%s\",\"source\":\"%s\",\"businessName\":\"%s\",\"pan\":\"%s\"," +
            "\"gstin\":\"%s\",\"address\":\"%s\",\"pinCode\":\"%s\",\"sector\":\"%s\"," +
            "\"phone\":\"%s\",\"registrationDate\":\"%s\"}",
            esc(recordId), esc(source), esc(businessName), esc(pan),
            esc(gstin), esc(address), esc(pinCode), esc(sector),
            esc(phone), esc(registrationDate)
        );
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
