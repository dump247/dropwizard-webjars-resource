package com.bazaarvoice.dropwizard.webjars;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

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
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.common.io.Resources;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.sun.jersey.spi.resource.Singleton;

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

    private final LoadingCache<AssetId, Asset> assetCache;

	public WebJarResource() {
		// Cache doesn't need to be very big as we have Server OS cache, browser
		// cacge and proxy caches too. But we do need limits to avoid using up
		// all the heap space. The guava docs advise against soft values, so we
		// use byte size and a maxium age to limit the size. See
		// http://code.google.com/p/guava-libraries/wiki/CachesExplained
        assetCache = CacheBuilder.newBuilder()
        		.maximumWeight(2 * 1024 * 1024) // Max number of bytes in cache
        		.weigher(new Weigher<AssetId, Asset>() {
					@Override
					public int weigh(AssetId key, Asset value) {
						// return file sze in bytes
						return value.bytes.length;
					}})
        		.expireAfterAccess(5, MINUTES)
        		.build(ASSET_LOADER);
    }

    public WebJarResource(CacheBuilderSpec spec) {
        assetCache = CacheBuilder.from(spec).build(ASSET_LOADER);
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

    /**
     * Cache loader that knows how to determine the version of a specific webjar.  This loader will look for the
     * {@code pom.properties} file is included inside of each webjar.
     */
    private static final CacheLoader<String, String> LIBRARY_VERSION_LOADER = new CacheLoader<String, String>() {
        @Override
        public String load(String library) throws Exception {
            String path = String.format("META-INF/maven/org.webjars/%s/pom.properties", library);
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
                    Closeables.closeQuietly(in);
                }
            } catch (IOException e) {
                return null;
            }
        }
    };

    /** Cache loader that knows how to load the contents of WebJar assets. */
    private static final CacheLoader<AssetId, Asset> ASSET_LOADER = new CacheLoader<AssetId, Asset>() {
        final LoadingCache<String, String> versionCache = CacheBuilder.newBuilder()
        		.maximumSize(10) // need to limit to avoid OOME
        		.build(LIBRARY_VERSION_LOADER);

        @Override
        public Asset load(AssetId id) throws Exception {
            String version;
            try {
                version = versionCache.getUnchecked(id.library);
            } catch (UncheckedExecutionException e) {
                return null;
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
                String path = String.format("META-INF/resources/webjars/%s/%s", id.library, id.resource);
                URL resource;

                try {
                    resource = Resources.getResource(path);

                    return new Asset(ByteStreams.toByteArray(resource.openStream()));
                } catch(IllegalArgumentException e) {
                    // ignored
                }

                // Trim a suffix off of the version number
                int hyphen = version.lastIndexOf('-');
                if (hyphen == -1) {
                    return null;
                }

                version = version.substring(0, hyphen);
            }
            while(true);
        }
    };
}