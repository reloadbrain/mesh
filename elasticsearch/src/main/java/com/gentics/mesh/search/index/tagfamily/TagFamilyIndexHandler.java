package com.gentics.mesh.search.index.tagfamily;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.gentics.mesh.cli.BootstrapInitializer;
import com.gentics.mesh.context.InternalActionContext;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.TagFamily;
import com.gentics.mesh.core.data.project.HibProject;
import com.gentics.mesh.core.data.root.ProjectRoot;
import com.gentics.mesh.core.data.search.UpdateDocumentEntry;
import com.gentics.mesh.core.data.search.bulk.IndexBulkEntry;
import com.gentics.mesh.core.data.search.index.IndexInfo;
import com.gentics.mesh.core.data.search.request.SearchRequest;
import com.gentics.mesh.core.data.tagfamily.HibTagFamily;
import com.gentics.mesh.core.db.Tx;
import com.gentics.mesh.etc.config.MeshOptions;
import com.gentics.mesh.graphdb.spi.Database;
import com.gentics.mesh.search.SearchProvider;
import com.gentics.mesh.search.index.BucketManager;
import com.gentics.mesh.search.index.entry.AbstractIndexHandler;
import com.gentics.mesh.search.index.metric.SyncMetersFactory;
import com.gentics.mesh.search.verticle.eventhandler.MeshHelper;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;

@Singleton
public class TagFamilyIndexHandler extends AbstractIndexHandler<HibTagFamily> {

	@Inject
	TagFamilyTransformer transformer;

	@Inject
	TagFamilyMappingProvider mappingProvider;

	@Inject
	public TagFamilyIndexHandler(SearchProvider searchProvider, Database db, BootstrapInitializer boot, MeshHelper helper, MeshOptions options,
		SyncMetersFactory syncMetricsFactory, BucketManager bucketManager) {
		super(searchProvider, db, boot, helper, options, syncMetricsFactory, bucketManager);
	}

	@Override
	public String getType() {
		return "tagfamily";
	}

	@Override
	public Class<TagFamily> getElementClass() {
		return TagFamily.class;
	}

	@Override
	public long getTotalCountFromGraph() {
		return db.tx(tx -> {
			return tx.data().tagFamilyDao().globalCount();
		});
	}

	@Override
	public TagFamilyTransformer getTransformer() {
		return transformer;
	}

	@Override
	public TagFamilyMappingProvider getMappingProvider() {
		return mappingProvider;
	}

	@Override
	protected String composeDocumentIdFromEntry(UpdateDocumentEntry entry) {
		return TagFamily.composeDocumentId(entry.getElementUuid());
	}

	@Override
	protected String composeIndexNameFromEntry(UpdateDocumentEntry entry) {
		return TagFamily.composeIndexName(entry.getContext().getProjectUuid());
	}

	@Override
	public Completable store(HibTagFamily tagFamily, UpdateDocumentEntry entry) {
		entry.getContext().setProjectUuid(tagFamily.getProject().getUuid());
		return super.store(tagFamily, entry);
	}

	@Override
	public Observable<IndexBulkEntry> storeForBulk(HibTagFamily tagFamily, UpdateDocumentEntry entry) {
		entry.getContext().setProjectUuid(tagFamily.getProject().getUuid());
		return super.storeForBulk(tagFamily, entry);
	}

	@Override
	public Map<String, IndexInfo> getIndices() {
		return db.tx(() -> {
			ProjectRoot root = boot.meshRoot().getProjectRoot();
			Map<String, IndexInfo> indexInfo = new HashMap<>();
			for (Project project : root.findAll()) {
				String indexName = TagFamily.composeIndexName(project.getUuid());
				IndexInfo info = new IndexInfo(indexName, null, getMappingProvider().getMapping(), "tagFamily");
				indexInfo.put(indexName, info);
			}
			return indexInfo;
		});
	}

	@Override
	public Flowable<SearchRequest> syncIndices() {
		return Flowable.defer(() -> db.tx(() -> {
			return boot.meshRoot().getProjectRoot().findAll().stream()
				.map(project -> {
					String uuid = project.getUuid();
					String indexName = TagFamily.composeIndexName(uuid);
					return diffAndSync(indexName, uuid);
				}).collect(Collectors.collectingAndThen(Collectors.toList(), Flowable::merge));
		}));
	}

	@Override
	public Set<String> filterUnknownIndices(Set<String> indices) {
		return db.tx(() -> {
			Set<String> activeIndices = new HashSet<>();
			for (Project project : boot.meshRoot().getProjectRoot().findAll()) {
				activeIndices.add(TagFamily.composeIndexName(project.getUuid()));
			}

			return indices.stream()
				.filter(i -> i.startsWith(getType()))
				.filter(i -> !activeIndices.contains(i))
				.collect(Collectors.toSet());
		});

	}

	@Override
	public Set<String> getIndicesForSearch(InternalActionContext ac) {
		return db.tx(() -> {
			HibProject project = ac.getProject();
			if (project != null) {
				return Collections.singleton(TagFamily.composeIndexName(project.getUuid()));
			} else {
				return getIndices().keySet();
			}
		});
	}

	@Override
	public Function<String, HibTagFamily> elementLoader() {
		return (uuid) -> boot.meshRoot().getTagFamilyRoot().findByUuid(uuid);
	}

	@Override
	public Stream<? extends HibTagFamily> loadAllElements(Tx tx) {
		return tx.data().tagFamilyDao().findAllGlobal().stream();
	}

}
