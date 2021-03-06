package com.gentics.mesh.search.index.node;

import static com.gentics.mesh.core.rest.common.ContainerType.DRAFT;
import static com.gentics.mesh.core.rest.common.ContainerType.PUBLISHED;
import static com.gentics.mesh.core.rest.error.Errors.error;
import static com.gentics.mesh.search.index.node.NodeIndexUtil.getDefaultSetting;
import static com.gentics.mesh.search.index.node.NodeIndexUtil.getLanguageOverride;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.gentics.mesh.cli.BootstrapInitializer;
import com.gentics.mesh.context.InternalActionContext;
import com.gentics.mesh.core.data.Branch;
import com.gentics.mesh.core.data.Bucket;
import com.gentics.mesh.core.data.NodeGraphFieldContainer;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.branch.HibBranch;
import com.gentics.mesh.core.data.dao.BranchDaoWrapper;
import com.gentics.mesh.core.data.dao.ContentDaoWrapper;
import com.gentics.mesh.core.data.dao.ProjectDaoWrapper;
import com.gentics.mesh.core.data.dao.SchemaDaoWrapper;
import com.gentics.mesh.core.data.node.HibNode;
import com.gentics.mesh.core.data.node.Node;
import com.gentics.mesh.core.data.perm.InternalPermission;
import com.gentics.mesh.core.data.project.HibProject;
import com.gentics.mesh.core.data.schema.HibSchemaVersion;
import com.gentics.mesh.core.data.search.BucketableElement;
import com.gentics.mesh.core.data.search.MoveDocumentEntry;
import com.gentics.mesh.core.data.search.UpdateDocumentEntry;
import com.gentics.mesh.core.data.search.bulk.BulkEntry;
import com.gentics.mesh.core.data.search.bulk.IndexBulkEntry;
import com.gentics.mesh.core.data.search.bulk.UpdateBulkEntry;
import com.gentics.mesh.core.data.search.context.GenericEntryContext;
import com.gentics.mesh.core.data.search.context.MoveEntryContext;
import com.gentics.mesh.core.data.search.context.impl.GenericEntryContextImpl;
import com.gentics.mesh.core.data.search.index.IndexInfo;
import com.gentics.mesh.core.data.search.request.CreateDocumentRequest;
import com.gentics.mesh.core.data.search.request.SearchRequest;
import com.gentics.mesh.core.db.Tx;
import com.gentics.mesh.core.rest.common.ContainerType;
import com.gentics.mesh.core.rest.schema.SchemaModel;
import com.gentics.mesh.core.rest.schema.SchemaVersionModel;
import com.gentics.mesh.etc.config.MeshOptions;
import com.gentics.mesh.graphdb.spi.Database;
import com.gentics.mesh.graphdb.spi.Transactional;
import com.gentics.mesh.search.SearchProvider;
import com.gentics.mesh.search.index.BucketManager;
import com.gentics.mesh.search.index.entry.AbstractIndexHandler;
import com.gentics.mesh.search.index.metric.SyncMetersFactory;
import com.gentics.mesh.search.verticle.eventhandler.MeshHelper;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Handler for the node specific search index.
 * 
 * This handler can process {@link UpdateDocumentEntry} objects which may contain additional {@link GenericEntryContextImpl} information. The handler will use
 * the context information in order to determine which elements need to be stored in or removed from the index.
 * 
 * Additionally the handler may infer the scope of store actions if the context information is lacking certain information. A context which does not include the
 * target language will result in multiple store actions. Each language container will be loaded and stored. This behaviour will also be applied to branches and
 * project information.
 */
@Singleton
public class NodeIndexHandlerImpl extends AbstractIndexHandler<HibNode> implements NodeIndexHandler {

	private static final Logger log = LoggerFactory.getLogger(NodeIndexHandlerImpl.class);

	@Inject
	public NodeContainerTransformer transformer;

	@Inject
	public NodeContainerMappingProvider mappingProvider;

