/*******************************************************************************
 * Copyright (c) 2016, 2019 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Lucas Bullen (Red Hat Inc.) - [Bug 517428] Requests sent before initialization
 *  Martin Lippert (Pivotal Inc.) - bug 531167, 531670, 536258
 *  Kris De Volder (Pivotal Inc.) - Get language servers by capability predicate.
 *******************************************************************************/
package org.eclipse.lsp4e;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.part.FileEditorInput;

/**
 * The entry-point to retrieve a Language Server for a given resource/project.
 * Deals with instantiations and caching of underlying
 * {@link LanguageServerWrapper}.
 *
 */
public class LanguageServiceAccessor {

	private LanguageServiceAccessor() {
		// this class shouldn't be instantiated
	}

	private static final Set<LanguageServerWrapper> startedServers = new CopyOnWriteArraySet<>();
	private static final Map<StreamConnectionProvider, LanguageServerDefinition> providersToLSDefinitions = new HashMap<>();

	/**
	 * This is meant for test code to clear state that might have leaked from other
	 * tests. It isn't meant to be used in production code.
	 */
	public static void clearStartedServers() {
		startedServers.removeIf(server -> {
			server.stop();
			return true;
		});
	}

	/**
	 * A bean storing association of a Document/File with a language server.
	 */
	public static class LSPDocumentInfo {

		private final @NonNull URI fileUri;
		private final @NonNull IDocument document;
		private final @NonNull LanguageServerWrapper wrapper;

		private LSPDocumentInfo(@NonNull URI fileUri, @NonNull IDocument document,
				@NonNull LanguageServerWrapper wrapper) {
			this.fileUri = fileUri;
			this.document = document;
			this.wrapper = wrapper;
		}

		public @NonNull IDocument getDocument() {
			return this.document;
		}

		/**
		 * TODO consider directly returning a {@link TextDocumentIdentifier}
		 *
		 * @return the file URI
		 */
		public @NonNull URI getFileUri() {
			return this.fileUri;
		}

		/**
		 * Returns the language server, regardless of if it is initialized.
		 *
		 * @deprecated use {@link #getInitializedLanguageClient()} instead.
		 */
		@Deprecated
		public LanguageServer getLanguageClient() {
			try {
				return this.wrapper.getInitializedServer().get();
			} catch (ExecutionException e) {
				LanguageServerPlugin.logError(e);
				return this.wrapper.getServer();
			} catch (InterruptedException e) {
				LanguageServerPlugin.logError(e);
				Thread.currentThread().interrupt();
				return this.wrapper.getServer();
			}
		}

		public int getVersion() {
			return wrapper.getVersion(fileUri);
		}

		public CompletableFuture<LanguageServer> getInitializedLanguageClient() {
			return this.wrapper.getInitializedServer();
		}

		public @Nullable ServerCapabilities getCapabilites() {
			return this.wrapper.getServerCapabilities();
		}

		public boolean isActive() {
			return this.wrapper.isActive();
		}
	}

	public static @NonNull List<CompletableFuture<LanguageServer>> getInitializedLanguageServers(@NonNull IFile file,
			@Nullable Predicate<ServerCapabilities> request) throws IOException {
		return getLSWrappers(file, request).stream().map(LanguageServerWrapper::getInitializedServer)
				.collect(Collectors.toList());
	}

	public static void disableLanguageServerContentType(
			@NonNull ContentTypeToLanguageServerDefinition contentTypeToLSDefinition) {
		Optional<LanguageServerWrapper> result = startedServers.stream()
				.filter(server -> server.serverDefinition.equals(contentTypeToLSDefinition.getValue())).findFirst();
		if (result.isPresent()) {
			IContentType contentType = contentTypeToLSDefinition.getKey();
			if (contentType != null) {
				result.get().disconnectContentType(contentType);
			}
		}
	}

	public static boolean isStillRunning(LanguageServer server) {
		return startedServers.stream().anyMatch(wrapper -> wrapper.isWrapperFor(server) && wrapper.isActive());
	}

	public static void enableLanguageServerContentType(
			@NonNull final ContentTypeToLanguageServerDefinition contentTypeToLSDefinition,
			@NonNull final IEditorReference[] editors) {
		final IContentType contentType = contentTypeToLSDefinition.getKey();
		if(contentType == null)
			return;
		final LanguageServerDefinition lsDefinition = contentTypeToLSDefinition.getValue();
		if(lsDefinition == null)
			return;

		for (final IEditorReference editor : editors) {
			try {
				if (editor.getEditorInput() instanceof FileEditorInput editorInput) {
					final IFile editorFile = editorInput.getFile();
					final IContentDescription contentDesc = editorFile.getContentDescription();
					if(contentDesc == null)
						continue;
					if (contentType.equals(contentDesc.getContentType()) && contentTypeToLSDefinition.isEnabled()) {
						getInitializedLanguageServer(editorFile, lsDefinition, capabilities -> true);
					}
				}
			} catch (Exception e) {
				LanguageServerPlugin.logError(e);
			}
		}
	}

