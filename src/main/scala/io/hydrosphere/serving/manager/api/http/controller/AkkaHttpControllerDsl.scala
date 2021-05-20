package io.hydrosphere.serving.manager.api.http.controller

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directives, ExceptionHandler, Route}
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import cats.effect.Async
import cats.effect.std.Dispatcher
import cats.syntax.flatMap._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport
import io.circe.Decoder
import io.circe.parser._
import io.circe.syntax._
import io.hydrosphere.serving.manager.domain.DomainError
import io.hydrosphere.serving.manager.util.AsyncUtil
import org.apache.logging.log4j.scala.Logging

import java.nio.file.{Files, Path}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait AkkaHttpControllerDsl extends ErrorAccumulatingCirceSupport with Directives with Logging {

  import AkkaHttpControllerDsl._

  final case class WithDispatcher[F[_]: Async]() {
    def apply[T](x: Dispatcher[F] => F[T]): F[T] = Dispatcher[F].use(x)
  }

  final def withDispatcher[F[_]: Async]: WithDispatcher[F] = WithDispatcher[F]()

  final def getFileWithMeta[F[_], T: Decoder, R: ToResponseMarshaller](
      callback: (Option[Path], Option[T]) => F[R]
  )(implicit
      F: Async[F],
      dispatcher: Dispatcher[F],
      mat: Materializer,
      ec: ExecutionContext
  ): Route =
    entity(as[Multipart.FormData]) { formdata =>
      val parts = formdata.parts.mapAsync(2) { part =>
        logger.debug(s"Got part ${part.name}")
        part.name match {
          case "payload" if part.filename.isDefined =>
            val filename = part.filename.get
            logger.debug(s"Part ${part.name} filename=$filename")
            val tempPath = Files.createTempFile("payload", filename)
            part.entity.dataBytes
              .runWith(FileIO.toPath(tempPath))
              .map(_ => UploadFile(tempPath))

          case "metadata" if part.filename.isEmpty =>
            part.toStrict(5.minutes).map { k =>
              val raw = k.entity.data.utf8String
              logger.debug(s"Part ${part.name} metadata=$raw")
              val res = parse(raw)
                .flatMap(_.as[T])
                .toTry
                .get
              UploadMeta(res)
            }

          case x =>
            logger.warn(s"Got unknown part name=${part.name} filename=${part.filename}")
            Future.successful(UploadUnknown(x, part.filename))
        }
      }

      val entitiesF: F[List[UploadE]] = AsyncUtil.futureAsync {
        parts.runFold(List.empty[UploadE]) {
          case (a, b) => a :+ b
        }
      }

      completeF {
        entitiesF.flatMap { entities =>
          val file     = entities.collectFirst { case UploadFile(x) => x }
          val metadata = entities.collectFirst { case UploadMeta(meta) => meta.asInstanceOf[T] }
          callback(file, metadata)
        }
      }
    }

  final def withF[F[_], T: ToResponseMarshaller](
      res: F[T]
  )(f: T => Route)(implicit dispatcher: Dispatcher[F]): Route =
    onComplete(dispatcher.unsafeToFuture(res)) {
      case Success(result) =>
        f(result)
      case Failure(err) => commonExceptionHandler(err)
    }

  final def completeF[F[_], T: ToResponseMarshaller](
      res: F[T]
  )(implicit dispatcher: Dispatcher[F]): Route =
    withF(res)(complete(_))

  final def commonExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case x: DomainError.NotFound =>
        complete(
          HttpResponse(
            status = StatusCodes.NotFound,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              x.asInstanceOf[DomainError].asJson.spaces2
            )
          )
        )
      case x: DomainError.InvalidRequest =>
        complete(
          HttpResponse(
            status = StatusCodes.BadRequest,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              x.asInstanceOf[DomainError].asJson.spaces2
            )
          )
        )
      case p: Throwable =>
        logger.error(p.toString)
        logger.error(p.getStackTrace.mkString("\n"))
        complete(
          HttpResponse(
            StatusCodes.InternalServerError,
            entity = Map(
              "error"   -> "InternalException",
              "message" -> Option(p.toString).getOrElse(s"Unknown error: $p")
            ).asJson.spaces2
          )
        )
    }
}

object AkkaHttpControllerDsl {

  sealed trait UploadE

  case class UploadFile(path: Path) extends UploadE

  case class UploadMeta[T](meta: T) extends UploadE

  case class UploadUnknown(key: String, filename: Option[String]) extends UploadE

}
