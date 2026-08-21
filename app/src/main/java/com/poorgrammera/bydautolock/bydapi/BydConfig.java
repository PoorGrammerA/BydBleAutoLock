package com.poorgrammera.bydautolock.bydapi;

/**
 * Holds BYD integration settings.
 * Manages API URLs and country codes by region (Korea, Europe, and others).
 */
public class BydConfig {
    private String baseUrl;
    private String countryCode;
    private String language;
    private String timeZone;

    public BydConfig(String baseUrl, String countryCode, String language, String timeZone) {
        this.baseUrl = baseUrl;
        this.countryCode = countryCode;
        this.language = language;
        this.timeZone = timeZone;
    }

    /**
     * Creates configuration dynamically based on region code.
     * Supports 15 official BYD overseas production regions.
     */
    public static BydConfig fromRegion(String region) {
        if (region == null) region = "KR";
        String regionUpper = region.toUpperCase().trim();
        String baseUrl;
        String countryCode = regionUpper;
        String language = "en";
        String timeZone = "UTC";

        switch (regionUpper) {
            case "KR":
                baseUrl = "https://dilinkappoversea-kr-ali.byd.auto";
                language = "ko";
                timeZone = "Asia/Seoul";
                break;
            case "EU":
                baseUrl = "https://dilinkappoversea-eu.byd.auto";
                countryCode = "GB"; // Standard representation for overseas app
                language = "en";
                timeZone = "Europe/London";
                break;
            case "JP":
                baseUrl = "https://dilinkappoversea-jp.byd.auto";
                language = "ja";
                timeZone = "Asia/Tokyo";
                break;
            case "SG":
                baseUrl = "https://dilinkappoversea-sg.byd.auto";
                language = "en";
                timeZone = "Asia/Singapore";
                break;
            case "AU":
                baseUrl = "https://dilinkappoversea-au.byd.auto";
                language = "en";
                timeZone = "Australia/Sydney";
                break;
            case "BR":
                baseUrl = "https://dilinkappoversea-br.byd.auto";
                language = "pt";
                timeZone = "America/Sao_Paulo";
                break;
            case "MX":
                baseUrl = "https://dilinkappoversea-mx.byd.auto";
                language = "es";
                timeZone = "America/Mexico_City";
                break;
            case "NO":
                baseUrl = "https://dilinkappoversea-no.byd.auto";
                language = "no";
                timeZone = "Europe/Oslo";
                break;
            case "UZ":
                baseUrl = "https://dilinkappoversea-uz.byd.auto";
                language = "en";
                timeZone = "Asia/Tashkent";
                break;
            case "KZ":
                baseUrl = "https://dilinkappoversea-kz.byd.auto";
                language = "en";
                timeZone = "Asia/Almaty";
                break;
            case "IN":
                baseUrl = "https://dilinkappoversea-in.byd.auto";
                language = "en";
                timeZone = "Asia/Kolkata";
                break;
            case "ID":
                baseUrl = "https://dilinkappoversea-id.byd.auto";
                language = "in";
                timeZone = "Asia/Jakarta";
                break;
            case "VN":
                baseUrl = "https://dilinkappoversea-vn.byd.auto";
                language = "vi";
                timeZone = "Asia/Ho_Chi_Minh";
                break;
            case "SA":
                baseUrl = "https://dilinkappoversea-sa.byd.auto";
                language = "ar";
                timeZone = "Asia/Riyadh";
                break;
            case "OM":
                baseUrl = "https://dilinkappoversea-om.byd.auto";
                language = "ar";
                timeZone = "Asia/Muscat";
                break;
            default:
                baseUrl = "https://dilinkappoversea-" + region.toLowerCase() + ".byd.auto";
                break;
        }
        return new BydConfig(baseUrl, countryCode, language, timeZone);
    }

    @Deprecated
    public static BydConfig korea() {
        return fromRegion("KR");
    }

    @Deprecated
    public static BydConfig europe() {
        return fromRegion("EU");
    }

    // Getters and Setters
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
}

