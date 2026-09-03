package com.poorgrammera.bydautolock.bydapi;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages official BYD overseas countries (116 countries) and 16 server cluster nodes.
 * Data source: assets/country_list.json (extracted from official BYD WatchApp).
 */
public final class BydCountryRepository {
    private static final String TAG = "BydCountryRepository";
    private static final Object LOCK = new Object();
    private static volatile BydCountryRepository instance;

    // 16 Official BYD overseas server nodes (dilinkappoversea-*.byd.auto)
    public static final Map<String, String> NODE_BASE_URLS;
    public static final Map<String, String> NODE_DEFAULT_TIMEZONES;
    public static final Map<String, String> DOMAIN_TO_NODE;

    static {
        Map<String, String> urls = new HashMap<>();
        urls.put("1", "https://dilinkappoversea-eu.byd.auto");
        urls.put("2", "https://dilinkappoversea-sg.byd.auto");
        urls.put("3", "https://dilinkappoversea-au.byd.auto");
        urls.put("4", "https://dilinkappoversea-br.byd.auto");
        urls.put("5", "https://dilinkappoversea-jp.byd.auto");
        urls.put("6", "https://dilinkappoversea-uz.byd.auto");
        urls.put("7", "https://dilinkappoversea-no.byd.auto");
        urls.put("8", "https://dilinkappoversea-mx.byd.auto");
        urls.put("9", "https://dilinkappoversea-id.byd.auto");
        urls.put("10", "https://dilinkappoversea-tr.byd.auto");
        urls.put("11", "https://dilinkappoversea-kr-ali.byd.auto");
        urls.put("12", "https://dilinkappoversea-in.byd.auto");
        urls.put("13", "https://dilinkappoversea-vn.byd.auto");
        urls.put("14", "https://dilinkappoversea-sa.byd.auto");
        urls.put("15", "https://dilinkappoversea-om.byd.auto");
        urls.put("16", "https://dilinkappoversea-kz.byd.auto");
        NODE_BASE_URLS = Collections.unmodifiableMap(urls);

        Map<String, String> tz = new HashMap<>();
        tz.put("1", "Europe/London");
        tz.put("2", "Asia/Singapore");
        tz.put("3", "Australia/Sydney");
        tz.put("4", "America/Sao_Paulo");
        tz.put("5", "Asia/Tokyo");
        tz.put("6", "Asia/Tashkent");
        tz.put("7", "Europe/Oslo");
        tz.put("8", "America/Mexico_City");
        tz.put("9", "Asia/Jakarta");
        tz.put("10", "Europe/Istanbul");
        tz.put("11", "Asia/Seoul");
        tz.put("12", "Asia/Kolkata");
        tz.put("13", "Asia/Ho_Chi_Minh");
        tz.put("14", "Asia/Riyadh");
        tz.put("15", "Asia/Muscat");
        tz.put("16", "Asia/Almaty");
        NODE_DEFAULT_TIMEZONES = Collections.unmodifiableMap(tz);

        Map<String, String> d2n = new HashMap<>();
        // Node 1 (EU - 43 countries)
        d2n.put("NO", "1"); d2n.put("NL", "1"); d2n.put("DE", "1"); d2n.put("DK", "1");
        d2n.put("SE", "1"); d2n.put("FR", "1"); d2n.put("AT", "1"); d2n.put("LU", "1");
        d2n.put("BE", "1"); d2n.put("FI", "1"); d2n.put("IT", "1"); d2n.put("ES", "1");
        d2n.put("PT", "1"); d2n.put("GB", "1"); d2n.put("IE", "1"); d2n.put("IS", "1");
        d2n.put("IL", "1"); d2n.put("HU", "1"); d2n.put("MT", "1"); d2n.put("GR", "1");
        d2n.put("CH", "1"); d2n.put("PL", "1"); d2n.put("CY", "1"); d2n.put("EE", "1");
        d2n.put("LV", "1"); d2n.put("LT", "1"); d2n.put("CZ", "1"); d2n.put("RO", "1");
        d2n.put("SK", "1"); d2n.put("SI", "1"); d2n.put("BG", "1"); d2n.put("HR", "1");
        d2n.put("LI", "1"); d2n.put("ME", "1"); d2n.put("RS", "1"); d2n.put("BA", "1");
        d2n.put("MK", "1"); d2n.put("AL", "1"); d2n.put("MD", "1"); d2n.put("MC", "1");
        d2n.put("VA", "1"); d2n.put("XK", "1"); d2n.put("UA", "1");

        // Node 2 (SG - 19 countries)
        d2n.put("SG", "2"); d2n.put("TH", "2"); d2n.put("MY", "2"); d2n.put("HK", "2");
        d2n.put("MO", "2"); d2n.put("KH", "2"); d2n.put("LA", "2"); d2n.put("PH", "2");
        d2n.put("BN", "2"); d2n.put("BRN", "2"); d2n.put("MM", "2"); d2n.put("NP", "2"); d2n.put("BD", "2");
        d2n.put("PK", "2"); d2n.put("LK", "2"); d2n.put("PF", "2"); d2n.put("NC", "2");
        d2n.put("MN", "2"); d2n.put("BT", "2"); d2n.put("MV", "2");

        // Node 3 (AU - 2 countries)
        d2n.put("AU", "3"); d2n.put("NZ", "3");

        // Node 4 (BR)
        d2n.put("BR", "4");

        // Node 5 (JP)
        d2n.put("JP", "5");

        // Node 6 (UZ)
        d2n.put("UZ", "6");

        // Node 7 (NO - Middle East / Africa, 12 countries)
        d2n.put("PS", "7"); d2n.put("AE", "7"); d2n.put("IQ", "7"); d2n.put("KW", "7");
        d2n.put("QA", "7"); d2n.put("MA", "7"); d2n.put("BH", "7"); d2n.put("JO", "7");
        d2n.put("ZA", "7"); d2n.put("RE", "7"); d2n.put("MU", "7"); d2n.put("EG", "7");

        // Node 8 (MX - Latin America, 29 countries)
        d2n.put("MX", "8"); d2n.put("CL", "8"); d2n.put("UY", "8"); d2n.put("CO", "8");
        d2n.put("DO", "8"); d2n.put("CR", "8"); d2n.put("PE", "8"); d2n.put("EC", "8");
        d2n.put("PY", "8"); d2n.put("BO", "8"); d2n.put("PA", "8"); d2n.put("GT", "8");
        d2n.put("SV", "8"); d2n.put("HN", "8"); d2n.put("NI", "8"); d2n.put("AR", "8");
        d2n.put("BZ", "8"); d2n.put("BS", "8"); d2n.put("AW", "8"); d2n.put("CW", "8");
        d2n.put("BQ", "8"); d2n.put("TT", "8"); d2n.put("JM", "8"); d2n.put("SR", "8");
        d2n.put("KY", "8"); d2n.put("AG", "8"); d2n.put("GY", "8"); d2n.put("LC", "8");
        d2n.put("BB", "8");

        // Node 9 (ID)
        d2n.put("ID", "9");

        // Node 10 (TR)
        d2n.put("TR", "10");

        // Node 11 (KR)
        d2n.put("KR", "11");

        // Node 12 (IN)
        d2n.put("IN", "12");

        // Node 13 (VN / VNM)
        d2n.put("VN", "13"); d2n.put("VNM", "13");

        // Node 14 (SA)
        d2n.put("SA", "14");

        // Node 15 (OM)
        d2n.put("OM", "15");

        // Node 16 (KZ)
        d2n.put("KZ", "16");

        // Legacy / Alias
        d2n.put("EU", "1");

        DOMAIN_TO_NODE = Collections.unmodifiableMap(d2n);
    }

