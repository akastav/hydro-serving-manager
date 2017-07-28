package io.hydrosphere.serving.manager.service.envoy

import java.util.UUID

import io.hydrosphere.serving.manager.model.{ModelService, ModelServiceInstance}
import io.hydrosphere.serving.manager.service.RuntimeManagementService

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}


case class EnvoyClusterHost(
  url: String
)

case class EnvoyCluster(
  name: String,
  `type`: String,
  connect_timeout_ms: Long,
  lb_type: String,
  hosts: Seq[EnvoyClusterHost],
  service_name: String,
  features: String
)

case class EnvoyClusterConfig(
  clusters: Seq[EnvoyCluster]
)

case class EnvoyRoute(
  prefix: String,
  cluster: String
)

case class EnvoyRouteHost(
  name: String,
  domains: Seq[String],
  routes: Seq[EnvoyRoute]
)

case class EnvoyRouteConfig(
  virtual_hosts: Seq[EnvoyRouteHost]
)

case class EnvoyServiceTags(
  az: String,
  canary: String,
  load_balancing_weight: String
)

case class EnvoyServiceHost(
  ip_address: String,
  port: Int,
  tags: Seq[EnvoyServiceTags]
)

case class EnvoyServiceConfig(
  hosts: Seq[EnvoyServiceHost]
)

trait EnvoyManagementService {
  def clusters(serviceId: Long, containerId: String): Future[EnvoyClusterConfig]

  def services(serviceName: String): Future[EnvoyServiceConfig]

  def routes(configName: String, serviceId: Long, containerId: String): Future[EnvoyRouteConfig]
}

class EnvoyManagementServiceImpl(
  runtimeManagementService: RuntimeManagementService
)(implicit val ex: ExecutionContext) extends EnvoyManagementService {

  private def fetchGatewayIfNeeded(modelService: ModelService): Future[Seq[ModelServiceInstance]] = {
    if (modelService.serviceId >= 0) {
      runtimeManagementService.instancesForService(runtimeManagementService.GATEWAY_ID)
    } else {
      Future.successful(Seq())
    }
  }

  override def routes(configName: String, serviceId: Long, containerId: String): Future[EnvoyRouteConfig] = {
    runtimeManagementService.getService(serviceId).flatMap(servOp => {
      runtimeManagementService.allServices().flatMap(services => {
        val modelService = servOp.get
        fetchGatewayIfNeeded(modelService).map(gatewayServiceInstances => {

          val routeHosts = mutable.MutableList[EnvoyRouteHost]()
          services.filter(s => s.serviceId != serviceId)
            .foreach(s => {
              routeHosts += EnvoyRouteHost(
                name = s.serviceName,
                domains = Seq(s.serviceName),
                routes = Seq(EnvoyRoute("/", s.serviceName))
              )
            })
          gatewayServiceInstances.foreach(s => {
            routeHosts += EnvoyRouteHost(
              name = s.instanceId,
              domains = Seq(s.instanceId),
              routes = Seq(EnvoyRoute("/", UUID.nameUUIDFromBytes(s.instanceId.getBytes()).toString))
            )
          })

          routeHosts += EnvoyRouteHost(
            name = "all",
            domains = Seq("*"),
            routes = Seq(EnvoyRoute("/", modelService.serviceName))
          )

          EnvoyRouteConfig(
            virtual_hosts = routeHosts
          )
        })
      })
    })
  }

  override def services(serviceName: String): Future[EnvoyServiceConfig] =
    runtimeManagementService.instancesForService(serviceName)
      .map(seq => {
        EnvoyServiceConfig(
          hosts = seq.map(s =>
            EnvoyServiceHost(
              ip_address = s.host,
              port = s.sidecarPort,
              tags = Seq()
            )
          )
        )
      })

  override def clusters(serviceId: Long, containerId: String): Future[EnvoyClusterConfig] = {
    runtimeManagementService.getService(serviceId).flatMap(servOp => {
      runtimeManagementService.instancesForService(serviceId).flatMap(instancesSame => {
        runtimeManagementService.allServices().flatMap(services => {
          val modelService = servOp.get
          val containerInstance = instancesSame.find(p => p.instanceId == containerId)
          if (containerInstance.isEmpty) {
            Future.successful(EnvoyClusterConfig(Seq()))
          } else {
            fetchGatewayIfNeeded(modelService).map(gatewayServiceInstances => {
              val clustres = mutable.MutableList[EnvoyCluster]()

              services.foreach(s => {
                if (s.serviceId == modelService.serviceId) {
                  clustres += EnvoyCluster(
                    features = null,
                    connect_timeout_ms = 500,
                    lb_type = "round_robin",
                    service_name = null,
                    name = s.serviceName,
                    `type` = "static",
                    hosts = Seq(EnvoyClusterHost(s"tcp://127.0.0.1:${containerInstance.get.appPort}"))
                  )
                } else {
                  clustres += EnvoyCluster(
                    features = null,
                    connect_timeout_ms = 500,
                    lb_type = "round_robin",
                    service_name = s.serviceName,
                    name = s.serviceName,
                    `type` = "sds",
                    hosts = null
                  )
                }
              })
              gatewayServiceInstances.foreach(s => {
                clustres += EnvoyCluster(
                  features = null,
                  connect_timeout_ms = 500,
                  lb_type = "round_robin",
                  service_name = null,
                  name = UUID.nameUUIDFromBytes(s.instanceId.getBytes).toString,
                  `type` = "static",
                  hosts = getStaticHost(modelService, s, containerId)
                )
              })

              EnvoyClusterConfig(
                clusters = clustres
              )
            })
          }
        })
      })
    })
  }


  private def getStaticHost(runtime: ModelService, service: ModelServiceInstance, forNode: String): Seq[EnvoyClusterHost] = {
    val sameNode = service.instanceId == forNode
    val builder = new StringBuilder("tcp://")
    if (sameNode)
      builder.append("127.0.0.1")
    else
      builder.append(service.host)
    builder.append(":")
    if (sameNode)
      builder.append(service.appPort)
    else
      builder.append(service.sidecarPort)
    Seq(EnvoyClusterHost(builder.toString))
  }

}
