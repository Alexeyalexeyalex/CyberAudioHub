package com.cyberaudio.hub;

import java.util.List;
import java.util.Map;

/** HTTP field names are case-insensitive, including Android's Set-cookie. */
final class SessionCookies {
    static String session(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            for (String header : entry.getValue()) {
                if (header == null) continue;
                String pair = header.split(";", 2)[0].trim();
                if (pair.startsWith("session=")) return pair.equals("session=") ? "" : pair;
            }
        }
        return null;
    }
}