	/**
	 * Get the requested language server instance for the given file. Starts the language server if not already started.
	 * @param file
	 * @param lsDefinition
	 * @param capabilitiesPredicate a predicate to check capabilities
	 * @return a LanguageServer for the given file, which is defined with provided server ID and conforms to specified request
	 * @deprecated use {@link #getInitializedLanguageServer(IFile, LanguageServerDefinition, Predicate)} instead.
	 */
	@Deprecated
	public static LanguageServer getLanguageServer(@NonNull IFile file, @NonNull LanguageServerDefinition lsDefinition,
			Predicate<ServerCapabilities> capabilitiesPredicate) throws IOException {
		LanguageServerWrapper wrapper = getLSWrapper(file.getProject(), lsDefinition, file.getFullPath());
		if (capabilitiesComply(wrapper, capabilitiesPredicate)) {
			wrapper.connect(file, null);
			return wrapper.getServer();
		}
		return null;
	}

	/**
	 * Get the requested language server instance for the given file. Starts the language server if not already started.
	 * @param file
	 * @param lsDefinition
	 * @param capabilitiesPredicate a predicate to check capabilities
	 * @return a LanguageServer for the given file, which is defined with provided server ID and conforms to specified request.
	 *  If {@code capabilitesPredicate} does not test positive for the server's capabilities, {@code null} is returned.
	 */
	public static CompletableFuture<LanguageServer> getInitializedLanguageServer(@NonNull IFile file,
			@NonNull LanguageServerDefinition lsDefinition, Predicate<ServerCapabilities> capabilitiesPredicate)
			throws IOException {
		LanguageServerWrapper wrapper = getLSWrapper(file.getProject(), lsDefinition, file.getFullPath());
		if (capabilitiesComply(wrapper, capabilitiesPredicate)) {
			return wrapper.getInitializedServer();
		}
		return null;
	}

	/**
	 * Get the requested language server instance for the given document. Starts the
	 * language server if not already started.
	 *
	 * @param document the document for which the initialized LanguageServer shall be returned
	 * @param lsDefinition the ID of the LanguageServer to be returned
	 * @param capabilitiesPredicate
	 *            a predicate to check capabilities
	 * @return a LanguageServer for the given file, which is defined with provided
	 *         server ID and conforms to specified request. If
	 *         {@code capabilitesPredicate} does not test positive for the server's
	 *         capabilities, {@code null} is returned.
	 */
	public static CompletableFuture<LanguageServer> getInitializedLanguageServer(@NonNull IDocument document,
			@NonNull LanguageServerDefinition lsDefinition, Predicate<ServerCapabilities> capabilitiesPredicate)
			throws IOException {
		IPath initialPath = LSPEclipseUtils.toPath(document);
		LanguageServerWrapper wrapper = getLSWrapperForConnection(document, lsDefinition, initialPath);
		if (capabilitiesComply(wrapper, capabilitiesPredicate)) {
			return wrapper.getInitializedServer();
		}
		return null;
	}

	/**
	 * Checks if the given {@code wrapper}'s capabilities comply with the given
	 * {@code capabilitiesPredicate}.
	 *
	 * @param wrapper
	 *            the server that's capabilities are tested with
	 *            {@code capabilitiesPredicate}
	 * @param capabilitiesPredicate
	 *            predicate testing the capabilities of {@code wrapper}.
	 * @return The result of applying the capabilities of {@code wrapper} to
	 *         {@code capabilitiesPredicate}, or {@code false} if
	 *         {@code capabilitiesPredicate == null} or
	 *         {@code wrapper.getServerCapabilities() == null}
	 */
	private static boolean capabilitiesComply(LanguageServerWrapper wrapper,
			Predicate<ServerCapabilities> capabilitiesPredicate) {
		return capabilitiesPredicate == null
				/* next null check is workaround for https://github.com/TypeFox/ls-api/issues/47 */
				|| wrapper.getServerCapabilities() == null
				|| capabilitiesPredicate.test(wrapper.getServerCapabilities());
	}

