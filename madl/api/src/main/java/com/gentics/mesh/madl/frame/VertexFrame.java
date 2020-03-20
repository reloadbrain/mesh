package com.gentics.mesh.madl.frame;

import java.util.function.Function;
import java.util.stream.Stream;

import com.gentics.madl.traversal.RawTraversalResult;
import com.gentics.mesh.madl.tp3.mock.GraphTraversal;
import com.gentics.mesh.madl.traversal.TraversalResult;
import com.syncleus.ferma.traversals.VertexTraversal;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;

public interface VertexFrame extends ElementFrame, com.syncleus.ferma.VertexFrame {

	/**
	 * Add a unique <b>out-bound</b> link to the given vertex for the given set of labels. Note that this method will effectively ensure that only one
	 * <b>out-bound</b> link exists between the two vertices for each label.
	 * 
	 * @param vertex
	 *            Target vertex
	 * @param labels
	 *            Labels to handle
	 */
	void setUniqueLinkOutTo(VertexFrame vertex, String... labels);

	void setUniqueLinkInTo(VertexFrame vertex, String... labels);

	void setSingleLinkOutTo(VertexFrame vertex, String... labels);

	void setSingleLinkInTo(VertexFrame vertex, String... labels);

	/**
	 * @deprecated Use {@link #out(String, Class)} instead.
	 */
	@Deprecated
	@Override
	VertexTraversal<?, ?, ?> out(String... labels);

	<T extends ElementFrame> TraversalResult<? extends T> out(String label, Class<T> clazz);

	<T extends EdgeFrame> TraversalResult<? extends T> outE(String label, Class<T> clazz);

	Stream<Vertex> streamOut(String label);

	Stream<Vertex> streamIn(String label);

	Stream<Edge> streamOutE(String label);

	<T extends ElementFrame> TraversalResult<? extends T> in(String label, Class<T> clazz);

	<T extends EdgeFrame> TraversalResult<? extends T> inE(String label, Class<T> clazz);

	Stream<Edge> streamInE(String label);

	<T extends RawTraversalResult<?>> T traverse(final Function<GraphTraversal<Vertex, Vertex>, GraphTraversal<?, ?>> traverser);

	/**
	 * Remove all out edges to the supplied vertex with the supplied labels.
	 *
	 * @param vertex
	 *            The vertex to removed the edges to.
	 * @param labels
	 *            The labels of the edges.
	 */
	void unlinkOut(VertexFrame vertex, String... labels);

	/**
	 * Remove all in edges to the supplied vertex with the supplied labels.
	 *
	 * @param vertex
	 *            The vertex to removed the edges from.
	 * @param labels
	 *            The labels of the edges.
	 */
	void unlinkIn(VertexFrame vertex, String... labels);

	/**
	 * Remove all out edges with the labels and then add a single edge to the supplied vertex.
	 *
	 * @param vertex
	 *            the vertex to link to.
	 * @param labels
	 *            The labels of the edges.
	 */
	void setLinkOut(VertexFrame vertex, String... labels);

	/**
	 * Create edges from the framed vertex to the supplied vertex with the supplied labels
	 *
	 * @param vertex
	 *            The vertex to link to.
	 * @param labels
	 *            The labels for the edges.
	 */
	void linkOut(VertexFrame vertex, String... labels);

	/**
	 * Create edges from the supplied vertex to the framed vertex with the supplied labels
	 *
	 * @param vertex
	 *            The vertex to link from.
	 * @param labels
	 *            The labels for the edges.
	 */
	void linkIn(VertexFrame vertex, String... labels);

}
