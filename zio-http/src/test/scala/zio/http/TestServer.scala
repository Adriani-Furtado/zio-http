package zio.http

import zio._
import zio.http.Server.ErrorCallback

/**
 * Enables tests that make calls against "localhost" with user-specified
 * Behavior/Responses.
 *
 * @param state
 *   State that can be consulted by our behavior PartialFunction
 * @param behavior
 *   Describes how the Server should behave during your test
 * @param driver
 *   The web driver that accepts our Server behavior
 * @param bindPort
 *   Port for HTTP interactions
 * @tparam State
 *   The type of state that will be mutated
 */
final case class TestServer[State](
  state: Ref[State],
  behavior: PartialFunction[(State, Request), (State, Response)],
  driver: Driver,
  bindPort: Int,
) extends Server {

  /**
   * Define 1-1 mappings between incoming Requests and outgoing Responses
   *
   * @param expectedRequest
   *   Request that will trigger the provided Response
   * @param response
   *   Response that the Server will send
   *
   * @example
   *   {{{ ZIO.serviceWithZIO[TestServer[Unit]]( _.addRequestResponse(
   *   Request.get(url = URL.root.setPort(port = ???)), Response(Status.Ok)
   *   ).install ) }}}
   *
   * @return
   *   The TestSever with new behavior.
   */
  def addRequestResponse(
    expectedRequest: Request,
    response: Response,
  ): TestServer[State] = {
    val handler: PartialFunction[(State, Request), (State, Response)] = {
      case (state, realRequest) if {
            // The way that the Client breaks apart and re-assembles the request prevents a straightforward
            //    expectedRequest == realRequest
            expectedRequest.url.relative == realRequest.url &&
            expectedRequest.method == realRequest.method &&
            expectedRequest.headers.toSet.forall(expectedHeader => realRequest.headers.toSet.contains(expectedHeader))
          } =>
        (state, response)
    }
    addHandlerState(handler)
  }

  /**
   * Define stateless behavior for the Server.
   *
   * @param pf
   *   The stateless behavior
   *
   * @return
   *   The TestSever with new behavior.
   *
   * @example
   *   {{{
   *   ZIO.serviceWithZIO[TestServer[Unit]](_.addHandler { case _: Request =>
   *       Response(Status.Ok)
   *   }
   *   }}}
   */
  def addHandler(pf: PartialFunction[Request, Response]): TestServer[State] = {
    val handler: PartialFunction[(State, Request), (State, Response)] = {
      case (state, request) if pf.isDefinedAt(request) => (state, pf(request))
    }
    addHandlerState(handler)
  }

  /**
   * Define stateful behavior for Server
   * @param pf
   *   Stateful behavior
   * @return
   *   The TestSever with new behavior.
   *
   * @example
   *   {{{
   * ZIO.serviceWithZIO[TestServer[Int]](_.addHandlerState { case (state, _: Request) =>
   *   if (state > 0)
   *     (state + 1, Response(Status.InternalServerError))
   *   else
   *     (state + 1, Response(Status.Ok))
   * }
   *   }}}
   */
  def addHandlerState(
    pf: PartialFunction[(State, Request), (State, Response)],
  ): TestServer[State] =
    copy(behavior = behavior.orElse(pf))

  def install(implicit
    trace: zio.Trace,
  ): UIO[Unit] =
    driver.addApp(
      Http.fromFunctionZIO((request: Request) => state.modify(state1 => behavior((state1, request)).swap)),
    )

  override def install[R](httpApp: HttpApp[R, Throwable], errorCallback: Option[ErrorCallback])(implicit
    trace: zio.Trace,
  ): URIO[R, Unit] =
    ZIO.environment[R].flatMap { env =>
      driver.addApp(
        if (env == ZEnvironment.empty) httpApp.asInstanceOf[HttpApp[Any, Throwable]]
        else httpApp.provideEnvironment(env),
      ) *> {
        val func =
          (request: Request) => state.modify(state1 => behavior((state1, request)).swap)

        val app: HttpApp[Any, Nothing] = Http.fromFunctionZIO(func)
        driver.addApp(app)
      }

    } *> setErrorCallback(errorCallback)

  private def setErrorCallback(errorCallback: Option[ErrorCallback]): UIO[Unit] =
    driver
      .setErrorCallback(errorCallback)
      .unless(errorCallback.isEmpty)
      .map(_.getOrElse(()))

  override def port: Int = bindPort
}

object TestServer {

  val make: ZIO[Driver with Scope, Throwable, TestServer[Unit]] =
    make(())

  def make[State](initial: State): ZIO[Driver with Scope, Throwable, TestServer[State]] =
    for {
      driver <- ZIO.service[Driver]
      port   <- driver.start
      state  <- Ref.make(initial)
    } yield TestServer(state, empty, driver, port)

  // Ensures that we blow up quickly if we execute a test against a TestServer with no behavior defined.
  private def empty[State]: PartialFunction[(State, Request), (State, Response)] = {
    case _ if false => ???
  }
}