	@Inject
	public NodeIndexHandlerImpl(SearchProvider searchProvider, Database db, BootstrapInitializer boot, MeshHelper helper, MeshOptions options,
		SyncMetersFactory syncMetersFactory, BucketManager bucketManager) {
		super(searchProvider, db, boot, helper, options, syncMetersFactory, bucketManager);
	}

	@Override
	public Class<? extends BucketableElement> getElementClass() {
		return Node.class;
	}

	@Override
	public String getType() {
		return "node";
	}

	@Override
	protected String composeDocumentIdFromEntry(UpdateDocumentEntry entry) {
		return ContentDaoWrapper.composeDocumentId(entry.getElementUuid(), entry.getContext().getLanguageTag());
	}

	@Override
	protected String composeIndexNameFromEntry(UpdateDocumentEntry entry) {
		GenericEntryContext context = entry.getContext();
		String projectUuid = context.getProjectUuid();
		String branchUuid = context.getBranchUuid();
		String schemaContainerVersionUuid = context.getSchemaContainerVersionUuid();
		ContainerType type = context.getContainerType();
		return ContentDaoWrapper.composeIndexName(projectUuid, branchUuid, schemaContainerVersionUuid, type);
	}

	@Override
	public NodeContainerTransformer getTransformer() {
		return transformer;
	}

	@Override
	public NodeContainerMappingProvider getMappingProvider() {
		return mappingProvider;
	}

	@Override
	public long getTotalCountFromGraph() {
		return db.tx(tx -> {
			return tx.data().contentDao().globalCount();
		});
	}

	@Override
	public Map<String, IndexInfo> getIndices() {
		return db.tx(tx -> {
			Map<String, IndexInfo> indexInfo = new HashMap<>();

			// Iterate over all projects and construct the index names
			for (Project project : boot.meshRoot().getProjectRoot().findAll()) {
				for (Branch branch : project.getBranchRoot().findAll()) {
					indexInfo.putAll(getIndices(project, branch).runInExistingTx(tx));
				}
			}
			return indexInfo;
		});
	}

	public Transactional<Map<String, IndexInfo>> getIndices(HibProject project, HibBranch branch) {
		return db.transactional(tx -> {
			Map<String, IndexInfo> indexInfo = new HashMap<>();
			// Each branch specific index has also document type specific mappings
			for (HibSchemaVersion containerVersion : branch.findActiveSchemaVersions()) {
				indexInfo.putAll(getIndices(project, branch, containerVersion).runInExistingTx(tx));
			}
			return indexInfo;
		});
	}

	public Transactional<Map<String, IndexInfo>> getIndices(HibProject project, HibBranch branch, HibSchemaVersion containerVersion) {
		return db.transactional(tx -> {
			Map<String, IndexInfo> indexInfos = new HashMap<>();
			SchemaVersionModel schema = containerVersion.getSchema();

			// Add all language specific indices (might be none)
			schema.findOverriddenSearchLanguages().forEach(language -> Stream.of(DRAFT, PUBLISHED).forEach(version -> {
				String indexName = ContentDaoWrapper.composeIndexName(project.getUuid(), branch.getUuid(), containerVersion
					.getUuid(), version, language);
				log.debug("Adding index to map of known indices {" + indexName + "}");
				// Load the index mapping information for the index
				indexInfos.put(indexName, createIndexInfo(branch, schema, language, indexName, schema.getName() + "@" + schema.getVersion()));
			}));

			// And all default indices
			Stream.of(DRAFT, PUBLISHED).forEach(version -> {
				String indexName = ContentDaoWrapper.composeIndexName(project.getUuid(), branch.getUuid(), containerVersion
					.getUuid(), version);
				log.debug("Adding index to map of known indices {" + indexName + "}");
				// Load the index mapping information for the index
				indexInfos.put(indexName, createIndexInfo(branch, schema, null, indexName, schema.getName() + "@" + schema.getVersion()));
			});
			return indexInfos;
		});
	}

