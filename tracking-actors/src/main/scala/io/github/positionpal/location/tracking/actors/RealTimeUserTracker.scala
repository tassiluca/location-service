package io.github.positionpal.location.tracking.actors

import java.time.Instant.now

import scala.util.{Failure, Success}
import scala.concurrent.duration.DurationInt

import akka.persistence.typed.scaladsl.RetentionCriteria.snapshotEvery
import akka.cluster.Cluster
import io.github.positionpal.location.application.notifications.NotificationService
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.actor.typed.*
import io.github.positionpal.location.tracking.utils.AkkaUtils.refOf
import io.github.positionpal.location.application.tracking.reactions.*
import io.github.positionpal.location.application.tracking.MapsService
import io.github.positionpal.location.presentation.ScopeCodec.*
import io.github.positionpal.location.domain.*
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.actor.typed.SupervisorStrategy.restartWithBackoff
import io.github.positionpal.location.domain.UserState.*
import akka.cluster.sharding.typed.scaladsl.*
import cats.effect.IO
import akka.persistence.typed.PersistenceId
import io.github.positionpal.location.domain.EventConversions.userUpdateFrom
import io.github.positionpal.location.application.groups.UserGroupsService
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}

/** The actor in charge of tracking the real-time location of users, reacting to
  * their movements and status changes. This actor is managed by Akka Cluster Sharding.
  */
object RealTimeUserTracker:

  /** Uniquely identifies the types of this entity instances (actors) that will be managed by cluster sharding. */
  val key: EntityTypeKey[Command] = EntityTypeKey(getClass.getSimpleName)

  /** Labels used to tag the events emitted by this kind entity actors to distribute them over several projections.
    * Each entity instance selects it (based on an appropriate strategy) and uses it to tag the events it emits.
    */
  val tags: Seq[String] = Vector.tabulate(5)(i => s"${getClass.getSimpleName}-$i")

  /** The key used to identify the timer that checks whether the user went offline. */
  private val timerKey: String = "alive-check-timer"

  /** A message sent by the actor to itself in response to an async operation or to initiate an action proactively. */
  sealed trait SelfMessage

  /** A [[SelfMessage]] indicating the spawned async operation has completed with an unuseful result. */
  case object Ignore extends SelfMessage

  /** A [[SelfMessage]] triggered regularly by a timer to check whether the user went offline. */
  case object AliveCheck extends SelfMessage

  type Command = DrivingEvent | SelfMessage
  type Event = StatefulDrivingEvent

  case class StatefulDrivingEvent(state: UserState, event: DrivingEvent) extends AkkaSerializable

  def apply(using
      NotificationService[IO],
      MapsService[IO],
      UserGroupsService[IO],
  ): Entity[Command, ShardingEnvelope[Command]] =
    Entity(key)(ctx => this(ctx.entityId.decode, tags(math.abs(ctx.entityId.hashCode % tags.size))))

  def apply(scope: Scope, tag: String)(using
      NotificationService[IO],
      MapsService[IO],
      UserGroupsService[IO],
  ): Behavior[Command] =
    Behaviors.setup: ctx =>
      given ActorContext[Command] = ctx
      Behaviors.withTimers: timer =>
        ctx.log.debug("Starting RealTimeUserTracker::{}@{}", scope, Cluster(ctx.system).selfMember.address)
        val persistenceId = PersistenceId(key.name, scope.encode)
        EventSourcedBehavior(persistenceId, Session.of(scope), commandHandler(timer), eventHandler)
          .withTagger(_ => Set(tag))
          .snapshotWhen((_, event, _) => event == RoutingStopped, deleteEventsOnSnapshot = true)
          .withRetention(snapshotEvery(numberOfEvents = 100, keepNSnapshots = 1).withDeleteEventsOnSnapshot)
          .onPersistFailure(restartWithBackoff(minBackoff = 2.second, maxBackoff = 15.seconds, randomFactor = 0.2))

  private def eventHandler: (Session, Event) => Session = (currentSession, statefulEvent) =>
    currentSession.updateWith(statefulEvent.event).getOrElse(currentSession)

  private def commandHandler(timerScheduler: TimerScheduler[Command])(using
      ctx: ActorContext[Command],
      notifier: NotificationService[IO],
      maps: MapsService[IO],
      userGroupsService: UserGroupsService[IO],
  ): (Session, Command) => Effect[Event, Session] = (session, command) =>
    command match
      case e: DrivingEvent if e canBeAppliedTo session =>
        e match
          case event: ClientDrivingEvent =>
            event match
              case _: WentOffline => timerScheduler.cancel(timerKey)
              case _ if !timerScheduler.isTimerActive(timerKey) =>
                timerScheduler.startTimerAtFixedRate(timerKey, msg = AliveCheck, interval = 20.seconds)
              case _ => ()
            trackingHandler(session, event)
          case _ => ()
        persistAndNotify(session.userState.next(e, session.tracking).toOption.get, e)
      case _: AliveCheck.type => aliveCheckHandler(session)
      case e => ctx.log.info("Ignoring {}", e); Effect.none

  import cats.effect.unsafe.implicits.global

  private def trackingHandler(using
      ctx: ActorContext[Command],
      notifier: NotificationService[IO],
      maps: MapsService[IO],
      userGroupsService: UserGroupsService[IO],
  ): (Session, ClientDrivingEvent) => Unit = (s, e) =>
    val reaction = (PreCheckNotifier[IO] >>> ArrivalCheck[IO] >>> StationaryCheck[IO] >>> ArrivalTimeoutCheck[IO])(s, e)
    ctx.pipeToSelf(reaction.unsafeToFuture()):
      case Success(result) =>
        result match
          case Left(e: DrivingEvent) => e
          case _ => Ignore
      case Failure(exception) => ctx.log.error("Error while reacting: {}", exception.getMessage); Ignore

  private def aliveCheckHandler(using ctx: ActorContext[Command]): (Session) => Effect[Event, Session] = session =>
    val event = WentOffline(now(), session.scope)
    if (event canBeAppliedTo session) && session.lastSampledLocation.get.timestamp.isBefore(now().minusSeconds(60)) then
      ctx.self ! event
    Effect.none

  private def persistAndNotify(state: UserState, event: DrivingEvent)(using ctx: ActorContext[Command]) =
    Effect
      .persist[Event, Session](StatefulDrivingEvent(state, event))
      .thenRun(s => refOf(GroupManager.key, s.scope.groupId.value())(using ctx.system) ! userUpdateFrom(event, s))
