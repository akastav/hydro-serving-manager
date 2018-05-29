package io.hydrosphere.serving.manager.grpc.manager

import io.hydrosphere.serving.grpc.Headers
import io.hydrosphere.serving.manager.ManagerServices
import io.hydrosphere.serving.manager.service.application.RequestTracingInfo
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc.PredictionService
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.{ExecutionContext, Future}

class ManagerGrpcApi(
  managerServices: ManagerServices,
  grpcClient: PredictionServiceGrpc.PredictionServiceStub
)(implicit ec: ExecutionContext) extends PredictionService with Logging {

  override def predict(request: PredictRequest): Future[PredictResponse] = {
    request.modelSpec match {
      case Some(_) =>
        val requestId = Option(Headers.XRequestId.contextKey.get())
        managerServices.applicationManagementService.serveGrpcApplication(
          request,
          requestId.map(r => RequestTracingInfo(
            xRequestId = r,
            xB3requestId = Option(Headers.XB3TraceId.contextKey.get()),
            xB3SpanId = Option(Headers.XB3SpanId.contextKey.get())
          ))).flatMap {
          case Left(err) => Future.failed(new RuntimeException(err.toString))
          case Right(value) => Future.successful(value)
        }

      case None => Future.failed(new IllegalArgumentException("ModelSpec is not defined"))
    }
  }
}
