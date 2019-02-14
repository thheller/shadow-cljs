/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package shadow.undertow;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.undertow.UndertowLogger;
import io.undertow.io.IoCallback;
import io.undertow.predicate.Predicate;
import io.undertow.predicate.Predicates;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.builder.HandlerBuilder;
import io.undertow.server.handlers.cache.ResponseCache;
import io.undertow.server.handlers.encoding.ContentEncodedResource;
import io.undertow.server.handlers.encoding.ContentEncodedResourceManager;
import io.undertow.server.handlers.resource.*;
import io.undertow.util.*;

/**
 * This is the default undertow ResourceHandler but modified so that missing index files
 * do not 403 but instead call next handler
 *
 * @author Stuart Douglas
 */
public class ShadowResourceHandler implements HttpHandler {

    /**
     * Set of methods prescribed by HTTP 1.1. If request method is not one of those, handler will
     * return NOT_IMPLEMENTED.
     */
    private static final Set<HttpString> KNOWN_METHODS = new HashSet<>();

    public static final AttachmentKey RESOURCE_KEY = AttachmentKey.create(Resource.class);

    static {
        KNOWN_METHODS.add(Methods.OPTIONS);
        KNOWN_METHODS.add(Methods.GET);
        KNOWN_METHODS.add(Methods.HEAD);
        KNOWN_METHODS.add(Methods.POST);
        KNOWN_METHODS.add(Methods.PUT);
        KNOWN_METHODS.add(Methods.DELETE);
        KNOWN_METHODS.add(Methods.TRACE);
        KNOWN_METHODS.add(Methods.CONNECT);
    }

    private final List<String> welcomeFiles = new CopyOnWriteArrayList<>(new String[]{"index.html", "index.htm", "default.html", "default.htm"});
    /**
     * If directory listing is enabled.
     */
    private volatile boolean directoryListingEnabled = false;

    /**
     * If the canonical version of paths should be passed into the resource manager.
     */
    private volatile boolean canonicalizePaths = true;

    /**
     * The mime mappings that are used to determine the content type.
     */
    private volatile MimeMappings mimeMappings = MimeMappings.DEFAULT;
    private volatile Predicate cachable = Predicates.truePredicate();
    private volatile Predicate allowed = Predicates.truePredicate();
    private volatile ResourceSupplier resourceSupplier;
    private volatile ResourceManager resourceManager;
    /**
     * If this is set this will be the maximum time (in seconds) the client will cache the resource.
     * <p/>
     * Note: Do not set this for private resources, as it will cause a Cache-Control: public
     * to be sent.
     * <p/>
     * TODO: make this more flexible
     * <p/>
     * This will only be used if the {@link #cachable} predicate returns true
     */
    private volatile Integer cacheTime;

    private volatile ContentEncodedResourceManager contentEncodedResourceManager;

    /**
     * Handler that is called if no resource is found
     */
    private final HttpHandler next;

    public ShadowResourceHandler(ResourceManager resourceSupplier) {
        this(resourceSupplier, ResponseCodeHandler.HANDLE_404);
    }

    public ShadowResourceHandler(ResourceManager resourceManager, HttpHandler next) {
        this.resourceSupplier = new DefaultResourceSupplier(resourceManager);
        this.resourceManager = resourceManager;
        this.next = next;
    }

    public ShadowResourceHandler(ResourceSupplier resourceSupplier) {
        this(resourceSupplier, ResponseCodeHandler.HANDLE_404);
    }

