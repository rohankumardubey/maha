// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.service.utils

import java.util.concurrent.atomic.AtomicBoolean
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString
import com.yahoo.maha.core.query._
import com.yahoo.maha.core.request.{AsyncRequest, ReportingRequest, SyncRequest}
import com.yahoo.maha.core.{DimensionCandidate, RequestModel, SortByColumnInfo}
import com.yahoo.maha.log.MahaRequestLogWriter
import com.yahoo.maha.proto.MahaRequestLog.MahaRequestProto
import com.yahoo.maha.proto.MahaRequestLog.MahaRequestProto.FactCost
import com.yahoo.maha.service.MahaRequestContext
import com.yahoo.maha.service.curators.Curator
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang.StringUtils
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Created by pranavbhole on 11/08/17.
 */

object MahaConstants {
  val REQUEST_ID: String = "requestId"
  val USER_ID: String = "user"
  val IS_INTERNAL: String = "isInternal"
}

trait BaseMahaRequestLogBuilder {
  protected[this] lazy val protoBuilder: MahaRequestProto.Builder = MahaRequestProto.newBuilder()

  def logQueryPipeline(queryPipeline: QueryPipeline)

  def logQueryStats(queryAttributes: QueryAttributes)

  def logFailed(errorMessage: String, httpStatusOption: Option[Int] = None)

  def logSuccess()

  def dryRun(): BaseMahaRequestLogBuilder

  def setJobId(jobId: Long)

  def setJobIdString(jobIdStr: String)
}

trait CuratorMahaRequestLogBuilder extends BaseMahaRequestLogBuilder {
  def copy(curator: Curator): CuratorMahaRequestLogBuilder
}

trait MahaRequestLogBuilder extends BaseMahaRequestLogBuilder {
  def curatorLogBuilder(curator: Curator): CuratorMahaRequestLogBuilder
}

case class CuratorMahaRequestLogHelper(delegate: MahaRequestLogBuilder) extends CuratorMahaRequestLogBuilder {
  def copy(curator: Curator): CuratorMahaRequestLogBuilder = {
    delegate.curatorLogBuilder(curator)
  }
  override def logQueryPipeline(queryPipeline: QueryPipeline): Unit =
    delegate.logQueryPipeline(queryPipeline)

  override def logQueryStats(queryAttributes: QueryAttributes): Unit =
    delegate.logQueryStats(queryAttributes)

  override def logFailed(errorMessage: String, httpStatusOption: Option[Int] = None): Unit =
    delegate.logFailed(errorMessage, httpStatusOption)

  override def logSuccess(): Unit = delegate.logSuccess()

  override def dryRun(): BaseMahaRequestLogBuilder = {
    delegate.dryRun()
  }

  override def setJobId(jobId: Long): Unit =  {
    delegate.setJobId(jobId)
  }

  override def setJobIdString(jobIdStr: String): Unit =  {
    delegate.setJobIdString(jobIdStr)
  }
}

