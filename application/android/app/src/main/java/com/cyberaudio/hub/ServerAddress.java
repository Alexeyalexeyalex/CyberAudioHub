package com.cyberaudio.hub;

import java.net.URI;
import java.util.Locale;

final class ServerAddress {
    static final String DEFAULT = "http://188.32.242.100:2077";

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return DEFAULT;
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) throw new IllegalArgumentException();
            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, path, null, null).toASCIIString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Укажите адрес сайта http:// или https:// без параметров и пароля.");
        }
    }
}