	public IndexInfo createIndexInfo(HibBranch branch, SchemaModel schema, String language, String indexName, String sourceInfo) {
		JsonObject mapping = getMappingProvider().getMapping(schema, branch, language);
		JsonObject settings = language == null
			? getDefaultSetting(schema.getElasticsearch())
			: getLanguageOverride(schema.getElasticsearch(), language);
		return new IndexInfo(indexName, settings, mapping, sourceInfo);
	}

	@Override
	public Set<String> filterUnknownIndices(Set<String> indices) {
		Set<String> activeIndices = new HashSet<>();
		db.tx(tx -> {
			ProjectDaoWrapper projectDao = tx.data().projectDao();
			BranchDaoWrapper branchDao = tx.data().branchDao();
			SchemaDaoWrapper schemaDao = tx.data().schemaDao();
			for (HibProject currentProject : projectDao.findAll()) {
				for (HibBranch branch : branchDao.findAll(currentProject)) {
					for (HibSchemaVersion version : schemaDao.findActiveSchemaVersions(branch)) {
						if (log.isDebugEnabled()) {
							log.debug("Found active schema version {}-{} in branch {}", version.getSchema().getName(), version.getVersion(),
								branch.getName());
						}
						Arrays.asList(ContainerType.DRAFT, ContainerType.PUBLISHED).forEach(type -> {
							activeIndices
								.add(ContentDaoWrapper.composeIndexName(currentProject.getUuid(), branch.getUuid(), version.getUuid(),
									type));
						});
					}
				}
			}
		});

		if (log.isDebugEnabled()) {
			log.debug(
				"All indices:\n" +
					String.join("\n", indices) + "\n" +
					"Active indices: \n" +
					String.join("\n", activeIndices));
		}
		return indices.stream()
			// Only handle indices of the handler's type
			.filter(i -> i.startsWith(getType()))
			// Filter out indices which are active
			.filter(i -> !activeIndices.contains(i))
			.collect(Collectors.toSet());
	}

	@Override
	public Flowable<SearchRequest> syncIndices() {
		return Flowable.defer(() -> db.tx(tx -> {
			ProjectDaoWrapper projectDao = tx.data().projectDao();
			BranchDaoWrapper branchDao = tx.data().branchDao();
			SchemaDaoWrapper schemaDao = tx.data().schemaDao();

			return projectDao.findAll().stream()
				.flatMap(project -> branchDao.findAll(project).stream()
					.flatMap(branch -> branch.findActiveSchemaVersions().stream()
						.flatMap(version -> Stream.of(DRAFT, PUBLISHED)
							.map(type -> diffAndSync(project, branch, version, type)))))
				.collect(Collectors.collectingAndThen(Collectors.toList(), Flowable::merge));
		}));
	}

	/**
	 *
	 * @param branch
	 * @param version
	 * @param type
	 * @param bucket
	 * @return indexName -> documentName -> NodeGraphFieldContainer
	 */
	private Map<String, Map<String, NodeGraphFieldContainer>> loadVersionsFromGraph(HibBranch branch, HibSchemaVersion version, ContainerType type,
		Bucket bucket) {
		return db.tx(tx -> {
			ContentDaoWrapper contentDao = tx.data().contentDao();
			SchemaDaoWrapper schemaDao = tx.data().schemaDao();
			String branchUuid = branch.getUuid();
			List<String> indexLanguages = version.getSchema().findOverriddenSearchLanguages().collect(Collectors.toList());

			return schemaDao.getFieldContainers(version, branchUuid, bucket)
				.filter(c -> c.isType(type, branchUuid))
				.map(NodeGraphFieldContainer.class::cast)
				.collect(Collectors.groupingBy(content -> {
					String languageTag = content.getLanguageTag();
					return ContentDaoWrapper.composeIndexName(
						branch.getProject().getUuid(),
						branchUuid,
						version.getUuid(),
						type,
						indexLanguages.contains(languageTag)
							? languageTag
							: null);
				},
					Collectors.toMap(c -> contentDao.getNode(c).getUuid() + "-" + c.getLanguageTag(), Function.identity())));
		});
	}

