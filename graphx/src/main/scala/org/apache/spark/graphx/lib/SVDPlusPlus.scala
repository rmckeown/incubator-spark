/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.graphx.lib

import scala.util.Random
import org.apache.commons.math.linear._
import org.apache.spark.rdd._
import org.apache.spark.graphx._

/** Implementation of SVD++ algorithm. */
object SVDPlusPlus {

  /** Configuration parameters for SVDPlusPlus. */
  class Conf(
      var rank: Int,
      var maxIters: Int,
      var minVal: Double,
      var maxVal: Double,
      var gamma1: Double,
      var gamma2: Double,
      var gamma6: Double,
      var gamma7: Double)
    extends Serializable

  /**
   * Implement SVD++ based on "Factorization Meets the Neighborhood:
   * a Multifaceted Collaborative Filtering Model",
   * available at [[http://public.research.att.com/~volinsky/netflix/kdd08koren.pdf]].
   *
   * The prediction rule is rui = u + bu + bi + qi*(pu + |N(u)|^(-0.5)*sum(y)),
   * see the details on page 6.
   *
   * @param edges edges for constructing the graph
   *
   * @param conf SVDPlusPlus parameters
   *
   * @return a graph with vertex attributes containing the trained model
   */
  def run(edges: RDD[Edge[Double]], conf: Conf)
    : (Graph[(RealVector, RealVector, Double, Double), Double], Double) =
  {
    // Generate default vertex attribute
    def defaultF(rank: Int): (RealVector, RealVector, Double, Double) = {
      val v1 = new ArrayRealVector(rank)
      val v2 = new ArrayRealVector(rank)
      for (i <- 0 until rank) {
        v1.setEntry(i, Random.nextDouble())
        v2.setEntry(i, Random.nextDouble())
      }
      (v1, v2, 0.0, 0.0)
    }

    // calculate global rating mean
    edges.cache()
    val (rs, rc) = edges.map(e => (e.attr, 1L)).reduce((a, b) => (a._1 + b._1, a._2 + b._2))
    val u = rs / rc

    // construct graph
    var g = Graph.fromEdges(edges, defaultF(conf.rank)).cache()

    // Calculate initial bias and norm
    val t0 = g.mapReduceTriplets(
      et => Iterator((et.srcId, (1L, et.attr)), (et.dstId, (1L, et.attr))),
      (g1: (Long, Double), g2: (Long, Double)) => (g1._1 + g2._1, g1._2 + g2._2))

    g = g.outerJoinVertices(t0) {
      (vid: VertexID, vd: (RealVector, RealVector, Double, Double), msg: Option[(Long, Double)]) =>
        (vd._1, vd._2, msg.get._2 / msg.get._1, 1.0 / scala.math.sqrt(msg.get._1))
    }

    def mapTrainF(conf: Conf, u: Double)
        (et: EdgeTriplet[(RealVector, RealVector, Double, Double), Double])
      : Iterator[(VertexID, (RealVector, RealVector, Double))] = {
      val (usr, itm) = (et.srcAttr, et.dstAttr)
      val (p, q) = (usr._1, itm._1)
      var pred = u + usr._3 + itm._3 + q.dotProduct(usr._2)
      pred = math.max(pred, conf.minVal)
      pred = math.min(pred, conf.maxVal)
      val err = et.attr - pred
      val updateP = q.mapMultiply(err)
        .subtract(p.mapMultiply(conf.gamma7))
        .mapMultiply(conf.gamma2)
      val updateQ = usr._2.mapMultiply(err)
        .subtract(q.mapMultiply(conf.gamma7))
        .mapMultiply(conf.gamma2)
      val updateY = q.mapMultiply(err * usr._4)
        .subtract(itm._2.mapMultiply(conf.gamma7))
        .mapMultiply(conf.gamma2)
      Iterator((et.srcId, (updateP, updateY, (err - conf.gamma6 * usr._3) * conf.gamma1)),
        (et.dstId, (updateQ, updateY, (err - conf.gamma6 * itm._3) * conf.gamma1)))
    }

    for (i <- 0 until conf.maxIters) {
      // Phase 1, calculate pu + |N(u)|^(-0.5)*sum(y) for user nodes
      g.cache()
      val t1 = g.mapReduceTriplets(
        et => Iterator((et.srcId, et.dstAttr._2)),
        (g1: RealVector, g2: RealVector) => g1.add(g2))
      g = g.outerJoinVertices(t1) {
        (vid: VertexID, vd: (RealVector, RealVector, Double, Double), msg: Option[RealVector]) =>
          if (msg.isDefined) (vd._1, vd._1.add(msg.get.mapMultiply(vd._4)), vd._3, vd._4) else vd
      }

      // Phase 2, update p for user nodes and q, y for item nodes
      g.cache()
      val t2 = g.mapReduceTriplets(
        mapTrainF(conf, u),
        (g1: (RealVector, RealVector, Double), g2: (RealVector, RealVector, Double)) =>
          (g1._1.add(g2._1), g1._2.add(g2._2), g1._3 + g2._3))
      g = g.outerJoinVertices(t2) {
        (vid: VertexID,
         vd: (RealVector, RealVector, Double, Double),
         msg: Option[(RealVector, RealVector, Double)]) =>
          (vd._1.add(msg.get._1), vd._2.add(msg.get._2), vd._3 + msg.get._3, vd._4)
      }
    }

    // calculate error on training set
    def mapTestF(conf: Conf, u: Double)
        (et: EdgeTriplet[(RealVector, RealVector, Double, Double), Double])
      : Iterator[(VertexID, Double)] =
    {
      val (usr, itm) = (et.srcAttr, et.dstAttr)
      val (p, q) = (usr._1, itm._1)
      var pred = u + usr._3 + itm._3 + q.dotProduct(usr._2)
      pred = math.max(pred, conf.minVal)
      pred = math.min(pred, conf.maxVal)
      val err = (et.attr - pred) * (et.attr - pred)
      Iterator((et.dstId, err))
    }
    g.cache()
    val t3 = g.mapReduceTriplets(mapTestF(conf, u), (g1: Double, g2: Double) => g1 + g2)
    g = g.outerJoinVertices(t3) {
      (vid: VertexID, vd: (RealVector, RealVector, Double, Double), msg: Option[Double]) =>
        if (msg.isDefined) (vd._1, vd._2, vd._3, msg.get) else vd
    }

    (g, u)
  }
}
