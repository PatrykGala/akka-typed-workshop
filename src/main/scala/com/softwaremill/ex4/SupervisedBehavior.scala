package com.softwaremill.ex4

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed._

object SupervisedBehavior {
  type SignalHandler[T] = PartialFunction[(ActorContext[T], Signal), Behavior[T]]

  sealed trait Command
  case class Request(param: Int, replyTo: ActorRef[ParentResult]) extends Command

  private sealed trait HandleWorkerResult         extends Command
  private case class HandleWorkerResponse(i: Int) extends HandleWorkerResult
  private case class HandleWorkerFailure(i: Int)  extends HandleWorkerResult

  sealed trait ParentResult
  case class Response(param: Int)      extends ParentResult
  case class ParentFailure(param: Int) extends ParentResult

  lazy val behavior: Behavior[Command] =
    Behaviors
      .supervise(
        Behaviors
          .setup[Command] { context =>
            val workerBehavior = Worker.behavior
            context.log.info("Parent setup")

            val worker = context.spawn(workerBehavior, "worker")
            context.watch(worker)

            val workerResponseMapper: ActorRef[Worker.PartialWorkResponse] =
              context.messageAdapter {
                case Worker.PartialWorkResult(param) => HandleWorkerResponse(param)
                case Worker.PartialWorkFailed(param) => HandleWorkerFailure(param)
              }

            lazy val handleRequests: Behavior[Command] =
              Behaviors
                .receiveMessage[Command] {
                  case Request(param, replyTo) =>
                    context.log.info(s"Delegating work ($param) to a child actor")
                    worker ! Worker.DoPartialWork(param, workerResponseMapper)
                    working(replyTo)
                  case r: HandleWorkerResult =>
                    context.log.error(s"Unexpected result when parent actor is idle: $r")
                    Behaviors.same
                }
                .receiveSignal(onSignal)

            def working(respondTo: ActorRef[ParentResult]): Behavior[Command] =
              Behaviors
                .receiveMessage[Command] {
                  case HandleWorkerResponse(i) =>
                    respondTo ! Response(i)
                    handleRequests
                  case HandleWorkerFailure(i) =>
                    respondTo ! ParentFailure(i)
                    handleRequests
                  case Request(param, _) =>
                    context.log.error(s"Cannot handle request ($param) while worker is busy!")
                    respondTo ! ParentFailure(param)
                    Behaviors.same
                }
                .receiveSignal(onSignal)

            def onSignal: SignalHandler[Command] = {
              case (context, PostStop) =>
                context.log.info("Stopping parent actor")
                Behaviors.same
              case (context, PreRestart) =>
                context.log.info("Restarting parent actor")
                Behaviors.same
            }

            handleRequests
          }
      )
      .onFailure[DeathPactException](SupervisorStrategy.restart)
}

object Worker {
  case class DoPartialWork(param: Int, replyTo: ActorRef[PartialWorkResponse])

  sealed trait PartialWorkResponse

  case class PartialWorkResult(param: Int) extends PartialWorkResponse
  case class PartialWorkFailed(param: Int) extends PartialWorkResponse

  // Should multiply input by 2
  lazy val behavior: Behavior[DoPartialWork] =
    Behaviors.setup { context =>
      context.log.info("Starting child")
      Behaviors
        .receive[DoPartialWork] {
          case (_, DoPartialWork(8, replyTo)) =>
            replyTo ! PartialWorkFailed(8)
            throw new IllegalArgumentException("8 is forbidden!")
          case (context, DoPartialWork(param, replyTo)) =>
            context.log.info("Partial job done, returning result")
            val calculatedResult = param * 2
            replyTo ! PartialWorkResult(calculatedResult)
            Behaviors.same
        }
        .receiveSignal {
          case (context, PostStop) =>
            context.log.info("Stopping worker actor.")
            Behaviors.same
          case (context, PreRestart) =>
            context.log.info("Actor cleanup and restart.")
            Behaviors.same
        }
    }
}