	/**
	 * TODO we need a similar method for generic IDocument (enabling non-IFiles)
	 *
	 * @param file
	 * @param request
	 * @return the matching LS wrappers
	 * @throws IOException
	 * @noreference This method is currently internal and should only be referenced
	 *              for testing
	 */
	@NonNull
	public static Collection<LanguageServerWrapper> getLSWrappers(@NonNull final IFile file,
			@Nullable final Predicate<ServerCapabilities> request) throws IOException {
		final var project = file.getProject();
		if (project == null) {
			return Collections.emptyList();
		}

		final var res = new LinkedHashSet<LanguageServerWrapper>(getMatchingStartedWrappers(file, request));

		// look for running language servers via content-type
		final var directContentTypes = LSPEclipseUtils.getFileContentTypes(file);
		final var contentTypesToProcess = new ArrayDeque<IContentType>(directContentTypes);
		final var processedContentTypes = new HashSet<IContentType>(directContentTypes.size());
		final var lsRegistry = LanguageServersRegistry.getInstance();

		while (!contentTypesToProcess.isEmpty()) {
			final var contentType = contentTypesToProcess.poll();
			if (contentType == null || processedContentTypes.contains(contentType)) {
				continue;
			}

			for (final ContentTypeToLanguageServerDefinition mapping : lsRegistry.findProviderFor(contentType)) {
				if (!mapping.isEnabled()) {
					continue;
				}
				final LanguageServerDefinition serverDefinition = mapping.getValue();
				if (serverDefinition == null) {
					continue;
				}

				final var wrapper = getLSWrapper(project, serverDefinition, file.getFullPath());
				if (!res.contains(wrapper) && capabilitiesComply(wrapper, request)) {
					res.add(wrapper);
				}
			}

			if (contentType.getBaseType() != null) {
				contentTypesToProcess.add(contentType.getBaseType());
			}
			processedContentTypes.add(contentType);
		}
		return res;
	}

	@NonNull
	private static Collection<LanguageServerWrapper> getLSWrappers(@NonNull final IDocument document) {
		final URI uri = LSPEclipseUtils.toUri(document);
		if (uri == null) {
			return Collections.emptyList();
		}

		final var lsRegistry = LanguageServersRegistry.getInstance();

		// look for already started compatible servers suitable for the given document
		final Predicate<LanguageServerWrapper> selectServersForDocument = wrapper -> {
			try {
				return wrapper.isConnectedTo(uri)
						|| (lsRegistry.matches(document, wrapper.serverDefinition) && wrapper.canOperate(document));
			} catch (Exception ex) {
				LanguageServerPlugin.logError(ex);
				return false;
			}
		};
		@NonNull
		final LinkedHashSet<LanguageServerWrapper> res = startedServers.stream().filter(selectServersForDocument)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		// look for running language servers via content-type
		final var directContentTypes = LSPEclipseUtils.getDocumentContentTypes(document);
		final var contentTypesToProcess = new ArrayDeque<IContentType>(directContentTypes);
		final var processedContentTypes = new HashSet<IContentType>(directContentTypes.size());
		final var path = new Path(uri.getPath());
		final var file = LSPEclipseUtils.getFile(document);

		while (!contentTypesToProcess.isEmpty()) {
			final var contentType = contentTypesToProcess.poll();
			if (contentType == null || processedContentTypes.contains(contentType)) {
				continue;
			}

			for (final ContentTypeToLanguageServerDefinition mapping : lsRegistry.findProviderFor(contentType)) {
				if (!mapping.isEnabled()) {
					continue;
				}
				final LanguageServerDefinition serverDefinition = mapping.getValue();
				if (serverDefinition == null) {
					continue;
				}
				final Predicate<LanguageServerWrapper> selectServersWithEqualDefinition = wrapper ->
						wrapper.serverDefinition .equals(serverDefinition);
				if (res.stream().anyMatch(selectServersWithEqualDefinition)) {
					// we already found a compatible LS with this definition
					continue;
				}

				synchronized (startedServers) {
					// check again while holding the write lock
					startedServers.stream().filter(selectServersForDocument).forEach(res::add);
					if (res.stream().anyMatch(selectServersWithEqualDefinition)) {
						// we already found a compatible LS with this definition
						continue;
					}

					final var fileProject = file != null ? file.getProject() : null;
					final var wrapper = fileProject != null //
							? new LanguageServerWrapper(fileProject, serverDefinition)
							: new LanguageServerWrapper(serverDefinition, path);
					startedServers.add(wrapper);
					res.add(wrapper);
				}
			}

			if (contentType.getBaseType() != null) {
			   contentTypesToProcess.add(contentType.getBaseType());
			}
			processedContentTypes.add(contentType);
		}
		return res;
	}

