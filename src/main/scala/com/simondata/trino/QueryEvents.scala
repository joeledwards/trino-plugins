package com.simondata.trino

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters._
import com.simondata.util.{Config, Time, Types, XRay}
import io.trino.spi.ErrorCode
import io.trino.spi.eventlistener.{EventListener, QueryCompletedEvent, QueryContext, QueryCreatedEvent, SplitCompletedEvent}
import io.trino.spi.resourcegroups.QueryType

import scala.util.{Failure, Success, Try}

sealed trait QueryStage {
  def info: QueryInfo
}
case class QueryStart(info: QueryInfo) extends QueryStage
case class QuerySplit(info: QueryInfo, stage: String, task: String) extends QueryStage
case class QueryEnd(info: QueryInfo) extends QueryStage
object QueryStage {
  def from(event: QueryCreatedEvent): QueryStage = QueryStart(QueryInfo.from(event))
  def from(event: SplitCompletedEvent): QueryStage = QuerySplit(QueryInfo.from(event), event.getStageId, event.getTaskId)
  def from(event: QueryCompletedEvent): QueryStage = QueryEnd(QueryInfo.from(event))
}

case class FailureInfo(
  code: String,
  message: Option[String],
  category: Option[String],
)

case class TimeInfo(
   created: Instant,
   started: Option[Instant] = None,
   ended: Option[Instant] = None,
) {
  def createdIso: String = Time.toIso(created)
  def startedIso: String = started.map(Time.toIso(_)).getOrElse("")
  def endedIso: String = ended.map(Time.toIso(_)).getOrElse("")
  def waitDuration: Duration = started.map(Duration.between(created, _)).getOrElse(Duration.ZERO)
  def runDuration: Duration = Types.zip(started, ended).map(d => Duration.between(d._1, d._2)).getOrElse(Duration.ZERO)
  def totalDuration: Duration = ended.map(Duration.between(created, _)).getOrElse(Duration.ZERO)
}

case class QueryInfo(
  id: String,
  state: String,
  time: TimeInfo,
  resource: Option[Resource] = None,
  user: Option[String] = None,
  tags: List[String] = Nil,
  failure: Option[FailureInfo] = None,
  queryType: Option[String] = None
) {
  def authId: AuthId = user.map(AuthIdUser(_)).getOrElse(AuthIdUnknown)
  def failed: Boolean = failure.isDefined
}

object QueryInfo {
  def determineQueryType(context: QueryContext): Option[String] = {
    Types.toOption(context.getQueryType) map { _.name }
  }

  def deriveResource(context: QueryContext): Resource = (
    Types.toOption(context.getCatalog),
    Types.toOption(context.getSchema)
  ) match {
    case (Some(catalog), Some(schema)) => Schema(schema, Catalog(catalog))
    case (Some(catalog), None) => Catalog(catalog)
    case _ => UnknownResource
  }

  def from(event: QueryCreatedEvent): QueryInfo = {
    val created = event.getCreateTime
    val context = event.getContext
    val meta = event.getMetadata
    val tags = context.getClientTags.asScala.toList

    val user = context.getUser
    val resource = deriveResource(context)

    val id = meta.getQueryId
    val state = meta.getQueryState
    val queryType = determineQueryType(context)

    QueryInfo(
      id,
      state,
      time = TimeInfo(created),
      resource = Some(resource),
      user = Some(user),
      tags = tags,
      queryType = queryType
    )
  }

  def from(event: SplitCompletedEvent): QueryInfo = {
    val id = event.getQueryId
    val created = event.getCreateTime
    val started = Types.toOption(event.getStartTime)
    val ended = Types.toOption(event.getEndTime)
    val failure = Types.toOption(event.getFailureInfo) map { f =>
      FailureInfo(
        code = f.getFailureType,
        message = Some(f.getFailureMessage),
        category = None
      )
    }

    QueryInfo(
      id,
      state = "RUNNING",
      time = TimeInfo(created, started, ended),
      failure = failure
    )
  }

  def from(event: QueryCompletedEvent): QueryInfo = {
    val user = event.getContext.getUser
    val meta = event.getMetadata
    val context = event.getContext
    val id = meta.getQueryId
    val state = meta.getQueryState
    val created = event.getCreateTime
    val started = Some(event.getExecutionStartTime)
    val ended = Some(event.getEndTime)
    val resource = deriveResource(context)
    val queryType = determineQueryType(context)
    val tags = context.getClientTags.asScala.toList
    val failure = Types.toOption(event.getFailureInfo) map { f =>
      FailureInfo(
        code = f.getErrorCode.getName,
        message = Types.toOption(f.getFailureMessage),
        category = Some(f.getErrorCode.getType.name)
      )
    }

    QueryInfo(
      id,
      state,
      time = TimeInfo(created, started, ended),
      resource = Some(resource),
      user = Some(user),
      tags = tags,
      failure = failure,
      queryType = queryType
    )
  }
}

class QueryEvents extends EventListener {
  private implicit val pc: PluginContext = EventsPlugin

  /**
   * A wrapper common to all event listener handlers which will catch and log errors,
   * but permit queries to continue.
   *
   * @param action the event handler logic which will close over the event on method declaration
   */
  private def wrappedEventHandler(action: => Unit) = Try {
    action
  } match {
    case Success(_) =>
    case Failure(e) => {
      Try {
        println(s"Error in an event listener handler ${XRay.getCallerName()}!")
        e.printStackTrace()

        Logger.log.error(s"Error in the event listener. The stack trace is in the Coordinator's logs")
      } match {
        case Success(_) =>
        case Failure(e) => {
          println("Another error occurred while handling an error in the event listener.")
          e.printStackTrace()
        }
      }
    }
  }

