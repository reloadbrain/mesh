package com.gentics.mesh.core.data.root;

import java.util.List;

import com.gentics.mesh.context.InternalActionContext;
import com.gentics.mesh.core.data.Branch;
import com.gentics.mesh.core.data.MeshAuthUser;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.Tag;
import com.gentics.mesh.core.data.TagFamily;
import com.gentics.mesh.core.data.User;
import com.gentics.mesh.core.data.node.Node;
import com.gentics.mesh.core.data.page.TransformablePage;
import com.gentics.mesh.core.rest.common.ContainerType;
import com.gentics.mesh.core.rest.tag.TagResponse;
import com.gentics.mesh.event.EventQueueBatch;
import com.gentics.mesh.madl.traversal.TraversalResult;
import com.gentics.mesh.parameter.PagingParameters;

/**
 * Aggregation node for tags.
 */
public interface TagRoot extends RootVertex<Tag>, TransformableElementRoot<Tag, TagResponse> {

	public static final String TYPE = "tags";

	/**
	 * Add the given tag to the aggregation vertex.
	 * 
	 * @param tag
	 *            Tag to be added
	 */
	void addTag(Tag tag);

	/**
	 * Remove the tag from the aggregation vertex.
	 * 
	 * @param tag
	 *            Tag to be removed
	 */
	void removeTag(Tag tag);

	/**
	 * Create a new tag with the given parameters and assign it to this tag root. Note that the created tag will also be assigned to the global and project tag
	 * root vertex.
	 * 
	 * @param name
	 *            Name of the tag
	 * @param project
	 *            Project in which the tag was created
	 * @param tagFamily
	 *            Tag family to which the tag should be assigned.
	 * @param creator
	 *            Creator of the tag
	 * @return
	 */
	Tag create(String name, Project project, TagFamily tagFamily, User creator);


	boolean update(Tag tag, InternalActionContext ac, EventQueueBatch batch);

	/**
	 * Return a page of nodes that are visible to the user and which are tagged by this tag. Use the paging and language information provided.
	 *
	 * @param tag
	 * @param requestUser
	 * @param branch
	 * @param languageTags
	 * @param type
	 * @param pagingInfo
	 * @return
	 */
	TransformablePage<? extends Node> findTaggedNodes(Tag tag, MeshAuthUser requestUser, Branch branch, List<String> languageTags, ContainerType type,
		PagingParameters pagingInfo);

	TraversalResult<? extends Node> findTaggedNodes(Tag tag, InternalActionContext ac);

	/**
	 * Return a traversal result of nodes that were tagged by this tag in the given branch
	 *
	 * @param branch
	 *            branch
	 *
	 * @return Result
	 */
	TraversalResult<? extends Node> getNodes(Tag tag, Branch branch);
}
