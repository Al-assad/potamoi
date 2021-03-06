package com.github.potamois.potamoi.gateway.flink.interact

import akka.Done
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, DispatcherSelector, PostStop}
import com.github.potamois.potamoi.commons.ClassloaderWrapper.tryRunWithExtraDeps
import com.github.potamois.potamoi.commons.EitherAlias.{fail, success}
import com.github.potamois.potamoi.commons.{CancellableFuture, FiniteQueue, RichMutableMap, RichString, RichTry, Using, curTs}
import com.github.potamois.potamoi.gateway.flink.interact.FsiSessManager.SessionId
import com.github.potamois.potamoi.gateway.flink.interact.OpType.OpType
import com.github.potamois.potamoi.gateway.flink.parser.FlinkSqlParser
import com.github.potamois.potamoi.gateway.flink.{Error, FlinkApiCovertTool, PageReq, PageRsp, interact}
import org.apache.flink.configuration.{Configuration, PipelineOptions}
import org.apache.flink.core.execution.JobClient
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.TableResult
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment
import org.apache.flink.table.api.internal.TableEnvironmentInternal
import org.apache.flink.table.delegation.Parser
import org.apache.flink.table.operations.{ModifyOperation, QueryOperation}
import org.apache.flink.table.planner.operations.PlannerQueryOperation

import java.io.File
import java.net.URL
import java.util.concurrent.CancellationException
import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.collection.{AbstractSeq, TraversableLike, mutable}
import scala.compat.java8.OptionConverters.RichOptionalGeneric
import scala.concurrent.ExecutionContextExecutor
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Failure, Success, Try}

/**
 * Flink sqls serial interaction executor actor.
 * This is the default implementation of [[FsiExecutor]].
 *
 * The format of the submitted flink job name is "potamoi-fsi-{sessionId}"
 * such as "potamoi-fsi-1234567890"
 *
 * @author Al-assad
 */
object FsiSerialExecutor {

  import FsiExecutor._

  sealed trait Internal extends Expansion

  // A execution plan process is finished
  private final case class ProcessFinished(result: MaybeDone, replyTo: ActorRef[MaybeDone]) extends Internal

  // A single statements is finished
  private final case class SingleStmtFinished(result: SingleStmtResult) extends Internal

  // Initialize the result storage bugger for QueryOperation
  private final case class InitQueryRsBuffer(collStrategy: RsCollectStrategy) extends Internal

  // Collect the columns of TableResult from QueryOperation
  private final case class CollectQueryOpColsRs(cols: Seq[Column]) extends Internal

  // Collect a row of TableResult from QueryOperation
  private final case class CollectQueryOpRow(row: Row) extends Internal

  // A error occurred when collecting from TableResult
  private final case class ErrorWhenCollectQueryOpRow(error: Error) extends Internal

  // Hook the Flink JobClient
  private final case class HookFlinkJobClient(jobClient: JobClient) extends Internal

  private final case object CheckIdleTimeout extends Internal

  def apply(sessionId: SessionId): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { implicit timers =>
        ctx.log.info(s"[sessionId: $sessionId] FsiExecutor actor created")
        new FsiSerialExecutor(sessionId).action()
      }
    }

}