	/**
	 * Return existing {@link LanguageServerWrapper} for the given definition. If
	 * not found, create a new one with the given definition.
	 *
	 * @param project
	 * @param serverDefinition
	 * @return a new or existing {@link LanguageServerWrapper} for the given definition.
	 * @throws IOException
	 */
	public static LanguageServerWrapper getLSWrapper(@NonNull IProject project,
			@NonNull LanguageServerDefinition serverDefinition) throws IOException {
		return getLSWrapper(project, serverDefinition, null);
	}

	/**
	 * Return existing {@link LanguageServerWrapper} for the given connection. If
	 * not found, create a new one with the given connection and register it for
	 * this project/content-type.
	 *
	 * @param project
	 * @param serverDefinition
	 * @return a new or existing {@link LanguageServerWrapper} for the given connection.
	 * @throws IOException
	 * @Deprecated will be made private soon, use {@link #getLSWrapper}
	 * @noreference will be made private soon, use {@link #getLSWrapper}
	 * @deprecated
	 */
	@Deprecated
	public static LanguageServerWrapper getLSWrapperForConnection(@NonNull IProject project,
			@NonNull LanguageServerDefinition serverDefinition) throws IOException {
		return getLSWrapper(project, serverDefinition);
	}

	private static LanguageServerWrapper getLSWrapper(@Nullable IProject project,
			@NonNull LanguageServerDefinition serverDefinition, @Nullable IPath initialPath) throws IOException {

		final Predicate<LanguageServerWrapper> serverSelector = wrapper -> project != null && wrapper.canOperate(project)
				&& wrapper.serverDefinition.equals(serverDefinition);

		var matchingServer = startedServers.stream().filter(serverSelector).findFirst();
		if (matchingServer.isPresent()) {
			return matchingServer.get();
		}

		synchronized (startedServers) {
			// check again while holding the write lock
			matchingServer = startedServers.stream().filter(serverSelector).findFirst();
			if (matchingServer.isPresent()) {
				return matchingServer.get();
			}

			final var wrapper = project != null //
					? new LanguageServerWrapper(project, serverDefinition)
					: new LanguageServerWrapper(serverDefinition, initialPath);
			wrapper.start();

			startedServers.add(wrapper);
			return wrapper;
		}
	}

	public static @NonNull LanguageServerWrapper startLanguageServer(@NonNull LanguageServerDefinition serverDefinition) throws IOException {
		synchronized (startedServers) {
			LanguageServerWrapper wrapper = startedServers.stream().filter(w -> w.serverDefinition == serverDefinition).findFirst().orElseGet(() -> {
				LanguageServerWrapper w = new LanguageServerWrapper(serverDefinition, null);
				startedServers.add(w);
				return w;
			});
			if (!wrapper.isActive()) {
				wrapper.start();
			}
			return wrapper;
		}
	}

	private static LanguageServerWrapper getLSWrapperForConnection(@NonNull IDocument document,
			@NonNull LanguageServerDefinition serverDefinition, @Nullable IPath initialPath) throws IOException {

		final Predicate<LanguageServerWrapper> serverSelector = wrapper -> wrapper.canOperate(document)
				&& wrapper.serverDefinition.equals(serverDefinition);

		var matchingServer = startedServers.stream().filter(serverSelector).findFirst();
		if (matchingServer.isPresent()) {
			return matchingServer.get();
		}

		synchronized (startedServers) {
			// check again while holding the write lock
			matchingServer = startedServers.stream().filter(serverSelector).findFirst();
			if (matchingServer.isPresent()) {
				return matchingServer.get();
			}

			final var wrapper = new LanguageServerWrapper(serverDefinition, initialPath);
			wrapper.start();
			startedServers.add(wrapper);
			return wrapper;
		}
	}

	/**
	 * Interface to be used for passing lambdas to
	 * {@link LanguageServiceAccessor#addStartedServerSynchronized(ServerSupplier)}.
	 */
	@FunctionalInterface
	private static interface ServerSupplier {
		LanguageServerWrapper get() throws IOException;
	}

	private static Collection<LanguageServerWrapper> getMatchingStartedWrappers(@NonNull final IFile file,
			@Nullable final Predicate<ServerCapabilities> request) {

		final var lsRegistry = LanguageServersRegistry.getInstance();
		final var project = file.getProject();
		final var fileURI = file.getLocationURI();

		return startedServers.stream()
				.filter(wrapper -> wrapper.isActive() && wrapper.isConnectedTo(fileURI)
						|| (lsRegistry.matches(file, wrapper.serverDefinition)
								&& project != null && wrapper.canOperate(project)))
				.filter(wrapper -> request == null
						|| (wrapper.getServerCapabilities() == null || request.test(wrapper.getServerCapabilities())))
				.collect(Collectors.toList());
	}