    public ShadowResourceHandler(ResourceSupplier resourceManager, HttpHandler next) {
        this.resourceSupplier = resourceManager;
        this.next = next;
    }


    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        HttpString method = exchange.getRequestMethod();
        if (method.equals(Methods.GET) || method.equals(Methods.POST)) {
            serveResource(exchange, true);
        } else if (method.equals(Methods.HEAD)) {
            serveResource(exchange, false);
        } else {
            // OPTIONS and others just go straight to the next handler
            // typically ring
            next.handleRequest(exchange);
        }
    }

    private void serveResource(final HttpServerExchange exchange, final boolean sendContent) throws Exception {

        if (DirectoryUtils.sendRequestedBlobs(exchange)) {
            return;
        }

        //we now dispatch to a worker thread
        //as resource manager methods are potentially blocking
        HttpHandler dispatchTask = new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                Resource resource = null;
                try {
                    if (File.separatorChar == '/' || !exchange.getRelativePath().contains(File.separator)) {
                        //we don't process resources that contain the sperator character if this is not /
                        //this prevents attacks where people use windows path seperators in file URLS's
                        resource = resourceSupplier.getResource(exchange, canonicalize(exchange.getRelativePath()));
                    }
                } catch (IOException e) {
                    UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                    exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                    exchange.endExchange();
                    return;
                }
                if (resource == null) {
                    //usually a 404 handler
                    next.handleRequest(exchange);
                    return;
                }

                if (resource.isDirectory()) {
                    Resource indexResource;
                    try {
                        indexResource = getIndexFiles(exchange, resourceSupplier, resource.getPath(), welcomeFiles);
                    } catch (IOException e) {
                        UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        exchange.endExchange();
                        return;
                    }
                    if (indexResource == null) {
                        if (directoryListingEnabled) {
                            DirectoryUtils.renderDirectoryListing(exchange, resource);
                            return;
                        } else {
                            next.handleRequest(exchange);
                            // thheller: we don't want this, next handler should decide what to display
                            // exchange.setStatusCode(StatusCodes.FORBIDDEN);
                            // exchange.endExchange();
                            return;
                        }
                    } else if (!exchange.getRequestPath().endsWith("/")) {
                        exchange.setStatusCode(StatusCodes.FOUND);
                        exchange.getResponseHeaders().put(Headers.LOCATION, RedirectBuilder.redirect(exchange, exchange.getRelativePath() + "/", true));
                        exchange.endExchange();
                        return;
                    }
                    resource = indexResource;
                } else if(exchange.getRelativePath().endsWith("/")) {
                    //UNDERTOW-432
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    exchange.endExchange();
                    return;
                }

                exchange.putAttachment(RESOURCE_KEY, resource);

                final ETag etag = resource.getETag();
                final Date lastModified = resource.getLastModified();
                if (!ETagUtils.handleIfMatch(exchange, etag, false) ||
                        !DateUtils.handleIfUnmodifiedSince(exchange, lastModified)) {
                    exchange.setStatusCode(StatusCodes.PRECONDITION_FAILED);
                    exchange.endExchange();
                    return;
                }
                if (!ETagUtils.handleIfNoneMatch(exchange, etag, true) ||
                        !DateUtils.handleIfModifiedSince(exchange, lastModified)) {
                    exchange.setStatusCode(StatusCodes.NOT_MODIFIED);
                    exchange.endExchange();
                    return;
                }
                final ContentEncodedResourceManager contentEncodedResourceManager = ShadowResourceHandler.this.contentEncodedResourceManager;
                Long contentLength = resource.getContentLength();

                if (contentLength != null && !exchange.getResponseHeaders().contains(Headers.TRANSFER_ENCODING)) {
                    exchange.setResponseContentLength(contentLength);
                }
                ByteRange.RangeResponseResult rangeResponse = null;
                long start = -1, end = -1;
                if(resource instanceof RangeAwareResource && ((RangeAwareResource)resource).isRangeSupported() && contentLength != null && contentEncodedResourceManager == null) {

                    exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
                    //TODO: figure out what to do with the content encoded resource manager
                    ByteRange range = ByteRange.parse(exchange.getRequestHeaders().getFirst(Headers.RANGE));
                    if(range != null && range.getRanges() == 1 && resource.getContentLength() != null) {
                        rangeResponse = range.getResponseResult(resource.getContentLength(), exchange.getRequestHeaders().getFirst(Headers.IF_RANGE), resource.getLastModified(), resource.getETag() == null ? null : resource.getETag().getTag());
                        if(rangeResponse != null){
                            start = rangeResponse.getStart();
                            end = rangeResponse.getEnd();
                            exchange.setStatusCode(rangeResponse.getStatusCode());
                            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE, rangeResponse.getContentRange());
                            long length = rangeResponse.getContentLength();
                            exchange.setResponseContentLength(length);
                            if(rangeResponse.getStatusCode() == StatusCodes.REQUEST_RANGE_NOT_SATISFIABLE) {
                                return;
                            }
                        }
                    }
                }
                //we are going to proceed. Set the appropriate headers

                if (!exchange.getResponseHeaders().contains(Headers.CONTENT_TYPE)) {
                    final String contentType = resource.getContentType(mimeMappings);
                    if (contentType != null) {
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
                    } else {
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                    }
                }
                if (lastModified != null) {
                    exchange.getResponseHeaders().put(Headers.LAST_MODIFIED, resource.getLastModifiedString());
                }
                if (etag != null) {
                    exchange.getResponseHeaders().put(Headers.ETAG, etag.toString());
                }

                if (contentEncodedResourceManager != null) {
                    try {
                        ContentEncodedResource encoded = contentEncodedResourceManager.getResource(resource, exchange);
                        if (encoded != null) {
                            exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, encoded.getContentEncoding());
                            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, encoded.getResource().getContentLength());
                            encoded.getResource().serve(exchange.getResponseSender(), exchange, IoCallback.END_EXCHANGE);
                            return;
                        }

                    } catch (IOException e) {
                        //TODO: should this be fatal
                        UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        exchange.endExchange();
                        return;
                    }
                }

                if (!sendContent) {
                    exchange.endExchange();
                } else if(rangeResponse != null) {
                    ((RangeAwareResource)resource).serveRange(exchange.getResponseSender(), exchange, start, end, IoCallback.END_EXCHANGE);
                } else {
                    resource.serve(exchange.getResponseSender(), exchange, IoCallback.END_EXCHANGE);
                }
            }
        };
        if(exchange.isInIoThread()) {
            exchange.dispatch(dispatchTask);
        } else {
            dispatchTask.handleRequest(exchange);
        }
    }

    private Resource getIndexFiles(HttpServerExchange exchange, ResourceSupplier resourceManager, final String base, List<String> possible) throws IOException {
        String realBase;
        if (base.endsWith("/")) {
            realBase = base;
        } else {
            realBase = base + "/";
        }
        for (String possibility : possible) {
            Resource index = resourceManager.getResource(exchange, canonicalize(realBase + possibility));
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private String canonicalize(String s) {
        if(canonicalizePaths) {
            return CanonicalPathUtils.canonicalize(s);
        }
        return s;
    }

    public boolean isDirectoryListingEnabled() {
        return directoryListingEnabled;
    }

    public ShadowResourceHandler setDirectoryListingEnabled(final boolean directoryListingEnabled) {
        this.directoryListingEnabled = directoryListingEnabled;
        return this;
    }

    public ShadowResourceHandler addWelcomeFiles(String... files) {
        this.welcomeFiles.addAll(Arrays.asList(files));
        return this;
    }

    public ShadowResourceHandler setWelcomeFiles(String... files) {
        this.welcomeFiles.clear();
        this.welcomeFiles.addAll(Arrays.asList(files));
        return this;
    }

    public MimeMappings getMimeMappings() {
        return mimeMappings;
    }

    public ShadowResourceHandler setMimeMappings(final MimeMappings mimeMappings) {
        this.mimeMappings = mimeMappings;
        return this;
    }

    public Predicate getCachable() {
        return cachable;
    }

    public ShadowResourceHandler setCachable(final Predicate cachable) {
        this.cachable = cachable;
        return this;
    }

    public Predicate getAllowed() {
        return allowed;
    }

    public ShadowResourceHandler setAllowed(final Predicate allowed) {
        this.allowed = allowed;
        return this;
    }

    public ResourceSupplier getResourceSupplier() {
        return resourceSupplier;
    }

    public ShadowResourceHandler setResourceSupplier(final ResourceSupplier resourceSupplier) {
        this.resourceSupplier = resourceSupplier;
        this.resourceManager = null;
        return this;
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public ShadowResourceHandler setResourceManager(final ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        this.resourceSupplier = new DefaultResourceSupplier(resourceManager);
        return this;
    }

    public Integer getCacheTime() {
        return cacheTime;
    }

    public ShadowResourceHandler setCacheTime(final Integer cacheTime) {
        this.cacheTime = cacheTime;
        return this;
    }

    public ContentEncodedResourceManager getContentEncodedResourceManager() {
        return contentEncodedResourceManager;
    }

    public ShadowResourceHandler setContentEncodedResourceManager(ContentEncodedResourceManager contentEncodedResourceManager) {
        this.contentEncodedResourceManager = contentEncodedResourceManager;
        return this;
    }

    public boolean isCanonicalizePaths() {
        return canonicalizePaths;
    }

    /**
     * If this handler should use canonicalized paths.
     *
     * WARNING: If this is not true and {@link io.undertow.server.handlers.CanonicalPathHandler} is not installed in
     * the handler chain then is may be possible to perform a directory traversal attack. If you set this to false make
     * sure you have some kind of check in place to control the path.
     * @param canonicalizePaths If paths should be canonicalized
     */
    public void setCanonicalizePaths(boolean canonicalizePaths) {
        this.canonicalizePaths = canonicalizePaths;
    }
}