class FsiSerialExecutor private(sessionId: SessionId)
                               (implicit ctx: ActorContext[FsiExecutor.Command], timers: TimerScheduler[FsiExecutor.Command]) {

  import ExecRsChangeEvent._
  import FsiExecutor._
  import FsiSerialExecutor._

  // Execution context for CancelableFuture
  implicit val ec: ExecutionContextExecutor = ctx.system.dispatchers.lookup(
    DispatcherSelector.fromConfig("potamoi.flink-gateway.dispatcher.fsi-executor")
  )
  // Cancelable process log, Plz use this log when it need to output logs inside CancelableFuture
  // private val pcLog: Logger = Logger(getClass)

  // result change topic
  protected val rsChangeTopic: ActorRef[Topic.Command[ExecRsChange]] = ctx.spawn(
    Topic[ExecRsChange](topicName = s"fsi-executor-state-$sessionId"),
    name = s"fsi-executor-topic-$sessionId")

  // running process
  private var process: Option[CancellableFuture[MaybeDone]] = None
  // executed statements result buffer
  private var rsBuffer: Option[StmtsRsBuffer] = None
  // collected table result buffer
  private var queryRsBuffer: Option[QueryRsBuffer] = None
  // hook flink job client for force cancel job if necessary
  private var jobClientHook: Option[JobClient] = None

  // default flink job name
  protected val defaultJobName = s"potamoi-fsi-$sessionId"

  // executor idle timeout config
  protected val idleCheckProp: ExecutorIdleCheckProps = FsiExecutorIdleCheckProps.from(ctx.system.settings.config)
  // last active timestamp
  private var lastActiveTs = curTs
  private def updateActiveTs(): Unit = lastActiveTs = curTs
  // idle timeout check process
  timers.startTimerAtFixedRate(CheckIdleTimeout, initialDelay = idleCheckProp.initDelay, interval = idleCheckProp.interval)

  private def action(): Behavior[Command] = Behaviors.receiveMessage[Command] {
    case IsInProcess(replyTo) => updateActiveTs()
      replyTo ! process.isDefined
      Behaviors.same

    case CancelCurProcess => updateActiveTs()
      if (process.isDefined) {
        process.get.cancel(interrupt = true)
        process = None
        ctx.log.info(s"[sessionId: $sessionId] Current sql execution process cancelled")
        rsChangeTopic ! Topic.Publish(StmtsPlanExecCanceled$Event)
      }
      Behaviors.same

    case ExecuteSqls(statements, props, replyTo) => updateActiveTs()
      process match {
        // when the previous statements execution process has not been done,
        // it's not allowed to execute new operation.
        case Some(_) =>
          val rejectReason = BusyInProcess(startTs = rsBuffer.map(_.startTs).get)
          rsChangeTopic ! Topic.Publish(RejectStmtsExecPlanEvent(statements, rejectReason))
          replyTo ! fail(rejectReason)

        case None =>
          // extract effective execution config
          val effectProps = props.toEffectiveExecProps.updateFlinkConfig { conf =>
            // set flink job name
            conf ?+= "pipeline.name" -> defaultJobName
          }
          // split sql statements and execute each one
          val stmtsPlan = FlinkSqlParser.extractSqlStatements(statements)
          stmtsPlan match {
            case stmts if stmts.isEmpty =>
              replyTo ! fail(StatementIsEmpty())
            case stmts =>
              rsChangeTopic ! Topic.Publish(AcceptStmtsExecPlanEvent(stmtsPlan, effectProps))
              // reset result buffer
              rsBuffer = Some(StmtsRsBuffer(ListBuffer.empty, curTs))
              queryRsBuffer = None
              // parse and execute statements in cancelable future
              process = Some(CancellableFuture(execStatementsPlan(stmts, effectProps)))
              ctx.pipeToSelf(process.get) {
                case Success(re) => ProcessFinished(re, replyTo)
                case Failure(cause) => cause match {
                  // when the execution process has been cancelled, it still means a success done result.
                  case _: CancellationException => ProcessFinished(success(Done), replyTo)
                  case _ => ProcessFinished(fail(ExecutionFailure(cause)), replyTo)
                }
              }
          }
      }
      Behaviors.same

    case SubscribeState(listener) => updateActiveTs()
      rsChangeTopic ! Topic.Subscribe(listener)
      Behaviors.same

    case UnsubscribeState(listener) => updateActiveTs()
      rsChangeTopic ! Topic.Unsubscribe(listener)
      Behaviors.same

    case Terminate(reason) => updateActiveTs()
      ctx.log.info(s"FsiExecutor[sessionId: $sessionId] is actively terminated [reason: $reason]")
      rsChangeTopic ! Topic.Publish(ActivelyTerminated(reason))
      Behaviors.stopped

    case cmd: GetQueryResult => updateActiveTs()
      queryResultBehavior(cmd)

    case cmd: Internal => internalBehavior(cmd)
    case _ => Behaviors.same

  }.receiveSignal {
    case (context, PostStop) =>
      // release resources before stop
      process.foreach { ps =>
        ps.cancel(true)
        context.log.info(s"SqlSerialExecutor[sessionId: $sessionId] interrupt the running statements execution process.")
      }
      Try(jobClientHook.map(_.cancel))
        .failed.foreach(context.log.error(s"SqlSerialExecutor[sessionId: $sessionId] fail to cancel flink job from flink JobClient.", _))
      context.log.info(s"SqlSerialExecutor[sessionId: $sessionId] stopped.")
      Behaviors.same
  }


  /**
   * [[Internal]] command received behavior
   */
  private def internalBehavior(command: Internal): Behavior[Command] = command match {

    case CheckIdleTimeout =>
      val durMs = curTs - lastActiveTs
      if (durMs > idleCheckProp.timeoutMillis) {
        ctx.log.info(s"FsiExecutor[sessionId: $sessionId] idle for ${durMs}ms [limit: ${idleCheckProp.timeout}], " +
                     s"about to shutdown automatically.")
        ctx.self ! Terminate(s"idle timeout for ${durMs}ms")
      }
      Behaviors.same

    case HookFlinkJobClient(jobClient) =>
      jobClientHook = Some(jobClient)
      ctx.log.info(s"[sessionId: $sessionId] Hooked Flink JobClient [jobId: ${jobClient.getJobID.toString}]")
      Behaviors.same

    case ProcessFinished(result, replyTo) =>
      replyTo ! result
      process = None
      queryRsBuffer.foreach { buf =>
        buf.isFinished = true
        buf.ts = curTs
      }
      rsChangeTopic ! Topic.Publish(AllStmtsDone(rsBuffer.map(_.toSerialStmtsResult(true)).orNull))
      // cancel flink job if necessary
      Try(jobClientHook.map(_.cancel))
        .failed.foreach(ctx.log.error(s"[sessionId: $sessionId] Fail to cancel from Flink JobClient", _))
      Behaviors.same

    case SingleStmtFinished(stmtRs) =>
      rsBuffer.foreach { buffer =>
        buffer.result += stmtRs
      }
      rsChangeTopic ! Topic.Publish(SingleStmtDone(stmtRs))
      Behaviors.same

    case InitQueryRsBuffer(strategy) =>
      val rowsBuffer: DataRowBuffer = strategy match {
        case RsCollectStrategy(EvictStrategy.DROP_HEAD, limit) => FiniteQueue[Row](limit)
        case RsCollectStrategy(EvictStrategy.DROP_TAIL, limit) => new ArrayBuffer[Row](limit + 10)
        case _ => new ArrayBuffer[Row]()
      }
      queryRsBuffer = Some(QueryRsBuffer(rows = rowsBuffer, startTs = curTs))
      Behaviors.same

    case CollectQueryOpColsRs(cols) =>
      queryRsBuffer.foreach { buf =>
        buf.cols = cols
        buf.ts = curTs
      }
      rsChangeTopic ! Topic.Publish(ReceiveQueryOpColumns(cols))
      Behaviors.same

    case CollectQueryOpRow(row) =>
      queryRsBuffer.foreach { buf =>
        buf.rows += row
        buf.ts = curTs
      }
      rsChangeTopic ! Topic.Publish(ReceiveQueryOpRow(row))
      Behaviors.same

    case ErrorWhenCollectQueryOpRow(err) =>
      queryRsBuffer.foreach { buf =>
        buf.error = Some(err)
        buf.ts = curTs
      }
      rsChangeTopic ! Topic.Publish(ErrorDuringQueryOp(err))
      Behaviors.same
  }


  /**
   * [[QueryResult]] command received behavior
   */
  private def queryResultBehavior(command: GetQueryResult): Behavior[Command] = command match {

    case GetExecPlanRsSnapshot(replyTo) =>
      val snapshot = rsBuffer.map(_.toSerialStmtsResult(process.isEmpty))
      replyTo ! snapshot
      Behaviors.same

    case GetQueryRsSnapshot(limit, replyTo) =>
      val snapshot = queryRsBuffer match {
        case None => None
        case Some(buf) =>
          val rows = limit match {
            case Int.MaxValue => buf.rows
            case size if size < 0 => buf.rows
            case size => buf.rows.take(size)
          }
          Some(TableResultSnapshot(
            data = TableResultData(buf.cols, Seq(rows: _*)),
            error = buf.error,
            isFinished = buf.isFinished,
            lastTs = buf.ts
          ))
      }
      replyTo ! snapshot
      Behaviors.same

    case GetQueryRsSnapshotByPage(PageReq(pageIndex, pageSize), replyTo) =>
      val snapshot = queryRsBuffer match {
        case None => None
        case Some(buf) =>
          val payload = {
            val rowsCount = buf.rows.size
            val pages = (rowsCount.toDouble / pageSize).ceil.toInt
            val offset = pageIndex * pageSize
            val rowsSlice = buf.rows.slice(offset, offset + pageSize)
            PageRsp(
              index = pageIndex,
              size = rowsSlice.size,
              totalPages = pages,
              totalRows = rowsCount,
              hasNext = pageIndex < pages - 1,
              data = TableResultData(buf.cols, Seq(rowsSlice: _*))
            )
          }
          Some(interact.PageableTableResultSnapshot(
            data = payload,
            error = buf.error,
            isFinished = buf.isFinished,
            lastTs = buf.ts))
      }
      replyTo ! snapshot
      Behaviors.same

  }


  /**
   * Execute sql statements plan.
   */
  private def execStatementsPlan(stmts: Seq[String], effectProps: EffectiveExecProps): MaybeDone = {
    val flinkDeps = effectProps.flinkDeps
    // todo Download flink deps and check dep jars from s3
    val depURLs: Seq[URL] = flinkDeps.map(new File(_).toURI.toURL)

    tryRunWithExtraDeps(depURLs) { classloader =>
      Try {
        // init flink environment context
        val flinkConfig = Configuration.fromMap(effectProps.flinkConfig.asJava)
        flinkConfig.set(PipelineOptions.JARS, flinkDeps.map(dep => s"file://$dep").toBuffer.asJava)
        createFlinkContext(flinkConfig, classloader)
      } match {
        case Failure(cause) => fail(InitFlinkEnvFailure(cause))
        case Success(flinkCtx) =>
          // parse and execute sql statements
          execImmediateOpsAndStashNonImmediateOps(stmts)(flinkCtx) match {
            case Left(_) => success(Done)
            case Right(stashOp) =>
              if (stashOp.isEmpty) success(Done)
              // execute stashed operations
              else success(execStashedOps(stashOp, effectProps.rsCollectSt)(flinkCtx))
          }
      }
    } match {
      case Success(done) => done
      case Failure(cause) => fail(LoadDepsToClassLoaderFailure(depURLs.map(_.toString), cause))
    }
  }

  /**
   * Parse all sql statements to Flink Operation, then execute all of them except for the
   * [[QueryOperation]] and [[ModifyOperation]] which will be stashed in [[StashOpToken]].
   */
  private def execImmediateOpsAndStashNonImmediateOps(stmts: Seq[String])(implicit flinkCtx: FlinkContext): Either[Done, StashOpToken] = {
    val stashToken = StashOpToken()
    var shouldDone = false
    breakable {
      for (stmt <- stmts) {
        if (stashToken.queryOp.isDefined) break
        // parse statement
        val op = Try(flinkCtx.parser.parse(stmt).get(0)).foldIdentity { err =>
          ctx.self ! SingleStmtFinished(SingleStmtResult.fail(stmt, Error(s"Fail to parse statement: ${stmt.compact}", err)))
          shouldDone = true
          break
        }
        op match {
          case op: QueryOperation => stashToken.queryOp = Some(stmt -> op)
          case op: ModifyOperation => stashToken.modifyOps += stmt -> op
          case op =>
            // when a ModifyOperation has been staged, the remaining normal statement would be skipped.
            if (stashToken.modifyOps.nonEmpty) break
            rsChangeTopic ! Topic.Publish(SingleStmtStart(stmt))
            val tableResult: TableResult = Try(flinkCtx.tEnvInternal.executeInternal(op)).foldIdentity { err =>
              ctx.self ! SingleStmtFinished(SingleStmtResult.fail(stmt, Error(s"Fail to execute statement: ${stmt.compact}", err)))
              shouldDone = true
              break
            }
            // collect result from flink TableResult immediately
            val cols = FlinkApiCovertTool.extractSchema(tableResult)
            val rows = Using(tableResult.collect)(iter => iter.asScala.map(row => FlinkApiCovertTool.covertRow(row)).toSeq)
              .foldIdentity { err =>
                ctx.self ! SingleStmtFinished(SingleStmtResult.fail(stmt, Error(s"Fail to collect table result: ${stmt.compact}", err)))
                shouldDone = true
                break
              }
            ctx.self ! SingleStmtFinished(SingleStmtResult.success(stmt, ImmediateOpDone(TableResultData(cols, rows))))
        }
      }
    }
    if (shouldDone) Left(Done) else Right(stashToken)
  }

  /**
   * Execute the stashed non-immediate operations such as  [[QueryOperation]] and [[ModifyOperation]],
   * and collect the result from TableResult.
   *
   * This process can lead to long thread blocking.
   */
  //noinspection DuplicatedCode
  private def execStashedOps(stashOp: StashOpToken, rsCollStrategy: RsCollectStrategy)
                            (implicit flinkCtx: FlinkContext): Done = stashOp.toEither match {
    case Right(stashModifyOps) =>
      val (stmts, modifyOps) = stashModifyOps.map(_._1).mkString(";") -> stashModifyOps.map(_._2)
      rsChangeTopic ! Topic.Publish(SingleStmtStart(stmts))
      Try(flinkCtx.tEnvInternal.executeInternal(modifyOps.asJava)) match {
        case Failure(err) =>
          ctx.self ! SingleStmtFinished(SingleStmtResult.fail(stmts, Error(s"Fail to execute modify statements: ${stmts.compact}", err)))
          Done
        case Success(tableResult) =>
          val jobClient: Option[JobClient] = tableResult.getJobClient.asScala
          ctx.self ! HookFlinkJobClient(jobClient.get)

          val jobId: Option[String] = jobClient.map(_.getJobID.toString)
          ctx.self ! SingleStmtFinished(SingleStmtResult.success(stmts, SubmitModifyOpDone(jobId.get)))
          rsChangeTopic ! Topic.Publish(SubmitJobToFlinkCluster(OpType.MODIFY, jobId.get, defaultJobName))
          // blocking until the insert operation job is finished
          jobClient.get.getJobExecutionResult.get()
          Done
      }

    case Left((stmt, queryOp)) =>
      rsChangeTopic ! Topic.Publish(SingleStmtStart(stmt))
      Try(flinkCtx.tEnvInternal.executeInternal(queryOp)) match {
        case Failure(err) =>
          ctx.self ! SingleStmtFinished(SingleStmtResult.fail(stmt, Error(s"Fail to execute query statement: ${stmt.compact}", err)))
          Done

        case Success(tableResult) =>
          val jobClient: Option[JobClient] = tableResult.getJobClient.asScala
          ctx.self ! HookFlinkJobClient(jobClient.get)

          val jobId: Option[String] = jobClient.map(_.getJobID.toString)
          ctx.self ! SingleStmtFinished(SingleStmtResult.success(stmt, SubmitQueryOpDone(jobId.get)))
          ctx.self ! InitQueryRsBuffer(rsCollStrategy)
          rsChangeTopic ! Topic.Publish(SubmitJobToFlinkCluster(OpType.QUERY, jobId.get, defaultJobName))

          val cols = FlinkApiCovertTool.extractSchema(tableResult)
          ctx.self ! CollectQueryOpColsRs(cols)

          // get the topmost fetch rex from query operation
          val limitRex = queryOp match {
            case op: PlannerQueryOperation => FlinkSqlParser.getTopmostLimitRexFromOp(op)
            case _ => None
          }
          // blocking until the select operation job is finished
          Using(tableResult.collect) { iter =>
            val stream = rsCollStrategy match {
              case RsCollectStrategy(EvictStrategy.DROP_TAIL, limit) => iter.asScala.take(limit.min(limitRex.getOrElse(Int.MaxValue)))
              case RsCollectStrategy(EvictStrategy.DROP_HEAD, _) =>
                if (limitRex.isDefined) iter.asScala.take(limitRex.get)
                else iter.asScala
              case _ => iter.asScala
            }
            stream.foreach(row => ctx.self ! CollectQueryOpRow(FlinkApiCovertTool.covertRow(row)))
          } match {
            case Failure(err) =>
              ctx.self ! ErrorWhenCollectQueryOpRow(Error("Fail to collect table result", err))
              Done
            case Success(_) =>
              Done
          }
      }
  }


  /**
   * Flink environment context.
   */
  private case class FlinkContext(tEnv: StreamTableEnvironment, tEnvInternal: TableEnvironmentInternal, parser: Parser)

  /**
   * Initialize flink context.
   */
  private def createFlinkContext(flinkConfig: Configuration, classloader: ClassLoader): FlinkContext = {
    val env = new StreamExecutionEnvironment(flinkConfig, classloader)
    val tEnv = StreamTableEnvironment.create(env)
    val tEnvInternal = tEnv.asInstanceOf[TableEnvironmentInternal]
    val parser = tEnvInternal.getParser
    FlinkContext(tEnv, tEnvInternal, parser)
  }

  /**
   * Temporary storage for the Flink Operation that requires for remote submission.
   */
  private case class StashOpToken(var queryOp: Option[(String, QueryOperation)] = None,
                                  modifyOps: mutable.Buffer[(String, ModifyOperation)] = ListBuffer.empty) {

    def isEmpty: Boolean = queryOp.isEmpty && modifyOps.isEmpty
    def toEither: Either[(String, QueryOperation), Seq[(String, ModifyOperation)]] =
      if (queryOp.isDefined) Left(queryOp.get) else Right(modifyOps)
  }

  /**
   * Flink sql statements execution result buffer.
   */
  private case class StmtsRsBuffer(result: mutable.Buffer[SingleStmtResult], startTs: Long) {
    def lastTs: Long = result.lastOption.map(_.ts).getOrElse(startTs)
    def lastOpType: OpType = result.lastOption.map(_.opType).getOrElse(OpType.UNKNOWN)

    // convert to SerialStmtsResult
    def toSerialStmtsResult(finished: Boolean): SerialStmtsResult = SerialStmtsResult(
      result = Seq(result: _*),
      isFinished = finished,
      lastOpType = lastOpType,
      startTs = startTs,
      lastTs = lastTs
    )
  }

  private type DataRowBuffer = AbstractSeq[Row] with mutable.Builder[Row, AbstractSeq[Row]] with TraversableLike[Row, AbstractSeq[Row]]

  /**
   * Flink query statements execution result buffer.
   */
  private case class QueryRsBuffer(var cols: Seq[Column] = Seq.empty,
                                   rows: DataRowBuffer,
                                   var error: Option[Error] = None,
                                   var isFinished: Boolean = false,
                                   startTs: Long,
                                   var ts: Long = curTs)

}