object MahaRequestLogHelper {
  val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[MahaRequestLogHelper])
  val hostname:Option[String] = try {
    import java.net.InetAddress
    val addr = InetAddress.getLocalHost
    Some(addr.getHostName)
  } catch {
    case e:Exception=>
      logger.error(s"Failed to get hostname ${e.getMessage}", e)
      None
  }
}
case class MahaRequestLogHelper(mahaRequestContext: MahaRequestContext, mahaRequestLogWriter: MahaRequestLogWriter, curator: String = "none") extends MahaRequestLogBuilder {

  private[this] val complete: AtomicBoolean = new AtomicBoolean(false)
  import MahaRequestLogHelper._

  init(protoBuilder)

  protected def init(protoBuilder: MahaRequestProto.Builder) : Unit = {
    protoBuilder.setMahaServiceRegistryName(mahaRequestContext.registryName)
    protoBuilder.setRequestStartTime(mahaRequestContext.requestStartTime)

    if(hostname.isDefined) {
      protoBuilder.setMahaServiceHostname(hostname.get)
    }

    val requestId = mahaRequestContext.requestId
    val userId = mahaRequestContext.userId

    if (requestId != null && StringUtils.isNotBlank(requestId.toString)) {
      protoBuilder.setRequestId(requestId.toString)
    }
    if (userId != null && StringUtils.isNotBlank(userId.toString)) {
      protoBuilder.setUserId(userId.toString)
    }
    if(mahaRequestContext.reportingRequest != null) {
      protoBuilder.setRequestType(getRequestType(mahaRequestContext.reportingRequest))
      protoBuilder.setCube(mahaRequestContext.reportingRequest.cube)
      protoBuilder.setSchema(mahaRequestContext.reportingRequest.schema.toString)
      protoBuilder.setNumDays(mahaRequestContext.reportingRequest.numDays)
    }
    if(mahaRequestContext.rawJson != null) {
      protoBuilder.setJson(ByteString.copyFrom(mahaRequestContext.rawJson))
      if(mahaRequestContext.requestHashOption.isDefined) {
        protoBuilder.setRequestHash(mahaRequestContext.requestHashOption.get)
      }
    }
    if(curator != null) {
      protoBuilder.setCurator(curator)
    }
  }

  protected def getRequestType(reportingRequest: ReportingRequest) : MahaRequestProto.RequestType = {
    reportingRequest.requestType match {
      case SyncRequest => MahaRequestProto.RequestType.SYNC
      case AsyncRequest => MahaRequestProto.RequestType.ASYNC
    }
  }

  def setDryRun(): Unit = {
    protoBuilder.setIsDryRun(true)
  }

  def setAsyncQueueParams(): Unit = {

  }

  def getRevision(requestModel: RequestModel): Option[Int] = {
    try {
      Some(requestModel.bestCandidates.get.publicFact.revision)
    } catch {
      case e:Exception =>
        logger.warn(s"Something went wrong in extracting cube revision for logging purpose $e")
        None
    }
  }

  override def logQueryPipeline(queryPipeline: QueryPipeline): Unit = {
    val drivingQuery = queryPipeline.queryChain.drivingQuery
    val model = queryPipeline.queryChain.drivingQuery.queryContext.requestModel
    val factBestCandidateOption = queryPipeline.factBestCandidate
    val engine = queryPipeline.queryChain.drivingQuery.engine
    val engineEnum = MahaRequestProto.Engine.valueOf(engine.toString)
    protoBuilder.setDrivingQueryEngine(drivingQuery.engine.toString)
    protoBuilder.setDrivingTable(drivingQuery.tableName)
    protoBuilder.setQueryChainType(queryPipeline.queryChain.getClass.getSimpleName)
    protoBuilder.setHasFactFilters(model.hasFactFilters)
    protoBuilder.setHasNonFKFactFilters(model.hasNonFKFactFilters)
    protoBuilder.setHasDimFilters(model.hasDimFilters)
    protoBuilder.setHasNonFKDimFilters(model.hasNonFKDimFilters)
    protoBuilder.setHasFactSortBy(model.hasFactSortBy)
    protoBuilder.setHasDimSortBy(model.hasDimSortBy)
    protoBuilder.setIsFactDriven(model.isFactDriven)
    protoBuilder.setForceDimDriven(model.forceDimDriven)
    protoBuilder.setForceFactDriven(model.forceFactDriven)
    protoBuilder.setHasNonDrivingDimSortOrFilter(model.hasNonDrivingDimSortOrFilter)
    protoBuilder.setHasDimAndFactOperations(model.hasDimAndFactOperations)

    if(factBestCandidateOption.isDefined) {
      protoBuilder.addFactCostBuilder().build()
      protoBuilder.setFactCost(0,MahaRequestProto.FactCost.newBuilder().setEngine(engineEnum).setCost(factBestCandidateOption.get.factCost))
    }
    if (model.queryGrain.isDefined) {
      protoBuilder.setTimeGrain(model.queryGrain.toString)
    }
    if (model.dimCardinalityEstimate.isDefined) {
      protoBuilder.setDimCardinalityEstimate(model.dimCardinalityEstimate.get.asInstanceOf[Long])
    }
    val cubeRevision = getRevision(model)

    if (cubeRevision.isDefined) {
      protoBuilder.setCubeRevision(cubeRevision.get)
    }
    protoBuilder.setIsDebug(model.isDebugEnabled)
    protoBuilder.setIsTest(model.reportingRequest.isTestEnabled)
    if(model.reportingRequest.isTestEnabled) {
      val testName = model.reportingRequest.getTestName
      if(testName.isDefined) {
        protoBuilder.setTestName(testName.get)
      }
    }
    if(model.reportingRequest.hasLabels) {
      model.reportingRequest.getLabels.foreach(protoBuilder.addLabels)
    }

    if(factBestCandidateOption.isDefined) {
      val factBestCandidate = factBestCandidateOption.get
      protoBuilder.setIsIndexOptimized(factBestCandidate.isIndexOptimized)
      protoBuilder.setIsGrainOptimized(factBestCandidate.isGrainOptimized)
      protoBuilder.setIsScanOptimized(factBestCandidate.factRows.isScanOptimized)
      if(factBestCandidate.isGrainOptimized) {
        protoBuilder.setGrainRows(factBestCandidate.factRows.rows)
      } else {
        protoBuilder.setGrainRows(0)
      }
      if(factBestCandidate.factRows.isScanOptimized) {
        protoBuilder.setScanRows(factBestCandidate.factRows.scanRows)
      } else {
        protoBuilder.setScanRows(0)
      }
    }

    val columnInfoIterator: Iterator[SortByColumnInfo] = model.requestSortByCols.iterator
    while (columnInfoIterator != null && columnInfoIterator.hasNext) {
      val sortByColumnInfo: SortByColumnInfo = columnInfoIterator.next
      val sortByColumnInfoProtoBuilder: MahaRequestProto.SortByColumnInfo.Builder = MahaRequestProto.SortByColumnInfo.newBuilder.setAlias(sortByColumnInfo.alias)
      if (MahaRequestProto.Order.ASC.toString.equalsIgnoreCase(sortByColumnInfo.order.toString)) sortByColumnInfoProtoBuilder.setOrder(MahaRequestProto.Order.ASC)
      else sortByColumnInfoProtoBuilder.setOrder(MahaRequestProto.Order.DESC)
      protoBuilder.addRequestSortByCols(sortByColumnInfoProtoBuilder.build)
    }
    val dimensionsCandidates: Iterator[DimensionCandidate] = model.dimensionsCandidates.iterator
    while (dimensionsCandidates != null && dimensionsCandidates.hasNext) {
      val dimensionCandidate: DimensionCandidate = dimensionsCandidates.next
      protoBuilder.addDimensionsCandidates(dimensionCandidate.dim.name)
    }

  }

  override def logQueryStats(queryAttributes: QueryAttributes): Unit = {

    val queryAttributeOption: Option[QueryAttribute] = queryAttributes.getAttributeOption(QueryAttributes.QueryStats)
    if (queryAttributeOption.isDefined) {
      val queryStatsAttribute: QueryStatsAttribute = queryAttributeOption.get.asInstanceOf[QueryStatsAttribute]
      val engineQueryStatsIterator: Iterator[EngineQueryStat] = queryStatsAttribute.stats.getStats.iterator
      if (engineQueryStatsIterator != null && engineQueryStatsIterator.hasNext) {
        val drivingEngineQueryStat: EngineQueryStat = engineQueryStatsIterator.next
        protoBuilder.setDrivingQueryEngineLatency(drivingEngineQueryStat.endTime - drivingEngineQueryStat.startTime)
        if (engineQueryStatsIterator.hasNext) {
          val firsEngineQueryStat: EngineQueryStat = engineQueryStatsIterator.next
          protoBuilder.setFirstSubsequentQueryEngine(firsEngineQueryStat.engine.toString)
          protoBuilder.setFirstSubsequentQueryTable(firsEngineQueryStat.tableName)
          protoBuilder.setFirstSubsequentQueryEngineLatency(firsEngineQueryStat.endTime - firsEngineQueryStat.startTime)
        }
        if (engineQueryStatsIterator.hasNext) {
          val reRunEngineQueryStats: EngineQueryStat = engineQueryStatsIterator.next
          protoBuilder.setReRunEngineQueryTable(reRunEngineQueryStats.tableName)
          protoBuilder.setReRunEngineQueryLatency(reRunEngineQueryStats.endTime - reRunEngineQueryStats.startTime)
          if (MahaRequestProto.Engine.Druid.toString.equalsIgnoreCase(reRunEngineQueryStats.engine.toString)) protoBuilder.setReRunEngine(MahaRequestProto.Engine.Druid)
          else if (MahaRequestProto.Engine.Oracle.toString.equalsIgnoreCase(reRunEngineQueryStats.engine.toString)) protoBuilder.setReRunEngine(MahaRequestProto.Engine.Oracle)
          else if (MahaRequestProto.Engine.Hive.toString.equalsIgnoreCase(reRunEngineQueryStats.engine.toString)) protoBuilder.setReRunEngine(MahaRequestProto.Engine.Hive)
        }
      }
    }
  }

  override def logFailed(errorMessage: String, httpStatusOption: Option[Int] = None): Unit = {
    if(complete.compareAndSet(false, true)) {
      if(httpStatusOption.isDefined) {
        protoBuilder.setStatus(httpStatusOption.get)
      } else protoBuilder.setStatus(500)
      protoBuilder.setErrorMessage(errorMessage)
      protoBuilder.setRequestEndTime(System.currentTimeMillis())
      writeLog()
    } else {
      logger.warn("logFailed called more than once!")
    }
  }

  override def logSuccess(): Unit =  {
    if(complete.compareAndSet(false, true)) {
      protoBuilder.setStatus(200)
      protoBuilder.setRequestEndTime(System.currentTimeMillis())
      writeLog()
    } else {
      logger.warn("logSuccess called more than once!")
    }
  }

  private[this] def writeLog(): Unit = {
    try {
      mahaRequestLogWriter.write(protoBuilder.build())
    } catch {
      case e : Throwable=>
        logger.warn(s"Failed to log the event to kafka ${e.getMessage} $e")
    }
  }

  @VisibleForTesting
  def getbuilder(): MahaRequestProto.Builder = {
    protoBuilder
  }

  def curatorLogBuilder(curator: Curator): CuratorMahaRequestLogBuilder = {
    CuratorMahaRequestLogHelper(
      new MahaRequestLogHelper(mahaRequestContext, mahaRequestLogWriter, curator.name)
    )
  }

  override def dryRun(): BaseMahaRequestLogBuilder = {
    new MahaRequestLogHelper(mahaRequestContext, mahaRequestLogWriter, s"$curator-dryrun")
  }

  override def setJobId(jobId: Long): Unit =  {
    protoBuilder.setJobId(jobId)
  }

  override def setJobIdString(jobIdStr: String): Unit = {
    protoBuilder.setJobIdString(jobIdStr)
  }
}
