package com.poorgrammera.bydautolock.bydapi;

import android.content.Context;

/**
 * Holds BYD integration settings.
 * Manages API URLs and country codes for all 116 official BYD overseas countries
 * mapped across 16 server cluster nodes.
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
     * Creates configuration dynamically based on country or region code.
     * Supports all 116 official BYD overseas countries across all 16 server nodes.
     *
     * @param region ISO country code (e.g. "KR", "IE", "DE", "AU", "MX") or legacy region code ("EU").
     */
    public static BydConfig fromRegion(String region) {
        if (region == null || region.trim().isEmpty()) {
            region = "KR";
        }
        String regionUpper = BydCountryRepository.normalizeCountryCode(region);

        // 1. South Korea (Node 11) - Dedicated Alibaba Cloud Seoul Region
        if ("KR".equals(regionUpper)) {
            return new BydConfig(
                    BydCountryRepository.getBaseUrlForNode("11"),
                    "KR",
                    "ko",
                    "Asia/Seoul"
            );
        }

        // 2. Legacy "EU" representation
        if ("EU".equals(regionUpper)) {
            return new BydConfig(
                    BydCountryRepository.getBaseUrlForNode("1"),
                    "GB",
                    "en",
                    "Europe/London"
            );
        }

        // 3. Specific known country overrides for exact timezones
        String nodeName = BydCountryRepository.getNodeForDomain(regionUpper);
        String baseUrl = BydCountryRepository.getBaseUrlForNode(nodeName);
        String countryCode = regionUpper;
        String language = "en";
        String timeZone = BydCountryRepository.getDefaultTimezoneForNode(nodeName);

        switch (regionUpper) {
            case "IE":
                timeZone = "Europe/Dublin";
                break;
            case "GB":
            case "UK":
                timeZone = "Europe/London";
                break;
            case "DE":
                timeZone = "Europe/Berlin";
                break;
            case "FR":
                timeZone = "Europe/Paris";
                break;
            case "IT":
                timeZone = "Europe/Rome";
                break;
            case "ES":
                timeZone = "Europe/Madrid";
                break;
            case "JP":
                language = "ja";
                timeZone = "Asia/Tokyo";
                break;
            case "SG":
                timeZone = "Asia/Singapore";
                break;
            case "AU":
                timeZone = "Australia/Sydney";
                break;
            case "BR":
                language = "pt";
                timeZone = "America/Sao_Paulo";
                break;
            case "MX":
                language = "es";
                timeZone = "America/Mexico_City";
                break;
            case "NO":
                language = "no";
                timeZone = "Europe/Oslo";
                break;
            case "UZ":
                timeZone = "Asia/Tashkent";
                break;
            case "KZ":
                timeZone = "Asia/Almaty";
                break;
            case "IN":
                timeZone = "Asia/Kolkata";
                break;
            case "ID":
                language = "in";
                timeZone = "Asia/Jakarta";
                break;
            case "VN":
            case "VNM":
                language = "vi";
                timeZone = "Asia/Ho_Chi_Minh";
                countryCode = "VNM";
                break;
            case "BN":
            case "BRN":
                timeZone = "Asia/Brunei";
                countryCode = "BRN";
                break;
            case "SA":
                language = "ar";
                timeZone = "Asia/Riyadh";
                break;
            case "OM":
                language = "ar";
                timeZone = "Asia/Muscat";
                break;
            case "TR":
                language = "tr";
                timeZone = "Europe/Istanbul";
                break;
            default:
                break;
        }

        return new BydConfig(baseUrl, countryCode, language, timeZone);
    }

    /**
     * Context-aware helper that can resolve full country metadata if available.
     */
    public static BydConfig fromCountry(Context context, String countryCode) {
        if (context != null && countryCode != null) {
            BydCountry country = BydCountryRepository.getInstance().findByDomain(context, countryCode);
            if (country != null) {
                return fromRegion(country.getDomain());
            }
        }
        return fromRegion(countryCode);
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
