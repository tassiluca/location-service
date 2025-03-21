package io.github.positionpal.location.tracking.actors

import io.github.positionpal.location.application.notifications.NotificationService
import io.github.positionpal.location.application.tracking.MapsService
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.Eventually.PatienceConfig
import cats.effect.IO
import io.github.positionpal.location.application.groups.UserGroupsService
import io.github.positionpal.location.domain.{Scope, Session}
import io.github.positionpal.entities.{GroupId, UserId}

trait SystemVerifier[S, E, X](val ins: List[S], val events: List[E]):
  infix def -->(outs: List[S])(using ctx: Context[S, X]): Verification[E, X]

trait Verification[E, X]:
  infix def verifying(verifyLast: (E, X) => Unit)(using patience: PatienceConfig): Unit

trait Context[S, X]:
  def initialStates(ins: List[S]): List[X]
  def notificationService: NotificationService[IO]
  def mapsService: MapsService[IO]
  def userGroupsService: UserGroupsService[IO]

import akka.actor.testkit.typed.scaladsl.ActorTestKitBase

trait RealTimeUserTrackerVerifierDSL:
  context: ActorTestKitBase & Matchers =>

  import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
  import io.github.positionpal.location.domain.{ClientDrivingEvent, UserState}
  import io.github.positionpal.location.tracking.actors.RealTimeUserTracker.*
  import org.scalatest.concurrent.Eventually.eventually

  given Conversion[UserState, List[UserState]] = _ :: Nil
  given Conversion[ClientDrivingEvent, List[ClientDrivingEvent]] = _ :: Nil
  extension (u: UserState) infix def |(other: UserState): List[UserState] = u :: other :: Nil
  extension (us: List[UserState]) infix def |(other: UserState): List[UserState] = us :+ other

  extension (xs: List[UserState])
    infix def --(events: List[ClientDrivingEvent]): SystemVerifier[UserState, ClientDrivingEvent, Session] =
      RealTimeUserTrackerVerifier(xs, events)

  private class RealTimeUserTrackerVerifier(ins: List[UserState], events: List[ClientDrivingEvent])
      extends SystemVerifier[UserState, ClientDrivingEvent, Session](ins, events):

    infix override def -->(outs: List[UserState])(using
        ctx: Context[UserState, Session],
    ): Verification[ClientDrivingEvent, Session] = new Verification[ClientDrivingEvent, Session]:
      infix override def verifying(verifyLast: (ClientDrivingEvent, Session) => Unit)(using PatienceConfig): Unit =
        val testKit = EventSourcedBehaviorTestKit[Command, Event, Session](
          system = system,
          behavior = RealTimeUserTracker(
            scope = Scope(UserId.create("luke"), GroupId.create("astro")),
            tag = "rtut-0",
          )(using ctx.notificationService, ctx.mapsService, ctx.userGroupsService),
        )
        ctx
          .initialStates(ins)
          .zipWithIndex
          .foreach: (session, idx) =>
            testKit.initialize(session)
            events.foreach: e =>
              testKit.runCommand(e).events should contain(
                StatefulDrivingEvent(session.userState.next(e, session.tracking).getOrElse(fail()), e),
              )
            eventually:
              val currentSession = testKit.getState()
              currentSession.userState shouldBe outs(if outs.size == 1 then 0 else idx)
              verifyLast(events.last, currentSession)
