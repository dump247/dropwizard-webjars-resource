package com.bazaarvoice.dropwizard.webjars;

import com.sun.jersey.spi.resource.Singleton;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.UncheckedExecutionException;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URL;
import java.util.Date;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * A resource to include in a Jersey application that will allow it to serve WebJar resources from out of the classpath.
 * <p/>
 * Using this resource (instead of something comparable like DropWizard's {@code AssetBundle}) allows developers to
 * ignore the version of a particular WebJar dependency.  Instead the version of the dependency is automatically
 * inferred from the WebJar resource's jar file (using the {@code pom.properties} file embedded in it).
 * <p/>
 * To use register this resource as part of your Jersey application.  Then reference resources in your web application
 * like:
 * <pre>
 *     &lt;script src="webjars/bootstrap/js/bootstrap.min.js"&gt;&lt;/script&gt;
 * </pre>
 * <p/>
 * This will automatically resolve to the version of the resource corresponding to the version of the webjar in your
 * classpath.
 *
 * @see <a href="http://www.webjars.org/">http://www.webjars.org</a>
 */
@Singleton
@Path("/webjars")
public class WebJarResource {
    private static final Response NOT_FOUND = Response.status(Response.Status.NOT_FOUND).build();
    private static final Response NOT_MODIFIED = Response.status(Response.Status.NOT_MODIFIED).build();
    private static final Splitter ETAG_SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();
    private static final MimeTypes MIME_TYPES = new MimeTypes();
    private static final Buffer DEFAULT_MIME_TYPE = new ByteArrayBuffer(MediaType.TEXT_HTML);
    private static final Iterable<String> DEFAULT_WEBJAR_PACKAGES = Lists.newArrayList("org.webjars");

    private final LoadingCache<AssetId, Asset> assetCache;

    public WebJarResource() {
        AssetCacheLoader assetCacheLoader = buildAssetCacheLoader(DEFAULT_WEBJAR_PACKAGES);
        assetCache = buildDefaultAssetCache(assetCacheLoader);
    }

    public WebJarResource(String spec, Iterable<String> additionalWebjarPackages) {
        AssetCacheLoader assetCacheLoader = buildAssetCacheLoader(Iterables.concat(DEFAULT_WEBJAR_PACKAGES, ImmutableList.copyOf(additionalWebjarPackages)));

        if (spec != null) {
            assetCache = CacheBuilder.from(CacheBuilderSpec.parse(spec))
                .weigher(new AssetSizeWeigher())
                .build(assetCacheLoader);
        } else {
            assetCache = buildDefaultAssetCache(assetCacheLoader);
        }
    }

    private AssetCacheLoader buildAssetCacheLoader(Iterable<String> webJarPackages) {
        LibraryVersionLoader libraryVersionLoader = new LibraryVersionLoader(webJarPackages);
        return new AssetCacheLoader(libraryVersionLoader);
    }

    private LoadingCache<AssetId, Asset> buildDefaultAssetCache(CacheLoader<AssetId, Asset> assetCacheLoader) {
        // Cache doesn't need to be very big as we have Server OS cache, browser
        // cache and proxy caches too. But we do need limits to avoid using up
        // all the heap space. The guava docs advise against soft values, so we
        // use byte size and a maximum age to limit the size. See
        // http://code.google.com/p/guava-libraries/wiki/CachesExplained

        return CacheBuilder.newBuilder()
            .maximumWeight(2 * 1024 * 1024) // Max number of bytes in cache
            .weigher(new AssetSizeWeigher())
            .expireAfterAccess(5, MINUTES)
            .build(assetCacheLoader);
    }

    @GET
    @Path("/{library}/{resource: .*}")
    public Response getAsset(@PathParam("library") String library,
                             @PathParam("resource") String resource,
                             @HeaderParam(HttpHeaders.IF_NONE_MATCH) String ifNoneMatch,
                             @HeaderParam(HttpHeaders.IF_MODIFIED_SINCE) Date ifModifiedSince) throws IOException {
        AssetId id = new AssetId(library, resource);

        Asset asset;
        try {
            asset = assetCache.getUnchecked(id);
        } catch (UncheckedExecutionException e) {
            return NOT_FOUND;
        }

        // Check the ETags to see if the resource has changed...
        if (ifNoneMatch != null) {
            for (String eTag : ETAG_SPLITTER.split(ifNoneMatch)) {
                if (eTag.startsWith("\"")) eTag = eTag.substring(1);
                if (eTag.endsWith("\"")) eTag = eTag.substring(0, eTag.length() - 1);

                if ("*".equals(eTag) || asset.eTag.equals(eTag)) {
                    return NOT_MODIFIED;
                }
            }
        }

        // Check the last modification time
        if (ifModifiedSince != null && ifModifiedSince.getTime() >= asset.lastModifiedTime) {
            return NOT_MODIFIED;
        }

        // The asset is new to the client, build the response.
        Optional<Buffer> mimeType = Optional.fromNullable(MIME_TYPES.getMimeByExtension(id.resource));
        return Response.ok()
                .lastModified(asset.lastModifiedDate)
                .tag(asset.eTag)
                .type(mimeType.or(DEFAULT_MIME_TYPE).toString())
                .entity(asset.bytes)
                .build();
    }

    /** Weigh an asset according to the number of bytes it contains. */
    private static final class AssetSizeWeigher implements Weigher<AssetId, Asset> {
        @Override
        public int weigh(AssetId key, Asset asset) {
            // return file sze in bytes
            return asset.bytes.length;
        }
    }

    private static final class AssetId {
        public final String library;
        public final String resource;

        public AssetId(String library, String resource) {
            this.library = library;
            this.resource = resource;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || !(o instanceof AssetId)) return false;

            AssetId id = (AssetId) o;
            return Objects.equal(library, id.library) && Objects.equal(resource, id.resource);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(library, resource);
        }

        @Override
        public String toString() {
            return library + ":" + resource;
        }
    }

    private static final class Asset {
        public final byte[] bytes;
        public final String eTag;
        public final Date lastModifiedDate;
        public final long lastModifiedTime;

        public Asset(byte[] bytes) {
            this.bytes = bytes;
            this.eTag = Hashing.murmur3_128().hashBytes(bytes).toString();
            this.lastModifiedDate = new Date();
            this.lastModifiedTime = lastModifiedDate.getTime();
        }
    }


    private static class AssetCacheLoader extends CacheLoader<AssetId, Asset> {
        private final CacheLoader<String,String> libraryVersionLoader;
        private final LoadingCache<String, String> versionCache;

        private AssetCacheLoader(CacheLoader<String, String> libraryVersionLoader) {
            this.libraryVersionLoader = libraryVersionLoader;
            versionCache = CacheBuilder.newBuilder()
                    .maximumSize(10)
                    .build(this.libraryVersionLoader);
        }

        @Override
        public Asset load(AssetId id) throws Exception {
            String version;
            try {
                version = versionCache.getUnchecked(id.library);
            } catch (UncheckedExecutionException e) {
                throw new AssetNotFoundException(id, e);
            }

            // Sometimes the WebJar has multiple releases which gets represented by a -# suffix to the version number
            // inside of pom.properties.  When this happens, the files inside of the jar don't include the WebJar
            // release number as part of the file path.  For example, the angularjs 1.1.1 WebJar has a version inside of
            // pom.properties of 1.1.1-1.  But the path to angular.js inside of the jar is
            // META-INF/resources/webjars/angularjs/1.1.1/angular.js.
            //
            // Alternatively sometimes the developer of the library includes a -suffix in the true library version.  In
            // these cases the WebJar pom.properties will include that suffix in the version number, and the file paths
            // inside of the jar will also include the suffix.  For example, the backbone-marionette 1.0.0-beta6 WebJar
            // has a version inside of pom.properties of 1.0.0-beta6.  The path to backbone.marionette.js is also
            // META-INF/resources/webjars/backbone-marionette/1.0.0-beta6/backbone.marionette.js.
            //
            // So based on the data inside of pom.properties it's going to be impossible to determine whether a -suffix
            // should be stripped off or not.  A reasonable heuristic however is going to be to just keep trying over
            // and over starting with the most specific version number, then stripping a suffix off at a time until
            // there are no more suffixes and the right version number is determined.
            do {
                String path = String.format("META-INF/resources/webjars/%s/%s/%s", id.library, version, id.resource);
                URL resource;

                try {
                    resource = Resources.getResource(path);

                    // We know that this version was valid.  Update the version cache to make sure that we remember it
                    // for next time around.
                    versionCache.put(id.library, version);

                    return new Asset(ByteStreams.toByteArray(resource.openStream()));
                } catch(IllegalArgumentException e) {
                    // ignored
                }

                // Trim a suffix off of the version number
                int hyphen = version.lastIndexOf('-');
                if (hyphen == -1) {
                    throw new AssetNotFoundException(id);
                }

                version = version.substring(0, hyphen);
            }
            while(true);
        }
    }

    private static class AssetNotFoundException extends RuntimeException {
        private final AssetId assetId;

        public AssetNotFoundException(AssetId assetId) {
            super("Asset not found: " + assetId);
            this.assetId = assetId;
        }

        public AssetNotFoundException(AssetId assetId, Throwable cause) {
            super("Asset not found: " + assetId, cause);
            this.assetId = assetId;
        }
    }
}