	/**
	 * We need to override the default method since the UUID alone is not enough to id a document in the node index. We also need to append the language.
	 */
	@Override
	protected void processHits(JsonArray hits, Map<String, String> versions) {
		for (int i = 0; i < hits.size(); i++) {
			JsonObject hit = hits.getJsonObject(i);
			JsonObject source = hit.getJsonObject("_source");
			// The id contains the UUID + language
			String uuidAndLang = hit.getString("_id");
			String version = source.getString("version");
			versions.put(uuidAndLang, version);
		}
	}

	private Flowable<SearchRequest> diffAndSync(HibProject project, HibBranch branch, HibSchemaVersion version, ContainerType type) {
		log.info("Handling index sync on handler {" + getClass().getName() + "}");
		// Sync each bucket individually
		Flowable<Bucket> buckets = bucketManager.getBuckets(getTotalCountFromGraph());
		return buckets.flatMap(bucket -> {
			log.info("Handling sync of {" + bucket + "}");
			return diffAndSync(project, branch, version, type, bucket);
		}, 1);
	}

	private Flowable<SearchRequest> diffAndSync(HibProject project, HibBranch branch, HibSchemaVersion version, ContainerType type, Bucket bucket) {
		return Flowable.defer(() -> {
			Map<String, Map<String, NodeGraphFieldContainer>> sourceNodesPerIndex = loadVersionsFromGraph(branch, version, type, bucket);
			return Flowable.fromIterable(getIndexNames(project, branch, version, type))
				.flatMap(indexName -> loadVersionsFromIndex(indexName, bucket).flatMapPublisher(sinkVersions -> {
					log.debug("Handling index sync on handler {" + getClass().getName() + "} for bucket {" + bucket + "}");
					Map<String, NodeGraphFieldContainer> sourceNodes = sourceNodesPerIndex.getOrDefault(indexName, Collections.emptyMap());
					String branchUuid = branch.getUuid();

					Map<String, String> sourceVersions = db.tx(() -> sourceNodes.entrySet().stream()
						.collect(Collectors.toMap(Map.Entry::getKey, x -> generateVersion(x.getValue(), branchUuid, type))));

					// 3. Diff the maps
					MapDifference<String, String> diff = Maps.difference(sourceVersions, sinkVersions);
					if (diff.areEqual()) {
						return Flowable.empty();
					}
					Set<String> needInsertionInES = diff.entriesOnlyOnLeft().keySet();
					Set<String> needRemovalInES = diff.entriesOnlyOnRight().keySet();
					Set<String> needUpdateInEs = diff.entriesDiffering().keySet();

					log.debug("Pending insertions on {" + indexName + "}:" + needInsertionInES.size());
					log.debug("Pending removals on {" + indexName + "}:" + needRemovalInES.size());
					log.debug("Pending updates on {" + indexName + "}:" + needUpdateInEs.size());

					meters.getInsertMeter().addPending(needInsertionInES.size());
					meters.getDeleteMeter().addPending(needRemovalInES.size());
					meters.getUpdateMeter().addPending(needUpdateInEs.size());

					io.reactivex.functions.Function<Action, io.reactivex.functions.Function<String, CreateDocumentRequest>> toCreateRequest = action -> uuid -> {
						JsonObject doc = db.tx(() -> getTransformer().toDocument(sourceNodes.get(uuid), branchUuid, type));
						return helper.createDocumentRequest(indexName, uuid, doc, complianceMode, action);
					};

					Flowable<SearchRequest> toInsert = Flowable.fromIterable(needInsertionInES)
						.map(toCreateRequest.apply(meters.getInsertMeter()::synced));

					Flowable<SearchRequest> toUpdate = Flowable.fromIterable(needUpdateInEs)
						.map(toCreateRequest.apply(meters.getUpdateMeter()::synced));

					Flowable<SearchRequest> toDelete = Flowable.fromIterable(needRemovalInES)
						.map(uuid -> helper.deleteDocumentRequest(indexName, uuid, complianceMode, meters.getDeleteMeter()::synced));

					return Flowable.merge(toInsert, toUpdate, toDelete);
				}));
		});
	}

