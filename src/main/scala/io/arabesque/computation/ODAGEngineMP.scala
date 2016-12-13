package io.arabesque.computation

import java.io._
import java.util.concurrent.{ExecutorService, Executors}

import io.arabesque.aggregation.{AggregationStorage, AggregationStorageFactory}
import io.arabesque.conf.{Configuration, SparkConfiguration}
import io.arabesque.embedding._
import io.arabesque.odag.domain.DomainEntry
import io.arabesque.odag._
import io.arabesque.odag.BasicODAGStash.EfficientReader
import io.arabesque.pattern.Pattern
import io.arabesque.utils.SerializableConfiguration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{LongWritable, NullWritable, SequenceFile, Writable}
import org.apache.hadoop.io.SequenceFile.{Writer => SeqWriter}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.Accumulator
import org.apache.spark.broadcast.Broadcast

import scala.collection.JavaConversions._
import scala.collection.mutable.{ListBuffer, Map}
import scala.reflect.ClassTag

/**
 * Underlying engine that runs Arabesque workers in Spark.
 * Each instance of this engine corresponds to a partition in Spark computation
 * model. Instances' lifetimes refer also to one superstep of computation due
 * RDD's immutability.
 */
case class ODAGEngineMP [E <: Embedding] (
    partitionId: Int,
    superstep: Int,
    hadoopConf: SerializableConfiguration,
    accums: Map[String,Accumulator[_]],
    // TODO do not broadcast if user's code does not requires it
    previousAggregationsBc: Broadcast[_])
  extends ODAGEngine[E,MultiPatternODAG,MultiPatternODAGStash,ODAGEngineMP[E]] {

  // stashes
  nextEmbeddingStash = new MultiPatternODAGStash (configuration.getMaxOdags)

  /**
   * Returns a new execution engine from this with the aggregations/computation
   * variables updated (immutability)
    configuration.getInteger (CONF_COMM_STRATEGY_ODAGMP_MAX, CONF_)
   *
   * @param aggregationsBc broadcast variable with aggregations
   * @return the new execution engine, ready for flushing
   */
  def withNewAggregations(aggregationsBc: Broadcast[_])
      : ODAGEngineMP[E] = {
    
    // we first get a copy of the this execution engine, with previous
    // aggregations updated
    val execEngine = this.copy [E] (
      previousAggregationsBc = aggregationsBc,
      accums = accums)

    // set next stash with odags
    execEngine.nextEmbeddingStash = nextEmbeddingStash

    execEngine
  }

  override def flush: Iterator[(_,_)] = configuration.getOdagFlushMethod match {
    case SparkConfiguration.FLUSH_BY_PATTERN => flushByPattern
    //case SparkConfiguration.FLUSH_BY_ENTRIES => flushByEntries
    //case SparkConfiguration.FLUSH_BY_PARTS =>   flushByParts
  }

  /**
   * Naively flushes outbound odags.
   * We assume that this execEngine is ready to
   * do *aggregationFilter*, i.e., this execution engine was generated by
   * [[withNewAggregations]].
   *
   * @return iterator of pairs of (pattern, odag)
   */
  private def flushByPattern: Iterator[(Int,MultiPatternODAG)]  = {
    // consume content in *nextEmbeddingStash*
    nextEmbeddingStash.aggregationFilter (computation)
    for ((odag,idx) <- nextEmbeddingStash.odags.iterator.zipWithIndex
         if odag != null)
      yield (idx, odag)
  }
}
