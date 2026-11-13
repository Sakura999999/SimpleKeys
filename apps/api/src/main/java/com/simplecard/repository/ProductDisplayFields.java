package com.simplecard.repository;

import java.util.*;

/** Explicit allowlist; never put prices, stock, visibility or card keys in the display cache. */
public record ProductDisplayFields(String title, String titleEn, String description, String descriptionEn,
                                   String detailMd, String detailMdEn, String coverUrl) {
    public static final Set<String> KEYS = Set.of("title", "title_en", "description", "description_en", "detail_md", "detail_md_en", "cover_url");
    public Map<String,Object> asMap() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("title", title); m.put("title_en", titleEn); m.put("description", description);
        m.put("description_en", descriptionEn); m.put("detail_md", detailMd); m.put("detail_md_en", detailMdEn);
        m.put("cover_url", coverUrl); return m;
    }
}