	private List<String> getIndexNames(HibProject project, HibBranch branch, HibSchemaVersion version, ContainerType type) {
		return db.tx(() -> {
			Stream<String> languageIndices = version.getSchema().findOverriddenSearchLanguages()
				.map(lang -> ContentDaoWrapper.composeIndexName(
					project.getUuid(),
					branch.getUuid(),
					version.getUuid(),
					type,
					lang));
			Stream<String> defaultIndex = Stream.of(ContentDaoWrapper.composeIndexName(
				project.getUuid(),
				branch.getUuid(),
				version.getUuid(),
				type));
			return Stream.concat(languageIndices, defaultIndex)
				.collect(Collectors.toList());
		});
	}

	public Set<String> getIndicesForSearch(InternalActionContext ac, ContainerType type) {
		return db.tx(() -> {
			HibProject project = ac.getProject();
			if (project != null) {
				HibBranch branch = ac.getBranch();
				return Collections.singleton(ContentDaoWrapper.composeIndexPattern(
					project.getUuid(),
					branch.getUuid(),
					type));
			} else {
				return Collections.singleton(ContentDaoWrapper.composeIndexPattern(type));
			}
		});
	}

	@Override
	public Set<String> getIndicesForSearch(InternalActionContext ac) {
		return getIndicesForSearch(ac, ContainerType.forVersion(ac.getVersioningParameters().getVersion()));
	}

	@Override
	public Completable store(HibNode node, UpdateDocumentEntry entry) {
		return Completable.defer(() -> {
			GenericEntryContext context = entry.getContext();
			Set<Single<String>> obs = new HashSet<>();
			db.tx(() -> {
				store(obs, node, context);
			});

			// Now merge all store actions and refresh the affected indices
			return Observable.fromIterable(obs).map(x -> x.toObservable()).flatMap(x -> x).distinct().ignoreElements();
		});
	}

	public Observable<IndexBulkEntry> storeForBulk(HibNode node, UpdateDocumentEntry entry) {
		GenericEntryContext context = entry.getContext();
		return db.tx(() -> {
			return storeForBulk(node, context);
		});
	}

	/**
	 * Step 1 - Check whether we need to handle all branches.
	 * 
	 * @param obs
	 * @param node
	 * @param context
	 */
	private void store(Set<Single<String>> obs, HibNode node, GenericEntryContext context) {
		if (context.getBranchUuid() == null) {
			for (HibBranch branch : Tx.get().data().branchDao().findAll(node.getProject())) {
				store(obs, node, branch.getUuid(), context);
			}
		} else {
			store(obs, node, context.getBranchUuid(), context);
		}
	}

	/**
	 * Step 2 - Check whether we need to handle all container types.
	 * 
	 * Add the possible store actions to the set of observables. This method will utilise as much of the provided context data if possible. It will also handle
	 * fallback options and invoke store for all types if the container type has not been specified.
	 * 
	 * @param obs
	 * @param node
	 * @param branchUuid
	 * @param context
	 */
	private void store(Set<Single<String>> obs, HibNode node, String branchUuid, GenericEntryContext context) {
		if (context.getContainerType() == null) {
			for (ContainerType type : ContainerType.values()) {
				// We only want to store DRAFT and PUBLISHED Types
				if (type == DRAFT || type == PUBLISHED) {
					store(obs, node, branchUuid, type, context);
				}
			}
		} else {
			store(obs, node, branchUuid, context.getContainerType(), context);
		}
	}