  override def queryCreated(event: QueryCreatedEvent): Unit = wrappedEventHandler {
    // Always log event receipt
    Logger.log.info(s"event-query-created => ${event.getMetadata.getQueryId}")

    val qs = QueryStage.from(event)
    implicit val log = Logger.log(qs.info.authId)
    logQueryInfo(qs, slackOverride = Config.slackQueryCreated)
  }

  override def splitCompleted(event: SplitCompletedEvent): Unit = wrappedEventHandler {
    // By default, we do not log split events
    Config.logSplitComplete match {
      case None =>
      case Some(false) =>
      case Some(true) => {
        Logger.log.info(s"event-split-completed => ${event.getQueryId}")
        val qs = QueryStage.from(event)
        implicit val log = Logger.log(qs.info.authId)
        logQueryInfo(qs, slackOverride = Config.slackSplitComplete)
      }
    }
  }

  override def queryCompleted(event: QueryCompletedEvent): Unit = wrappedEventHandler {
    // Always log event receipt
    Logger.log.info(s"event-query-completed => ${event.getMetadata.getQueryId}")

    val qs = QueryStage.from(event)
    implicit val log = Logger.log(qs.info.authId)
    logQueryInfo(qs)
  }

  private def queryPrefix(info: QueryInfo): String = {
    val id = info.id
    val state = info.state
    val typeInfo = info.queryType.map(qt => s"$qt ").getOrElse("")
    val tagInfo = info.tags match {
      case Nil => ""
      case tagList => s" [${tagList.mkString(", ")}]"
    }
    s"${typeInfo}Query `${id}` _${state}_$tagInfo"
  }

  private def logQueryInfo(
    queryStage: QueryStage,
    slackOverride: Option[Boolean] = None
  )(implicit log: Logger): Unit = Try {
    val (logLevel: LogLevel, logMessage: Option[String], slackOverride: Option[Boolean]) = queryStage match {
      case QueryStart(QueryInfo(_, _, time, Some(resource), Some(user), _, _, _)) => {
        val prefix = queryPrefix(queryStage.info)
        val logMessage: Option[String] = Config.logQueryCreated match {
          case Some(false) => None
          case None | Some(true) => Some(
            s"""${prefix}
            |submitted by `${user}` against schema `${resource}`
            |created at _*${time.createdIso}*_""".stripMargin
          )
        }

        (InfoLevel, logMessage, Config.slackQueryCreated)
      }
      case QuerySplit(QueryInfo(_, _, time, _, _, _, None, _), stage, task) => {
        val prefix = queryPrefix(queryStage.info)
        val elapsed = Time.human(time.runDuration)
        val logMessage: Option[String] = Some(
          s"""${prefix}
          |completed split ${stage}.${task} (lasted _*${elapsed}*_)""".stripMargin
        )

        (InfoLevel, logMessage, Config.slackSplitComplete)
      }
      case QuerySplit(QueryInfo(_, _, time, _, _, _, Some(FailureInfo(code, message, category)), _), stage, task) => {
        val prefix = queryPrefix(queryStage.info)
        val elapsed = Time.human(time.runDuration)
        val msg = message.getOrElse("")
        val cat = category.getOrElse("UNCATEGORIZED")
        val failureMessage = s"""${cat}:${code} => ${msg}"""
        val logMessage: Option[String] = Some(
          s"""${prefix}
          |failed split ${stage}.${task} (lasted _*${elapsed}*_)
          |--
          |${failureMessage}""".stripMargin
        )

        (InfoLevel, logMessage, Config.slackSplitComplete)
      }
      case QueryEnd(QueryInfo(_, _, time, Some(resource), Some(user), _, None, _)) => {
        val prefix = queryPrefix(queryStage.info)
        val elapsed = Time.human(time.totalDuration)
        val logMessage: Option[String] = Config.logQuerySuccess match {
          case Some(false) => None
          case None | Some(true) => Some(
            s"""${prefix}
            |submitted by `${user}` against schema `${resource}`
            |ended at _*${time.endedIso}*_ (lasted _*${elapsed}*_)
            |""".stripMargin
          )
        }

        (InfoLevel, logMessage, Config.slackQuerySuccess)
      }
      case QueryEnd(QueryInfo(_, _, time, Some(resource), Some(user), _, Some(FailureInfo(code, message, category)), _)) => {
        val prefix = queryPrefix(queryStage.info)
        val elapsed = Time.human(time.totalDuration)
        val msg = message.getOrElse("")
        val cat = category.getOrElse("UNCATEGORIZED")
        val logMessage: Option[String] = Config.logQueryFailure match {
          case Some(false) => None
          case None | Some(true) => Some(
            s"""${prefix}
             |submitted by `${user}` against schema `${resource}`
             |ended at _*${time.endedIso}*_ (lasted _*${elapsed}*_)
             |--
             |*${cat}:${code}*
             |${msg}""".stripMargin
          )
        }

        (WarnLevel, logMessage, Config.slackQueryFailure)
      }
      case queryStage => (WarnLevel, s"""Unrecognized query stage: ${queryStage}""", None)
    }

    // Log query info if a message was generated
    logMessage foreach { log.log(logLevel, _, slackOverride) }
  } match {
    case Success(_) =>
    case Failure(error) => {
      error.printStackTrace()
      log.error(s"Error logging info for query ${queryStage} : ${error}")
    }
  }
}

object QueryEvents {
  val instance: EventListener = new QueryEvents
}
