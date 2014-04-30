package com.bazaarvoice.dropwizard.webjars;

import com.google.common.cache.CacheLoader;
import com.google.common.io.Resources;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

/**
 * Cache loader that knows how to determine the version of a specific webjar.  This loader will look for the
 * {@code pom.properties} file is included inside of each webjar.
 */
class LibraryVersionLoader extends CacheLoader<String, String> {
    private final Iterable<? extends String> webJarPackages;

    LibraryVersionLoader(Iterable<String> webJarPackages) {
        this.webJarPackages = webJarPackages;
    }

    @Override
    public String load(String library) throws Exception {
        for (String searchPackage : webJarPackages) {
            String found = tryToLoadFrom("META-INF/maven/%s/%s/pom.properties", searchPackage, library);
            if (found != null) {
                return found;
            }
        }

        throw new LibraryNotFoundException(library);
    }

    private String tryToLoadFrom(String format, String searchPackage, String library) {
        String path = String.format(format, searchPackage, library);
        URL url;
        try {
            url = Resources.getResource(path);
        } catch (IllegalArgumentException e) {
            return null;
        }

        try {
            InputStream in = url.openStream();
            try {
                Properties props = new Properties();
                props.load(in);

                return props.getProperty("version");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static class LibraryNotFoundException extends RuntimeException {
        private final String library;

        private LibraryNotFoundException(String library) {
            super("Library not found: " + library);
            this.library = library;
        }

        private LibraryNotFoundException(String library, Throwable cause) {
            super("Library not found: " + library, cause);
            this.library = library;
        }
    }
}
