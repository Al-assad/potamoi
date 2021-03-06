package com.github.potamois.potamoi.gateway.flink.interact

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.github.potamois.potamoi.commons.{RichString, RichThrowable, Tabulator}
import FsiSessManager.SessionId

/**
 * FsiExecResultChangeEvent printer actor , used to output [[FsiSerialExecutor]]
 * change events during the debugging phase.
 * See [[ExecRsChange]].
 *
 * @author Al-assad
 */
object ExecRsChangePrinter {

  import ExecRsChangeEvent._

  /**
   * @param sessionId            Executor session id
   * @param printEachRowReceived whether to print each row that received from [[ReceiveQueryOpRow]]
   */
  def apply(sessionId: SessionId, printEachRowReceived: Boolean = false): Behavior[ExecRsChange] =
    Behaviors.receive[ExecRsChange] { (ctx, msg) =>
      val log = ctx.log
      msg match {

        case AcceptStmtsExecPlanEvent(stmts, config) => log.info(
          s"""@Receive[sessionId: $sessionId] AcceptStmtsExecPlan => executor accepted a new statements plan.
             |stmts:
             |${stmts.map(e => s"  ${e.compact}").mkString("\n")}"
             |flink-config:
             |${config.flinkConfig.map(e => s"  ${e._1} = ${e._2}").mkString("\n")}
             |flink-extra-dependencies:
             |${config.flinkDeps.map(e => s"  $e").mkString("\n")}
             |result-collection-strategy: ${config.rsCollectSt}
             |""".stripMargin)
          Behaviors.same

        case RejectStmtsExecPlanEvent(stmts, cause) => log.info(
          s"""@Receive[sessionId: $sessionId] RejectStmtsExecPlan => executor reject a statements plan.
             |reason: ${cause.reason}
             |stmts: ${stmts.compact}"
             |""".stripMargin)
          Behaviors.same

        case SingleStmtStart(stmt) => log.info(
          s"""@Receive[sessionId: $sessionId] SingleStmtStart => start to execute a statement.
             |stmts: ${stmt.compact}
             |""".stripMargin)
          Behaviors.same

        case SingleStmtDone(rs) => log.info(
          s"""@Receive[sessionId: $sessionId] SingleStmtDone => finished a statement.
             |success: ${rs.isInstanceOf[OperationDone]}
             |""".stripMargin
            .concat(rs.toFriendlyString))
          Behaviors.same

        case AllStmtsDone(rs) => log.info(
          s"""@Receive[sessionId: $sessionId] AllStmtsDone => finished all statements.
             |""".stripMargin
            .concat(rs.toFriendlyString)
            .concat("\n"))
          Behaviors.same

        case SubmitJobToFlinkCluster(opType, jobId, jobName) => log.info(
          s"""@Receive[sessionId: $sessionId] SubmitJobToFlinkCluster => submit a flink job to flink cluster.
             |opType: $opType
             |jobId: $jobId
             |jobName: $jobName
             |""".stripMargin)
          Behaviors.same

        case ReceiveQueryOpColumns(cols) =>
          log.info(
            s"@Receive[sessionId: $sessionId] ReceiveQueryOpColumns => receive table columns info."
              .concat("\n")
              .concat(Tabulator.format(cols.map(_.name) +: Seq(cols.map(_.dataType))))
              .concat("\n")
          )
          Behaviors.same

        case ReceiveQueryOpRow(row) =>
          if (printEachRowReceived) log.info(s"@Receive[sessionId: $sessionId] ReceiveQueryOpRow => receive a table row: $row")
          Behaviors.same

        case ErrorDuringQueryOp(error) => log.info(
          s"""@Receive[sessionId: $sessionId] ErrorDuringQueryOp => a exception occurred during query operation.
             |summary: ${error.summary}
             |${error.stack.getStackTraceAsString}
             |""".stripMargin)
          Behaviors.same

        case StmtsPlanExecCanceled$Event =>
          log.info(s"@Receive[sessionId: $sessionId] StmtsPlanExecCanceled => current statements execution plan has been canceled.")
          Behaviors.same

        case ActivelyTerminated(reason) =>
          log.info(s"@Receive[sessionId: $sessionId] ActivelyTerminated => the executor has been terminated"
            + (if(reason.nonEmpty) s" with reason: $reason" else "."))
          Behaviors.same
      }
    }

}


