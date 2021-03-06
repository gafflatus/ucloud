package io.github.tobetwo.ucloud.rest

import java.io.File
import java.net.URLDecoder
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.http.scaladsl.model.{HttpEntity, _}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.github.tobetwo.implicits._
import io.github.tobetwo.ucloud.ClientId
import io.github.tobetwo.ucloud.actor.ServerActor
import io.github.tobetwo.ucloud.util.FileUtil

import scala.concurrent.duration._

object WebServer {
  implicit val timeout = Timeout(5 seconds)

  def start(server: ServerActor): Unit = {
    implicit val system = ActorSystem("api-server")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    def response(resp: String) = complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, resp))

    val settings = CorsSettings.defaultSettings.copy(
      allowedOrigins = HttpOriginRange.* // * refers to all
    )
    val route =
      (cors(settings) & get) {
        path("listClients") {
          response(server listClients)
        } ~
          pathPrefix("listJobs" / LongNumber) {
            id => response(server listJobs ClientId(id))
          } ~
          pathPrefix("runJob" / LongNumber / LongNumber) { (client, device) =>
            parameter("args") { args =>
              response(server.runJob(client, device, URLDecoder.decode(args).fromJson.children.map(_.values.toString): _*))
            }
          } ~
          pathPrefix("file" / Remaining) { path =>
            val filePath = server.conf.LOCAL_DIR + "/" + path
            if (new File(filePath).exists) withoutSizeLimit {
              val stream = FileUtil stream filePath
              complete(HttpEntity(ContentTypes.`application/octet-stream`, stream))
            } else complete(HttpResponse(StatusCodes.NotFound))
          }
      } ~
        (cors(settings) & post) {
          pathPrefix("file" / Remaining) { path =>
            withoutSizeLimit {
              extractDataBytes { bytes =>
                val future = bytes.runWith(FileIO toPath Paths.get(server.conf.LOCAL_DIR + "/" + path))
                onComplete(future) { _ =>
                  complete("success")
                }
              }
            }
          }
        }

    Http().bindAndHandle(route, server.conf.REST_HOST, server.conf.REST_PORT.toInt)
  }
}
