/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.accept;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Base class for {@code ContentNegotiationStrategy} implementations with the
 * steps to resolve a request to media types.
 *
 * <p>First a key (for example, "json", "pdf") must be extracted from the request (for example,
 * file extension, query param). The key must then be resolved to media type(s)
 * through the base class {@link MappingMediaTypeFileExtensionResolver} which
 * stores such mappings.
 *
 * <p>The method {@link #handleNoMatch} allow subclasses to plug in additional
 * ways of looking up media types (for example, through the Java Activation framework,
 * or {@link jakarta.servlet.ServletContext#getMimeType}). Media types resolved
 * this way are not added to the base class {@link MappingMediaTypeFileExtensionResolver}.
 * The file extension resolved this way is still remembered, within a fixed
 * bound, for reverse lookups through {@link #resolveFileExtensions(MediaType)}.
 *
 * @author Rossen Stoyanchev
 * @since 3.2
 */
public abstract class AbstractMappingContentNegotiationStrategy extends MappingMediaTypeFileExtensionResolver
		implements ContentNegotiationStrategy {

	/**
	 * Maximum number of file extensions resolved via {@link #handleNoMatch}
	 * that are remembered for reverse lookups.
	 */
	private static final int MAX_RESOLVED_FILE_EXTENSIONS = 256;


	protected final Log logger = LogFactory.getLog(getClass());

	private boolean useRegisteredExtensionsOnly = false;

	private boolean ignoreUnknownExtensions = false;

	/** Reverse lookup for file extensions resolved via {@link #handleNoMatch}. */
	private final ConcurrentMap<MediaType, CopyOnWriteArrayList<String>> resolvedFileExtensions =
			new ConcurrentHashMap<>();

	private final AtomicInteger resolvedFileExtensionCount = new AtomicInteger();


	/**
	 * Create an instance with the given map of file extensions and media types.
	 */
	public AbstractMappingContentNegotiationStrategy(@Nullable Map<String, MediaType> mediaTypes) {
		super(mediaTypes);
	}


	/**
	 * Whether to only use the registered mappings to look up file extensions,
	 * or also to use dynamic resolution (for example, via {@link MediaTypeFactory}).
	 * <p>By default this is set to {@code false}.
	 */
	public void setUseRegisteredExtensionsOnly(boolean useRegisteredExtensionsOnly) {
		this.useRegisteredExtensionsOnly = useRegisteredExtensionsOnly;
	}

	public boolean isUseRegisteredExtensionsOnly() {
		return this.useRegisteredExtensionsOnly;
	}

	/**
	 * Whether to ignore requests with unknown file extension. Setting this to
	 * {@code false} results in {@code HttpMediaTypeNotAcceptableException}.
	 * <p>By default this is set to {@literal false} but is overridden in
	 * {@link PathExtensionContentNegotiationStrategy} to {@literal true}.
	 */
	public void setIgnoreUnknownExtensions(boolean ignoreUnknownExtensions) {
		this.ignoreUnknownExtensions = ignoreUnknownExtensions;
	}

	public boolean isIgnoreUnknownExtensions() {
		return this.ignoreUnknownExtensions;
	}


	@Override
	public List<MediaType> resolveMediaTypes(NativeWebRequest webRequest)
			throws HttpMediaTypeNotAcceptableException {

		return resolveMediaTypeKey(webRequest, getMediaTypeKey(webRequest));
	}

	/**
	 * An alternative to {@link #resolveMediaTypes(NativeWebRequest)} that accepts
	 * an already extracted key.
	 * @since 3.2.16
	 */
	public List<MediaType> resolveMediaTypeKey(NativeWebRequest webRequest, @Nullable String key)
			throws HttpMediaTypeNotAcceptableException {

		if (StringUtils.hasText(key)) {
			MediaType mediaType = lookupMediaType(key);
			if (mediaType != null) {
				handleMatch(key, mediaType);
				return Collections.singletonList(mediaType);
			}
			mediaType = handleNoMatch(webRequest, key);
			if (mediaType != null) {
				addResolvedFileExtension(key, mediaType);
				return Collections.singletonList(mediaType);
			}
		}
		return MEDIA_TYPE_ALL_LIST;
	}

	/**
	 * Resolve the given media type to file extensions from the registered
	 * mappings, falling back on file extensions previously resolved via
	 * {@link #handleNoMatch}.
	 */
	@Override
	public List<String> resolveFileExtensions(MediaType mediaType) {
		List<String> fileExtensions = super.resolveFileExtensions(mediaType);
		if (!fileExtensions.isEmpty()) {
			return fileExtensions;
		}
		List<String> resolved = this.resolvedFileExtensions.get(mediaType.removeQualityValue());
		return (resolved != null ? Collections.unmodifiableList(resolved) : Collections.emptyList());
	}


	/**
	 * Extract a key from the request to use to look up media types.
	 * @return the lookup key, or {@code null} if none
	 */
	@Nullable
	protected abstract String getMediaTypeKey(NativeWebRequest request);

	/**
	 * Override to provide handling when a key is successfully resolved via
	 * {@link #lookupMediaType}.
	 */
	protected void handleMatch(String key, MediaType mediaType) {
	}

	/**
	 * Override to provide handling when a key is not resolved via.
	 * {@link #lookupMediaType}. Subclasses can take further steps to
	 * determine the media type(s). A MediaType returned from this method
	 * is not added to the registered mappings in the base class, but the
	 * file extension is remembered, within a fixed bound, for reverse
	 * lookups through {@link #resolveFileExtensions(MediaType)}.
	 */
	@Nullable
	protected MediaType handleNoMatch(NativeWebRequest request, String key)
			throws HttpMediaTypeNotAcceptableException {

		if (!isUseRegisteredExtensionsOnly()) {
			Optional<MediaType> mediaType = MediaTypeFactory.getMediaType("file." + key);
			if (mediaType.isPresent()) {
				return mediaType.get();
			}
		}
		if (isIgnoreUnknownExtensions()) {
			return null;
		}
		throw new HttpMediaTypeNotAcceptableException(getAllMediaTypes());
	}

	/**
	 * Remember a file extension resolved via {@link #handleNoMatch} for reverse
	 * lookups, without adding it to the registered mappings. Keys containing a
	 * '.' are ignored, and the total number of remembered extensions is bounded.
	 */
	private void addResolvedFileExtension(String key, MediaType mediaType) {
		if (key.indexOf('.') != -1) {
			return;
		}
		String extension = key.toLowerCase(Locale.ROOT);
		MediaType mediaTypeKey = mediaType.removeQualityValue();
		List<String> extensions = this.resolvedFileExtensions.get(mediaTypeKey);
		if (extensions != null && extensions.contains(extension)) {
			return;
		}
		if (this.resolvedFileExtensionCount.incrementAndGet() > MAX_RESOLVED_FILE_EXTENSIONS) {
			this.resolvedFileExtensionCount.decrementAndGet();
			return;
		}
		boolean added = this.resolvedFileExtensions
				.computeIfAbsent(mediaTypeKey, type -> new CopyOnWriteArrayList<>())
				.addIfAbsent(extension);
		if (!added) {
			this.resolvedFileExtensionCount.decrementAndGet();
		}
	}

}
