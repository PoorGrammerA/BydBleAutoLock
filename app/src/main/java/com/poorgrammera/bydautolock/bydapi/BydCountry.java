package com.poorgrammera.bydautolock.bydapi;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a supported BYD overseas country entry from country_list.json.
 */
public class BydCountry implements Serializable, Comparable<BydCountry> {
    private String code;        // Phone country dial code (e.g. "353", "82")
    private String name;        // Local/Chinese name (e.g. "爱尔兰", "韩国")
    private String alias;       // English country name (e.g. "Ireland", "Republic of Korea")
    private String domain;      // ISO Country Code / Domain identifier (e.g. "IE", "KR")
    private String nodeName;    // Server cluster node number ("1" to "16")
    private String continent;   // Continent code (e.g. "EU", "AS", "SA")

    public BydCountry() {
    }

    public BydCountry(String code, String name, String alias, String domain, String nodeName, String continent) {
        this.code = code;
        this.name = name;
        this.alias = alias;
        this.domain = domain;
        this.nodeName = nodeName;
        this.continent = continent;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getContinent() {
        return continent;
    }

    public void setContinent(String continent) {
        this.continent = continent;
    }

    /**
     * User-facing display text in dropdowns, e.g. "Ireland (IE)" or "Republic of Korea (KR)".
     */
    public String getDisplayName() {
        if (alias != null && !alias.isEmpty() && domain != null && !domain.isEmpty()) {
            return alias + " (" + domain + ")";
        } else if (alias != null && !alias.isEmpty()) {
            return alias;
        } else if (domain != null && !domain.isEmpty()) {
            return domain;
        }
        return name != null ? name : "";
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BydCountry that = (BydCountry) o;
        return Objects.equals(domain, that.domain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domain);
    }

    @Override
    public int compareTo(BydCountry o) {
        if (o == null || o.alias == null) return 1;
        if (this.alias == null) return -1;
        return this.alias.compareToIgnoreCase(o.alias);
    }
}