	/**
	 * Step 3 - Check whether we need to handle all languages.
	 * 
	 * Invoke store for the possible set of containers. Utilise the given context settings as much as possible.
	 * 
	 * @param obs
	 * @param node
	 * @param branchUuid
	 * @param type
	 * @param context
	 */
	private void store(Set<Single<String>> obs, HibNode node, String branchUuid, ContainerType type, GenericEntryContext context) {
		ContentDaoWrapper contentDao = Tx.get().data().contentDao();
		if (context.getLanguageTag() != null) {
			NodeGraphFieldContainer container = contentDao.getGraphFieldContainer(node, context.getLanguageTag(), branchUuid, type);
			if (container == null) {
				log.warn("Node {" + node.getUuid() + "} has no language container for languageTag {" + context.getLanguageTag()
					+ "}. I can't store the search index document. This may be normal in cases if mesh is handling an outdated search queue batch entry.");
			} else {
				obs.add(storeContainer(container, branchUuid, type));
			}
		} else {
			for (NodeGraphFieldContainer container : contentDao.getGraphFieldContainers(node, branchUuid, type)) {
				obs.add(storeContainer(container, branchUuid, type));
			}
		}

	}

	/**
	 * Step 1 - Check whether we need to handle all branches.
	 * 
	 * @param node
	 * @param context
	 * @return
	 */
	private Observable<IndexBulkEntry> storeForBulk(HibNode node, GenericEntryContext context) {
		if (context.getBranchUuid() == null) {
			Set<Observable<IndexBulkEntry>> obs = new HashSet<>();
			for (HibBranch branch : Tx.get().data().branchDao().findAll(node.getProject())) {
				obs.add(storeForBulk(node, branch.getUuid(), context));
			}
			return Observable.merge(obs);
		} else {
			return storeForBulk(node, context.getBranchUuid(), context);
		}
	}

	/**
	 * Step 2 - Check whether we need to handle all container types.
	 * 
	 * Add the possible store actions to the set of observables. This method will utilise as much of the provided context data if possible. It will also handle
	 * fallback options and invoke store for all types if the container type has not been specified.
	 * 
	 * @param node
	 * @param branchUuid
	 * @param context
	 * @return
	 */
	private Observable<IndexBulkEntry> storeForBulk(HibNode node, String branchUuid, GenericEntryContext context) {
		if (context.getContainerType() == null) {
			Set<Observable<IndexBulkEntry>> obs = new HashSet<>();
			for (ContainerType type : ContainerType.values()) {
				// We only want to store DRAFT and PUBLISHED Types
				if (type == DRAFT || type == PUBLISHED) {
					obs.add(storeForBulk(node, branchUuid, type, context));
				}
			}
			return Observable.merge(obs);
		} else {
			return storeForBulk(node, branchUuid, context.getContainerType(), context);
		}
	}

	/**
	 * Step 3 - Check whether we need to handle all languages.
	 * 
	 * Invoke store for the possible set of containers. Utilise the given context settings as much as possible.
	 * 
	 * @param node
	 * @param branchUuid
	 * @param type
	 * @param context
	 * @return
	 */
	private Observable<IndexBulkEntry> storeForBulk(HibNode node, String branchUuid, ContainerType type, GenericEntryContext context) {
		ContentDaoWrapper contentDao = Tx.get().data().contentDao();

		if (context.getLanguageTag() != null) {
			NodeGraphFieldContainer container = contentDao.getGraphFieldContainer(node, context.getLanguageTag(), branchUuid, type);
			if (container == null) {
				log.warn("Node {" + node.getUuid() + "} has no language container for languageTag {" + context.getLanguageTag()
					+ "}. I can't store the search index document. This may be normal in cases if mesh is handling an outdated search queue batch entry.");
			} else {
				return storeContainerForBulk(container, branchUuid, type).toObservable();
			}
		} else {
			Set<Observable<IndexBulkEntry>> obs = new HashSet<>();
			for (NodeGraphFieldContainer container : contentDao.getGraphFieldContainers(node, branchUuid, type)) {
				obs.add(storeContainerForBulk(container, branchUuid, type).toObservable());
			}
			return Observable.merge(obs);
		}
		return Observable.empty();

	}

