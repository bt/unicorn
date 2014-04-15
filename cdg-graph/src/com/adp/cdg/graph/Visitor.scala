package com.adp.cdg.graph

abstract class Visitor[V, E] {
  /**
   * Visit a node during graph traversal. The edge is the incoming
   * arc (null for starting node). The hops is the number of hops
   * from the starting node to this node.
   */
  def visit(node: V, edge: Edge[V, E], hops: Int): Unit
  /**
   * Returns an iterator of edges for this node. The input parameters
   * hops (# of hops from starting node) may be used for early termination.
   */
  def edges(node: V, hops: Int): Iterator[Edge[V, E]]
}