	/**
	 * Gets list of running LS satisfying a capability predicate. This does not
	 * start any matching language servers, it returns the already running ones.
	 *
	 * @param request
	 * @return list of Language Servers
	 */
	@NonNull
	public static List<@NonNull LanguageServer> getActiveLanguageServers(Predicate<ServerCapabilities> request) {
		return getLanguageServers(null, request, true);
	}

	/**
	 * Gets list of LS initialized for given project.
	 *
	 * @param project
	 * @param request
	 * @return list of Language Servers
	 */
	@NonNull
	public static List<@NonNull LanguageServer> getLanguageServers(@NonNull IProject project,
			Predicate<ServerCapabilities> request) {
		return getLanguageServers(project, request, false);
	}

	/**
	 * Gets list of LS initialized for given project
	 *
	 * @param onlyActiveLS
	 *            true if this method should return only the already running
	 *            language servers, otherwise previously started language servers
	 *            will be re-activated
	 * @return list of Language Servers
	 */
	@NonNull
	public static List<@NonNull LanguageServer> getLanguageServers(@Nullable IProject project,
			Predicate<ServerCapabilities> request, boolean onlyActiveLS) {
		List<@NonNull LanguageServer> serverInfos = new ArrayList<>();
		for (LanguageServerWrapper wrapper : startedServers) {
			if ((!onlyActiveLS || wrapper.isActive()) && (project == null || wrapper.canOperate(project))) {
				@Nullable
				LanguageServer server = wrapper.getServer();
				if (server == null) {
					continue;
				}
				if (capabilitiesComply(wrapper, request)) {
					serverInfos.add(server);
				}
			}
		}
		return serverInfos;
	}

	protected static LanguageServerDefinition getLSDefinition(@NonNull StreamConnectionProvider provider) {
		return providersToLSDefinitions.get(provider);
	}

	@NonNull public static List<@NonNull LSPDocumentInfo> getLSPDocumentInfosFor(@NonNull IDocument document, @NonNull Predicate<ServerCapabilities> capabilityRequest) {
		URI fileUri = LSPEclipseUtils.toUri(document);
		List<LSPDocumentInfo> res = new ArrayList<>();
		try {
			getLSWrappers(document).stream().filter(wrapper -> wrapper.getServerCapabilities() == null
					|| capabilityRequest.test(wrapper.getServerCapabilities())).forEach(wrapper -> {
						try {
							wrapper.connect(document);
						} catch (IOException e) {
							LanguageServerPlugin.logError(e);
						}
						res.add(new LSPDocumentInfo(fileUri, document, wrapper));
					});
		} catch (final Exception e) {
			LanguageServerPlugin.logError(e);
		}
		return res;
	}

	/**
	 *
	 * @param document
	 * @param filter
	 * @return
	 * @since 0.9
	 */
	@NonNull
	public static CompletableFuture<List<@NonNull LanguageServer>> getLanguageServers(@NonNull IDocument document,
			Predicate<ServerCapabilities> filter) {
		URI uri = LSPEclipseUtils.toUri(document);
		if (uri == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		final List<@NonNull LanguageServer> res = Collections.synchronizedList(new ArrayList<>());
		try {
			return CompletableFuture.allOf(getLSWrappers(document).stream()
					.map(wrapper -> wrapper.getInitializedServer().thenComposeAsync(server -> {
						if (server != null && (filter == null || filter.test(wrapper.getServerCapabilities()))) {
							try {
								return wrapper.connect(document);
							} catch (IOException ex) {
								LanguageServerPlugin.logError(ex);
							}
						}
						return CompletableFuture.completedFuture(null);
					}).thenAccept(server -> {
						if (server != null) {
							res.add(server);
						}
					})).toArray(CompletableFuture[]::new)).thenApply(theVoid -> res);
		} catch (final Exception e) {
			LanguageServerPlugin.logError(e);
		}
		return CompletableFuture.completedFuture(Collections.emptyList());
	}

	public static boolean checkCapability(LanguageServer languageServer, Predicate<ServerCapabilities> condition) {
		return startedServers.stream() //
				.filter(wrapper -> wrapper.isActive() && wrapper.getServer() == languageServer)
				.anyMatch(wrapper -> condition.test(wrapper.getServerCapabilities()));
	}

	public static Optional<LanguageServerDefinition> resolveServerDefinition(LanguageServer languageServer) {
		return startedServers.stream() //
				.filter(wrapper -> languageServer.equals(wrapper.getServer())).findFirst()
				.map(wrapper -> wrapper.serverDefinition);
	}
}
