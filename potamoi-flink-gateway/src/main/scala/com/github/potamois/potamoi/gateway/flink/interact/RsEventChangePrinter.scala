package com.github.potamois.potamoi.gateway.flink.interact

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.github.potamois.potamoi.commons.{RichString, RichThrowable, Tabulator}

/**
 * ResultChangeEvent printer actor , used to output [[SqlSerialExecutor]]
 * change events during the debugging phase.
 * See [[ResultChangeEvent]].
 *
 * @author Al-assad
 */
object RsEventChangePrinter {

  import ResultChangeEvent._

  /**
   * @param sessionId            Executor session id
   * @param printEachRowReceived whether to print each row that received from [[ReceiveQueryOpRow]]
   */
  def apply(sessionId: String, printEachRowReceived: Boolean = false): Behavior[ResultChange] =
    Behaviors.receive[ResultChange] { (ctx, msg) =>
      val log = ctx.log
      msg match {

        case AcceptStmtsExecPlan(stmts) => log.info(
          s"""@Receive[$sessionId] AcceptStmtsExecPlan => executor accepted a new statements plan.
             |stmts:
             |${stmts.map(_.compact).mkString("\n")}"
             |""".stripMargin)
          Behaviors.same

        case RejectStmtsExecPlan(stmts, cause) => log.info(
          s"""@Receive[$sessionId] RejectStmtsExecPlan => executor reject a statements plan.
             |reason: ${cause.reason}
             |stmts: ${stmts.compact}"
             |""".stripMargin)
          Behaviors.same

        case SingleStmtStart(stmt) => log.info(
          s"""@Receive[$sessionId] SingleStmtStart => start to execute a statement.
             |stmts: ${stmt.compact}
             |""".stripMargin)
          Behaviors.same

        case SingleStmtDone(rs) => log.info(
          s"""@Receive[$sessionId] SingleStmtDone => finished a statement.
             |success: ${rs.isInstanceOf[OperationDone]}
             |""".stripMargin
            .concat(rs.toFriendlyString))
          Behaviors.same

        case AllStmtsDone(rs) => log.info(
          s"""@Receive[$sessionId] AllStmtsDone => finished all statements.
             |""".stripMargin
            .concat(rs.toFriendlyString)
            .concat("\n"))
          Behaviors.same

        case SubmitJobToFlinkCluster(opType, jobId) => log.info(
          s"""@Receive[$sessionId] SubmitJobToFlinkCluster => submit a flink job to flink cluster.
             |opType: $opType
             |jobId: $jobId
             |""".stripMargin)
          Behaviors.same

        case ReceiveQueryOpColumns(cols) =>
          log.info(
            "@Receive[$sessionId] ReceiveQueryOpColumns => receive table columns info."
              .concat("\n")
              .concat(Tabulator.format(cols.map(_.name) +: Seq(cols.map(_.dataType))))
              .concat("\n")
          )
          Behaviors.same

        case ReceiveQueryOpRow(row) =>
          if (printEachRowReceived) log.info(s"@Receive[$sessionId] ReceiveQueryOpRow => receive a table row: $row")
          Behaviors.same

        case ErrorDuringQueryOp(error) => log.info(
          s"""@Receive[$sessionId] ErrorDuringQueryOp => A exception occurred during query operation.
             |summary: ${error.summary}
             |${error.stack.getStackTraceAsString}
             |""".stripMargin)
          Behaviors.same
      }

    }

}