	/**
	 * Remove the old container from its index and add the new container to the new index.
	 * 
	 * @param entry
	 * @return
	 */
	public Completable move(MoveDocumentEntry entry) {
		MoveEntryContext context = entry.getContext();
		ContainerType type = context.getContainerType();
		String branchUuid = context.getBranchUuid();
		return storeContainer(context.getNewContainer(), branchUuid, type).toCompletable().andThen(deleteContainer(context.getOldContainer(),
			branchUuid, type));
	}

	public Observable<BulkEntry> moveForBulk(MoveDocumentEntry entry) {
		ContentDaoWrapper contentDao = boot.contentDao();
		MoveEntryContext context = entry.getContext();
		ContainerType type = context.getContainerType();
		String releaseUuid = context.getBranchUuid();

		NodeGraphFieldContainer newContainer = context.getNewContainer();
		String newProjectUuid = contentDao.getNode(newContainer).getProject().getUuid();
		String newIndexName = ContentDaoWrapper.composeIndexName(newProjectUuid, releaseUuid,
			newContainer.getSchemaContainerVersion().getUuid(),
			type);
		String newLanguageTag = newContainer.getLanguageTag();
		String newDocumentId = ContentDaoWrapper.composeDocumentId(contentDao.getNode(newContainer).getUuid(), newLanguageTag);
		JsonObject doc = transformer.toDocument(newContainer, releaseUuid, type);
		return Observable.just(new IndexBulkEntry(newIndexName, newDocumentId, doc, complianceMode));

	}

	/**
	 * Deletes the container for the index in which it should reside.
	 * 
	 * @param container
	 * @param branchUuid
	 * @param type
	 * @return
	 */
	private Completable deleteContainer(NodeGraphFieldContainer container, String branchUuid, ContainerType type) {
		ContentDaoWrapper contentDao = boot.contentDao();
		String projectUuid = contentDao.getNode(container).getProject().getUuid();
		return searchProvider.deleteDocument(contentDao.getIndexName(container, projectUuid, branchUuid, type), contentDao.getDocumentId(container));
	}

	/**
	 * Generate an elasticsearch document object from the given container and stores it in the search index.
	 * 
	 * @param container
	 * @param branchUuid
	 * @param type
	 * @return Single with affected index name
	 */
	public Single<String> storeContainer(NodeGraphFieldContainer container, String branchUuid, ContainerType type) {
		ContentDaoWrapper contentDao = boot.contentDao();
		JsonObject doc = transformer.toDocument(container, branchUuid, type);
		String projectUuid = contentDao.getNode(container).getProject().getUuid();
		String indexName = ContentDaoWrapper.composeIndexName(projectUuid, branchUuid, container.getSchemaContainerVersion().getUuid(), type);
		if (log.isDebugEnabled()) {
			log.debug("Storing node {" + contentDao.getNode(container).getUuid() + "} into index {" + indexName + "}");
		}
		String languageTag = container.getLanguageTag();
		String documentId = ContentDaoWrapper.composeDocumentId(contentDao.getNode(container).getUuid(), languageTag);
		return searchProvider.storeDocument(indexName, documentId, doc).andThen(Single.just(indexName));
	}

	/**
	 * Generate an elasticsearch document object from the given container and stores it in the search index.
	 * 
	 * @param container
	 * @param branchUuid
	 * @param type
	 * @return Single with the bulk entry
	 */
	public Single<IndexBulkEntry> storeContainerForBulk(NodeGraphFieldContainer container, String branchUuid, ContainerType type) {
		ContentDaoWrapper contentDao = boot.contentDao();
		JsonObject doc = transformer.toDocument(container, branchUuid, type);
		String projectUuid = contentDao.getNode(container).getProject().getUuid();
		String indexName = ContentDaoWrapper.composeIndexName(projectUuid, branchUuid, container.getSchemaContainerVersion().getUuid(), type);
		if (log.isDebugEnabled()) {
			log.debug("Storing node {" + contentDao.getNode(container).getUuid() + "} into index {" + indexName + "}");
		}
		String languageTag = container.getLanguageTag();
		String documentId = ContentDaoWrapper.composeDocumentId(contentDao.getNode(container).getUuid(), languageTag);

		return Single.just(new IndexBulkEntry(indexName, documentId, doc, complianceMode));
	}

