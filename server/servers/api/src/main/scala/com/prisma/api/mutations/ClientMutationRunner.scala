package com.prisma.api.mutations

import com.prisma.api.ApiMetrics
import com.prisma.api.database.DataResolver
import com.prisma.api.database.mutactions._
import com.prisma.api.schema.GeneralError

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Failure

object ClientMutationRunner {

  import com.prisma.utils.future.FutureUtils._

  def run[T](
      clientMutation: ClientMutation[T],
      dataResolver: DataResolver
  ): Future[T] = {
    for {
      mutactionGroups  <- clientMutation.prepareMutactions()
      errors           = verifyMutactions(mutactionGroups, dataResolver)
      _                = if (errors.nonEmpty) throw errors.head
      executionResults <- performMutactions(mutactionGroups, dataResolver.project.id)
      dataItem <- {
        executionResults
          .filter(_.isInstanceOf[GeneralError])
          .map(_.asInstanceOf[GeneralError]) match {
          case errors if errors.nonEmpty => throw errors.head
          case _                         => clientMutation.getReturnValue
        }
      }
    } yield dataItem
  }

  private def verifyMutactions(mutactionGroups: List[MutactionGroup], dataResolver: DataResolver): List[GeneralError] = {
    val verifications = mutactionGroups.flatMap(_.mutactions).map(_.verify())
    val errors = verifications.collect { case Failure(x: GeneralError) => x }
    errors
  }

  private def performMutactions(mutactionGroups: List[MutactionGroup], projectId: String): Future[List[MutactionExecutionResult]] = {
    // Cancel further Mutactions and MutactionGroups when a Mutaction fails
    // Failures in async MutactionGroups don't stop other Mutactions in same group
    mutactionGroups.map(group => () => performGroup(group, projectId)).runSequentially.map(_.flatten)
  }

  private def performGroup(group: MutactionGroup, projectId: String): Future[List[MutactionExecutionResult]] = {
    group match {
      case MutactionGroup(mutactions, true) =>
        Future.sequence(mutactions.map(m => runWithTiming(m, projectId)))

      case MutactionGroup(mutactions: List[Mutaction], false) =>
        mutactions.map(m => () => runWithTiming(m, projectId)).runSequentially
    }
  }

  private def runWithTiming(mutaction: Mutaction, projectId: String): Future[MutactionExecutionResult] = {
    performWithTiming(
      s"execute ${mutaction.getClass.getSimpleName}", {
        mutaction match {
          case mut: ClientSqlDataChangeMutaction =>
            ApiMetrics.mutactionTimer.timeFuture(projectId) {
              runWithErrorHandler(mut)
            }
          case mut =>
            runWithErrorHandler(mut)
        }
      }
    )
  }

  private def runWithErrorHandler(mutaction: Mutaction): Future[MutactionExecutionResult] = {
    mutaction.handleErrors match {
      case Some(errorHandler) => mutaction.execute.recover(errorHandler)
      case None               => mutaction.execute
    }
  }

  private def performWithTiming[A](name: String, f: Future[A]): Future[A] = {
    //    val begin = System.currentTimeMillis()
    //    f andThen {
    //      case x =>
    //        mutactionTimings :+= Timing(name, System.currentTimeMillis() - begin)
    //        x
    //    }

    f
  }
}
