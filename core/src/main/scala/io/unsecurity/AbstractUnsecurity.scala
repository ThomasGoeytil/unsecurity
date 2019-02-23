package io.unsecurity

import cats.effect.Sync
import io.circe.{Decoder, Encoder}
import io.unsecurity.hlinx.HLinx._
import io.unsecurity.hlinx.{ReversedTupled, SimpleLinx, TransformParams}
import no.scalabin.http4s.directives.Conditional.ResponseDirective
import no.scalabin.http4s.directives.{Directive, Plan}
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Method, Response, ServerSentEvent}
import org.slf4j.Logger
import shapeless.HList

import scala.Ordering.Implicits._

abstract class AbstractUnsecurity[F[_]: Sync, U] {

  case class Endpoint[P <: HList, R, W](method: Method,
                                        path: HLinx[P],
                                        accepts: EntityDecoder[F, R],
                                        produces: EntityEncoder[F, W])
  object Endpoint {
    def apply[P <: HList, R, W](method: Method, path: HLinx[P]) =
      new Endpoint[P, Unit, Unit](method, path, Accepts.EmptyBody, Produces.Nothing)

    def apply[P <: HList, W](method: Method, path: HLinx[P], produces: EntityEncoder[F, W]) =
      new Endpoint[P, Unit, W](method, path, Accepts.EmptyBody, produces)

    def apply[P <: HList, R](method: Method, path: HLinx[P], accepts: EntityDecoder[F, R]) =
      new Endpoint[P, R, Unit](method, path, accepts, Produces.Nothing)
  }

  def log: Logger

  type PathMatcher[A] = PartialFunction[String, Directive[F, A]]

  def secure[P <: HList, R, W, TUP, TUP2](endpoint: Endpoint[P, R, W])(
      implicit revTup: ReversedTupled.Aux[P, TUP],
      transformParams: TransformParams.Aux[TUP, (R, U), TUP2]
  ): Secured[TUP2, W]

  def unsecure[P <: HList, R, W, TUP, TUP2](endpoint: Endpoint[P, R, W])(
      implicit revTup: ReversedTupled.Aux[P, TUP],
      transformParams: TransformParams.Aux[TUP, Tuple1[R], TUP2]
  ): Completable[TUP2, W]

  object Accepts {
    def EmptyBody: EntityDecoder[F, Unit] =
      implicitly[EntityDecoder[F, Unit]]

    def json[R: Decoder]: EntityDecoder[F, R] =
      org.http4s.circe.jsonOf[F, R]

    def raw: EntityDecoder[F, String] =
      implicitly[EntityDecoder[F, String]]
  }

  object Produces {
    def Nothing: EntityEncoder[F, Unit] =
      implicitly[EntityEncoder[F, Unit]]

    def json[W: Encoder]: EntityEncoder[F, W] =
      org.http4s.circe.jsonEncoderOf[F, W]

    def serverSentEvents: EntityEncoder[F, fs2.Stream[F, ServerSentEvent]] =
      implicitly[EntityEncoder[F, fs2.Stream[F, ServerSentEvent]]]

    def raw: EntityEncoder[F, String] =
      implicitly[EntityEncoder[F, String]]
  }

  trait Completable[C, W] {
    def resolve[C2](f: C => C2): Completable[C2, W]
    def run(f: C => Directive[F, W]): Complete
  }

  trait Secured[C, W] {
    def resolve[C2](f: C => C2): Secured[C2, W]
    def authorization(predicate: C => Boolean): Completable[C, W]
    def noAuthorization: Completable[C, W]
  }

  trait Complete {
    def key: List[SimpleLinx]
    def merge(other: AbstractUnsecurity[F, U]#Complete): AbstractUnsecurity[F, U]#Complete
    def methodMap: Map[Method, Any => ResponseDirective[F]]
    def compile: PathMatcher[Response[F]]
  }

  def toHttpRoutes(endpoints: List[AbstractUnsecurity[F, U]#Complete]): HttpRoutes[F] = {
//    log.trace("all endpoints")
//    endpoints.foreach { e =>
//      e.methodMap.keys.foreach { method =>
//        log.trace(s"""${method.name}: /${e.key.mkString("/")}""")
//      }
//    }

    val linxesToList: Map[List[SimpleLinx], List[AbstractUnsecurity[F, U]#Complete]] = endpoints.groupBy(_.key)

    val mergedRoutes: List[AbstractUnsecurity[F, U]#Complete] =
      linxesToList.toList
        .map {
          case (_, groupedEndpoints) => groupedEndpoints.reduce(_ merge _)
        }
        .sortBy(_.key)

    log.trace("Ordered and grouped endpoints:")
    mergedRoutes.foreach { r =>
      log.info(
        s"""/${r.key.mkString("/")}: ${r.methodMap.keys.map { _.name }.mkString(", ")}"""
      )
    }

    val compiledRoutes: List[PathMatcher[Response[F]]] =
      mergedRoutes.map(_.compile)

    val reducedRoutes: PathMatcher[Response[F]] = compiledRoutes.reduce(_ orElse _)

    val PathMapping = Plan[F]().PathMapping

    val service: HttpRoutes[F] = HttpRoutes.of[F](
      PathMapping(reducedRoutes)
    )

    service
  }
}