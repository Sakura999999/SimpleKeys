package com.simplecard.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/** Use the decoded application path for every security filter, not raw URI spelling or a hard-coded context. */
public final class RequestPaths {
    private RequestPaths() {}
    public static String application(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
