package com.winticket.server

import akka.actor.ActorSystem
import akka.event.slf4j.Logger
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.ConfigFactory

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Rest client dispatcher using an Akka http "host level" pooled connection to make the requests
 * Doc http://doc.akka.io/docs/akka-http/current/scala/http/client-side/host-level.html
 * @param address The target server's address
 * @param port The target server's port
 * @param poolSettings Settings for this particular connection pool - override defaults in application.conf under
 * @param system An actor system in which to execute the requests
 * @param materializer A flow materialiser
 */
case class RestClient(address: String, port: Int, poolSettings: ConnectionPoolSettings)(implicit val system: ActorSystem, implicit val materializer: ActorMaterializer) {

  val logger = Logger(this.getClass.getName)

  import system.dispatcher
  private val pool = Http().cachedHostConnectionPool[Int](address, port, poolSettings)

  logger.info("Loaded from config akka.http.client.host-connection-pool.max-connections: " + ConfigFactory.load().getInt("akka.http.client.host-connection-pool.max-connections"))
  logger.info("Loaded from config akka.http.host-connection-pool.max-connections: " + ConfigFactory.load().getInt("akka.http.host-connection-pool.max-connections"))

  /**
   * Execute a single request using the connection pool.
   * @param req An HttpRequest
   * @return The response
   */
  def exec(req: HttpRequest): Future[HttpResponse] = {
    Source.single(req -> 1)
      .via(pool)
      .runWith(Sink.head).flatMap {
        case (Success(r: HttpResponse), _) => Future.successful(r)
        case (Failure(f), _)               => Future.failed(f)
      }
  }

  /**
   * Take some sequence of requests and pipeline them through the connection pool.
   * Return whatever responses we get as a flattened sequence with the answers in the same
   * order as the original sequence. Zipping the request and response lists will result
   * in tuples of corresponding requests and responses
   * @param requests A list of requests that should be simultaneously issued to the pool
   * @return The responses in the same order as they were submitted
   */
  def execFlatten(requests: Iterable[HttpRequest]): Future[Iterable[HttpResponse]] = {
    Source(requests.zipWithIndex.toMap)
      .via(pool)
      .runFold(SortedMap[Int, Future[HttpResponse]]()) {
        case (m, (Success(r), idx)) ⇒ m + (idx → Future.successful(r))
        case (m, (Failure(e), idx)) ⇒ m + (idx → Future.failed(e))
      }.flatMap(r ⇒ Future.sequence(r.values))
  }

  /**
   * Take some sequence of requests and pipeline them through the connection pool.
   * Return whatever responses we get as a sequence of futures that will be ordered
   * in such a way that zipping the request and response lists will result
   * in tuples of corresponding requests and responses.
   * @param requests A list of requests that should be simultaneously issued to the pool
   * @return The Future responses in the same order as they were submitted
   */
  def exec(requests: Iterable[HttpRequest]): Future[Iterable[Future[HttpResponse]]] = {
    Source(requests.zipWithIndex.toMap)
      .via(pool)
      .runFold(SortedMap[Int, Future[HttpResponse]]()) {
        case (m, (Success(r), idx)) ⇒ m + (idx → Future.successful(r))
        case (m, (Failure(e), idx)) ⇒ m + (idx → Future.failed(e))
      }.map(r ⇒ r.values)
  }
}
