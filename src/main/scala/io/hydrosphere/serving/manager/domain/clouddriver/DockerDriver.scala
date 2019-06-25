package io.hydrosphere.serving.manager.domain.clouddriver

import cats._
import cats.data.OptionT
import cats.implicits._
import com.spotify.docker.client.DockerClient.{ListContainersParam, RemoveContainerParam}
import com.spotify.docker.client.messages._
import io.hydrosphere.serving.manager.config.CloudDriverConfiguration
import io.hydrosphere.serving.manager.domain.clouddriver.DockerDriver.Internals.ContainerState
import io.hydrosphere.serving.manager.domain.host_selector.HostSelector
import io.hydrosphere.serving.manager.domain.image.DockerImage
import io.hydrosphere.serving.manager.infrastructure.docker.DockerdClient

import scala.collection.JavaConverters._
import scala.util.Try

class DockerDriver[F[_]](
  client: DockerdClient[F],
  config: CloudDriverConfiguration.Docker)(
  implicit F: MonadError[F, Throwable]
) extends CloudDriver[F] {

  import DockerDriver._

  override def instances: F[List[CloudInstance]] = {
    client.listAllContainers.map(all => {
      all.map(containerToInstance).collect({ case Some(v) => v })
    })
  }

  private def containerOf(name: String): F[Option[Container]] = {
    val query = List(
      ListContainersParam.allContainers(),
      ListContainersParam.withLabel(CloudDriver.Labels.ServiceName, name)
    )
    client.listContainers(query).map(_.headOption)
  }

  override def instance(name: String): F[Option[CloudInstance]] =
    containerOf(name).map(_.flatMap(containerToInstance))

  override def run(
    name: String,
    modelVersionId: Long,
    image: DockerImage,
    hostSelector: Option[HostSelector] = None
  ): F[CloudInstance] = {
    val container = Internals.mkContainerConfig(name, modelVersionId, image, config)
    for {
      creation <- client.createContainer(container, None)
      _ <- client.runContainer(creation.id())
      maybeOut <- instance(name)
      out <- maybeOut match {
        case Some(v) => F.pure(v)
        case None =>
          val warnings = Option(creation.warnings()) match {
            case Some(l) => l.asScala.mkString("\n")
            case None => ""
          }
          val msg = s"Running docker container for $name (${creation.id()}) failed. Warnings: \n $warnings"
          F.raiseError[CloudInstance](new RuntimeException(msg))
      }
    } yield out
  }

  override def remove(name: String): F[Unit] = {
    for {
      maybeC <- containerOf(name)
      _ <- maybeC match {
        case Some(c) =>
          val params = List(
            RemoveContainerParam.forceKill(true),
            RemoveContainerParam.removeVolumes(true),
          )
          client.removeContainer(c.id, params)
        case None => F.raiseError[Unit](new Exception(s"Could not find container for $name"))
      }
    } yield ()
  }

  private def containerToInstance(c: Container): Option[CloudInstance] = {
    val labels = c.labels().asScala

    val mName = labels.get(CloudDriver.Labels.ServiceName)
    val mMvId = labels.get(CloudDriver.Labels.ModelVersionId).flatMap(i => Try(i.toLong).toOption)

    (mName, mMvId).mapN { (name, mvId) =>
      c.state() match {
        case ContainerState.Running(_) =>
          val host = Internals.extractIpAddress(c.networkSettings(), config.networkName)
          val status = CloudInstance.Status.Running(host, DefaultConstants.DEFAULT_APP_PORT)
          CloudInstance(mvId, name, status)
        case ContainerState.Created(_) =>
          CloudInstance(mvId, name, CloudInstance.Status.Starting)
        case ContainerState.Restarting(_) =>
          CloudInstance(mvId, name, CloudInstance.Status.Starting)
        case _ =>
          CloudInstance(mvId, name, CloudInstance.Status.Stopped)
      }
    }
  }

  override def getByVersionId(modelVersionId: Long): F[Option[CloudInstance]] = {
    val query = List(
      ListContainersParam.allContainers(),
      ListContainersParam.withLabel(CloudDriver.Labels.ModelVersionId, modelVersionId.toString)
    )
    val r = for {
      cont <- OptionT(client.listContainers(query).map(_.headOption))
      parsed <- OptionT.fromOption[F](containerToInstance(cont))
    } yield parsed
    r.value
  }
}

object DockerDriver {
  
  object Internals {

    sealed trait ContainerState
    object ContainerState {
      final case object Created extends ContainerState {
        def unapply(arg: String): Option[Created.type] = {
          if (arg == "created") {
            Some(this)
          } else {
            None
          }
        }
      }

      final case object Restarting extends ContainerState {
        def unapply(arg: String): Option[Restarting.type] = {
          if (arg == "restarting") {
            Some(this)
          } else {
            None
          }
        }
      }

      final case object Running extends ContainerState {
        def unapply(arg: String): Option[Running.type] = {
          if (arg == "running") {
            Some(this)
          } else {
            None
          }
        }
      }

      final case object Paused extends ContainerState {
        def unapply(arg: String): Option[Paused.type] = {
          if (arg == "paused") {
            Some(this)
          } else {
            None
          }
        }
      }

      final case object Exited extends ContainerState {
        def unapply(arg: String): Option[Exited.type] = {
          if (arg == "exited") {
            Some(this)
          } else {
            None
          }
        }
      }

      final case object Dead extends ContainerState {
        def unapply(arg: String): Option[Dead.type] = {
          if (arg == "dead") {
            Some(this)
          } else {
            None
          }
        }
      }

    }

    def mkContainerConfig(
      name: String,
      modelVersionId: Long,
      image: DockerImage,
      dockerConf: CloudDriverConfiguration.Docker
    ): ContainerConfig = {
      val hostConfig = {
        val builder = HostConfig.builder().networkMode(dockerConf.networkName)
        val withLogs = dockerConf.loggingConfiguration match {
          case Some(c) => builder.logConfig(LogConfig.create(c.driver, c.params.asJava))
          case None => builder
        }
        withLogs.build()
      }
  
      val labels = Map(
        CloudDriver.Labels.ServiceName -> name,
        CloudDriver.Labels.ModelVersionId -> modelVersionId.toString
      )
      val envMap = Map(
        DefaultConstants.ENV_MODEL_DIR -> DefaultConstants.DEFAULT_MODEL_DIR.toString,
        DefaultConstants.ENV_APP_PORT -> DefaultConstants.DEFAULT_APP_PORT.toString
      )
  
      val envs = envMap.map({ case (k, v) => s"$k=$v"}).toList.asJava
  
      ContainerConfig.builder()
        .image(image.fullName)
        .exposedPorts(DefaultConstants.DEFAULT_APP_PORT.toString)
        .labels(labels.asJava)
        .hostConfig(hostConfig)
        .env(envs)
        .build()
    }
  
    def extractIpAddress(settings: NetworkSettings, networkName: String): String = {
      val byNetworkName = Option(settings.networks().get(networkName)).map(_.ipAddress())
      byNetworkName.getOrElse(settings.ipAddress())
    }
  }
}