    private final List<BydCountry> countryList = new ArrayList<>();
    private final Map<String, BydCountry> countryByDomain = new HashMap<>();
    private boolean loaded = false;

    private BydCountryRepository() {
    }

    public static BydCountryRepository getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new BydCountryRepository();
                }
            }
        }
        return instance;
    }

    /**
     * Loads the country list from assets/country_list.json if not already loaded.
     */
    public synchronized void ensureLoaded(Context context) {
        if (loaded) return;
        if (context == null) return;

        try (InputStream is = context.getApplicationContext().getAssets().open("country_list.json");
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);
            if (jsonObject != null && jsonObject.has("dataNode")) {
                JsonArray dataNode = jsonObject.getAsJsonArray("dataNode");
                countryList.clear();
                countryByDomain.clear();
                for (JsonElement elem : dataNode) {
                    if (elem.isJsonObject()) {
                        BydCountry country = gson.fromJson(elem, BydCountry.class);
                        if (country != null && country.getDomain() != null) {
                            countryList.add(country);
                            countryByDomain.put(country.getDomain().toUpperCase(Locale.US), country);
                        }
                    }
                }
                Collections.sort(countryList);
                loaded = true;
                Log.i(TAG, "Successfully loaded " + countryList.size() + " countries from country_list.json");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load country_list.json from assets", t);
        }
    }

    public List<BydCountry> getCountries(Context context) {
        ensureLoaded(context);
        return Collections.unmodifiableList(countryList);
    }

    /**
     * Normalizes aliases or 2-letter ISO codes to BYD asset domain keys.
     * In BYD country_list.json, Vietnam is "VNM" and Brunei is "BRN".
     */
    public static String normalizeCountryCode(String code) {
        if (code == null) return null;
        String cc = code.trim().toUpperCase(Locale.US);
        switch (cc) {
            case "VN":
                return "VNM";
            case "BN":
                return "BRN";
            case "UK":
                return "GB";
            default:
                return cc;
        }
    }

    /**
     * Looks up a country by domain/countryCode (e.g. "IE", "KR", "DE", "VN", "BN").
     */
    public BydCountry findByDomain(Context context, String domain) {
        if (domain == null) return null;
        ensureLoaded(context);
        String normalized = normalizeCountryCode(domain);
        return countryByDomain.get(normalized);
    }

    /**
     * Resolves nodeName ("1" to "16") for a domain string with static fallback.
     */
    public static String getNodeForDomain(String domain) {
        if (domain == null) return "11";
        String normalized = normalizeCountryCode(domain);
        String node = DOMAIN_TO_NODE.get(normalized);
        return node != null ? node : "1";
    }

    /**
     * Resolves the official Base URL for a given nodeName ("1" to "16").
     */
    public static String getBaseUrlForNode(String nodeName) {
        String url = NODE_BASE_URLS.get(nodeName);
        return url != null ? url : "https://dilinkappoversea-eu.byd.auto";
    }

    /**
     * Resolves default timezone for a nodeName ("1" to "16").
     */
    public static String getDefaultTimezoneForNode(String nodeName) {
        String tz = NODE_DEFAULT_TIMEZONES.get(nodeName);
        return tz != null ? tz : "UTC";
    }
}