	@Override
	public InternalPermission getReadPermission(InternalActionContext ac) {
		switch (ContainerType.forVersion(ac.getVersioningParameters().getVersion())) {
		case PUBLISHED:
			return InternalPermission.READ_PUBLISHED_PERM;
		default:
			return InternalPermission.READ_PERM;
		}
	}

	/**
	 * We need to handle permission update requests for nodes here since the action must affect multiple documents in multiple indices .
	 */
	@Override
	public Observable<UpdateBulkEntry> updatePermissionForBulk(UpdateDocumentEntry entry) {
		String uuid = entry.getElementUuid();
		HibNode node = elementLoader().apply(uuid);
		if (node == null) {
			// Not found
			throw error(INTERNAL_SERVER_ERROR, "error_element_not_found", uuid);
		} else {
			BranchDaoWrapper branchDao = Tx.get().data().branchDao();
			ContentDaoWrapper contentDao = Tx.get().data().contentDao();

			HibProject project = node.getProject();
			List<UpdateBulkEntry> entries = new ArrayList<>();

			// Determine which documents need to be updated. The node could have multiple documents in various indices.
			for (HibBranch branch : branchDao.findAll(project)) {
				for (ContainerType type : Arrays.asList(DRAFT, PUBLISHED)) {
					JsonObject json = getTransformer().toPermissionPartial(node, type);
					for (NodeGraphFieldContainer container : contentDao.getGraphFieldContainers(node, branch, type)) {
						String indexName = boot.contentDao().getIndexName(container, project.getUuid(), branch.getUuid(), type);
						String documentId = boot.contentDao().getDocumentId(container);
						entries.add(new UpdateBulkEntry(indexName, documentId, json, complianceMode));
					}
				}
			}

			return Observable.fromIterable(entries);
		}
	}

	/**
	 * Validate the schema by creating an index template.
	 * 
	 * @param schema
	 */
	public Completable validate(SchemaModel schema) {
		return Flowable.defer(() -> {
			schema.validate();
			return Flowable.fromIterable(schema.findOverriddenSearchLanguages()::iterator);
		}).map(language -> createDummyIndexInfo(schema, language))
			.startWith(createDummyIndexInfo(schema, null))
			.flatMapCompletable(searchProvider::validateCreateViaTemplate);
	}

	/**
	 * Construct the full index settings using the provided schema and language as a source.
	 *
	 * @param schema
	 * @return
	 */
	public JsonObject createIndexSettings(SchemaModel schema) {
		return createIndexSettings(schema, null);
	}

	/**
	 * Construct the full index settings using the provided schema and language as a source.
	 *
	 * @param schema
	 * @return
	 */
	public JsonObject createIndexSettings(SchemaModel schema, String language) {
		return searchProvider.createIndexSettings(createDummyIndexInfo(schema, language));
	}

	private IndexInfo createDummyIndexInfo(SchemaModel schema, String language) {
		String indexName = language != null
			? "validationDummy-" + language
			: "validationDummy";
		return createIndexInfo(null, schema, language, indexName, schema.getName());
	}

	/**
	 * Generate the version for the container that should be transformed.
	 * 
	 * @param container
	 * @param branchUuid
	 * @param type
	 * @return
	 */
	public String generateVersion(NodeGraphFieldContainer container, String branchUuid, ContainerType type) {
		return getTransformer().generateVersion(container, branchUuid, type);
	}

	@Override
	public Function<String, HibNode> elementLoader() {
		return (uuid) -> db.index().findByUuid(Node.class, uuid);
	}

	@Override
	public Stream<? extends HibNode> loadAllElements(Tx tx) {
		return db.type().findAll(Node.class);
	}

}
