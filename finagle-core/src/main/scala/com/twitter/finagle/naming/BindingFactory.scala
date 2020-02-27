package com.twitter.finagle.naming

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.factory.ServiceFactoryCache
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.finagle.loadbalancer.aperture.EagerConnections
import com.twitter.finagle.param
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.{CachedHashCode, Showable}
import com.twitter.logging.Logger
import com.twitter.util._

/**
 * A factory that routes to the local binding of the passed-in
 * [[com.twitter.finagle.Path Path]]. It calls `newFactory` to mint a
 * new [[com.twitter.finagle.ServiceFactory ServiceFactory]] for novel
 * name evaluations.
 *
 * A three-level caching scheme is employed for efficiency:
 *
 * First, the [[ServiceFactory]] for a [[Path]] is cached by the local
 * [[com.twitter.finagle.Dtab Dtab]]. This permits sharing in the
 * common case that no local [[Dtab]] is given. (It also papers over the
 * mutability of [[Dtab.base]].)
 *
 * Second, the [[ServiceFactory]] for a [[Path]] (relative to a
 * [[Dtab]]) is cached by the [[com.twitter.finagle.NameTree
 * NameTree]] it is bound to by that [[Dtab]]. Binding a path results
 * in an [[com.twitter.util.Activity Activity]], so this cache permits
 * sharing when the same tree is returned in different updates of the
 * [[Activity]]. (In particular it papers over nuisance updates of the
 * [[Activity]] where the value is unchanged.)
 *
 * Third, the ServiceFactory for a [[com.twitter.finagle.Name.Bound
 * Name.Bound]] appearing in a [[NameTree]] is cached by its
 * [[Name.Bound]]. This permits sharing when the same [[Name.Bound]]
 * appears in different [[NameTree]]s (or the same [[NameTree]]
 * resulting from different bindings of the [[Path]]).
 *
 * @bug This is far too complicated, though it seems necessary for
 * efficiency when namers are occasionally overridden.
 *
 * @bug 'status' has a funny definition.
 */
private[finagle] class BindingFactory[Req, Rep](
  path: Path,
  newFactory: Name.Bound => ServiceFactory[Req, Rep],
  timer: Timer,
  baseDtab: () => Dtab = BindingFactory.DefaultBaseDtab,
  statsReceiver: StatsReceiver = NullStatsReceiver,
  maxNameCacheSize: Int = 8,
  maxNameTreeCacheSize: Int = 8,
  maxNamerCacheSize: Int = 4,
  cacheTti: Duration = 10.minutes)
    extends ServiceFactory[Req, Rep] {

  import BindingFactory.Dtabs

  private[this] val nameCache =
    new ServiceFactoryCache[Name.Bound, Req, Rep](
      bound =>
        new ServiceFactoryProxy(newFactory(bound)) {
          private val boundShow = Showable.show(bound)
          override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
            Trace.recordBinary("namer.name", boundShow)
            super.apply(conn)
          }
      },
      timer,
      statsReceiver.scope("namecache"),
      maxNameCacheSize,
      cacheTti
    )

  private[this] val nameTreeCache =
    new ServiceFactoryCache[NameTree[Name.Bound], Req, Rep](
      tree =>
        new ServiceFactoryProxy(NameTreeFactory(path, tree, nameCache)) {
          private val treeShow = tree.show
          override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
            Trace.recordBinary("namer.tree", treeShow)
            super.apply(conn)
          }
      },
      timer,
      statsReceiver.scope("nametreecache"),
      maxNameTreeCacheSize,
      cacheTti
    )

  private[this] val dtabCache = {
    val newFactory: Dtabs => ServiceFactory[Req, Rep] = {
      case Dtabs(baseDtab, localDtab) =>
        val factory = new DynNameFactory(
          NameInterpreter.bind(baseDtab ++ localDtab, path),
          nameTreeCache,
          statsReceiver = statsReceiver
        )

        new ServiceFactoryProxy(factory) {
          private val pathShow = path.show
          private val baseDtabShow = baseDtab.show
          override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
            Trace.recordBinary("namer.path", pathShow)
            Trace.recordBinary("namer.dtab.base", baseDtabShow)
            // dtab.local is annotated on the client & server tracers.

            super.apply(conn).rescue {
              // we don't have the dtabs handy at the point we throw
              // the exception; fill them in on the way out
              case e: NoBrokersAvailableException =>
                Future.exception(new NoBrokersAvailableException(e.name, baseDtab, localDtab))
            }
          }
        }
    }

    new ServiceFactoryCache[Dtabs, Req, Rep](
      newFactory,
      timer,
      statsReceiver.scope("dtabcache"),
      maxNamerCacheSize,
      cacheTti
    )
  }

  def apply(conn: ClientConnection): Future[Service[Req, Rep]] =
    dtabCache(Dtabs(baseDtab(), Dtab.local), conn)

  def close(deadline: Time): Future[Unit] =
    Closable.sequence(dtabCache, nameTreeCache, nameCache).close(deadline)

  override def status: Status = dtabCache.status(Dtabs(baseDtab(), Dtab.local))
}

private[finagle] object BindingFactory {
  private val log = Logger.get()

  private case class Dtabs(base: Dtab, local: Dtab) extends CachedHashCode.ForClass {
    override protected def computeHashCode: Int = {
      var hash = 1
      hash = 31 * hash + base.hashCode
      hash = 31 * hash + local.hashCode
      hash
    }
  }

  val role = Stack.Role("Binding")

  /**
   * A class eligible for configuring a
   * [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.naming.BindingFactory]] with a destination
   * [[com.twitter.finagle.Name]] to bind.
   */
  case class Dest(dest: Name) {
    def mk(): (Dest, Stack.Param[Dest]) =
      (this, Dest.param)
  }
  object Dest {
    implicit val param = Stack.Param(Dest(Name.Path(Path.read("/$/fail"))))
  }

  private[finagle] val DefaultBaseDtab = new Function0[Dtab] {
    def apply(): Dtab = Dtab.base
    override def toString: String = "() => com.twitter.finagle.Dtab.base"
  }

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.naming.BindingFactory]] with a
   * [[com.twitter.finagle.Dtab]].
   */
  case class BaseDtab(baseDtab: () => Dtab) {
    def mk(): (BaseDtab, Stack.Param[BaseDtab]) =
      (this, BaseDtab.param)
  }
  object BaseDtab {
    implicit val param = Stack.Param(BaseDtab(DefaultBaseDtab))
  }

  /**
   * Base type for BindingFactory modules. Implementers may handle
   * bound residual paths in a protocol-specific way.
   *
   * The module creates a new `ServiceFactory` based on the module
   * above it for each distinct [[com.twitter.finagle.Name.Bound]]
   * resolved from `BindingFactory.Dest` (with caching of previously
   * seen `Name.Bound`s).
   */
  private[finagle] trait Module[Req, Rep] extends Stack.Module[ServiceFactory[Req, Rep]] {
    val role: Stack.Role = BindingFactory.role
    val description: String = "Bind destination names to endpoints"
    val parameters: Seq[Stack.Param[_]] = Seq(
      implicitly[Stack.Param[BaseDtab]],
      implicitly[Stack.Param[Dest]],
      implicitly[Stack.Param[LoadBalancerFactory.Param]],
      implicitly[Stack.Param[param.Label]],
      implicitly[Stack.Param[param.Stats]],
      implicitly[Stack.Param[param.Timer]]
    )

    /**
     * A request filter that is aware of the bound residual path.
     *
     * The returned filter is applied around the ServiceFactory built from the rest of the stack.
     */
    protected[this] def boundPathFilter(path: Path): Filter[Req, Rep, Req, Rep]

    def make(
      params: Stack.Params,
      next: Stack[ServiceFactory[Req, Rep]]
    ): Stack[ServiceFactory[Req, Rep]] = {
      val param.Label(label) = params[param.Label]
      val param.Stats(stats) = params[param.Stats]
      val param.Timer(timer) = params[param.Timer]
      val Dest(dest) = params[Dest]
      val LoadBalancerFactory.Param(balancer) = params[LoadBalancerFactory.Param]

      // we check if the stack has been explictly configured to detect misconfiguration
      // and the underlying balancer supports eagerly connecting to endpoints
      val eagerlyConnect: Boolean =
        if (params.contains[EagerConnections]) {
          if (balancer.supportsEagerConnections) params[EagerConnections].enabled
          else {
            // misconfiguration
            log.warning(
              "EagerlyConnect is only supported for the aperture load balancer. " +
                s"stack param found for ${label}.")
            false
          }
        } else {
          balancer.supportsEagerConnections && params[EagerConnections].enabled
        }

      def newStack(errorLabel: String, bound: Name.Bound) = {
        val client = next.make(
          params +
            // replace the possibly unbound Dest with the definitely bound
            // Dest because (1) it's needed by AddrMetadataExtraction and
            // (2) it seems disingenuous not to.
            Dest(bound) +
            LoadBalancerFactory.Dest(bound.addr) +
            LoadBalancerFactory.ErrorLabel(errorLabel) +
            // replace `EagerlyConnect` because (1) we need a way to disable eager connection
            // establishment for local dtab overrides. Request-level overrides can vary unpredicably,
            // resulting in wasteful connections and (2) the feature can be enabled for all clients
            // via the experimental global flag until this becomes default behavior
            EagerConnections(eagerlyConnect && Dtab.local.isEmpty)
        )

        boundPathFilter(bound.path) andThen client
      }

      val factory = dest match {
        case bound @ Name.Bound(addr) => newStack(label, bound)

        case Name.Path(path) =>
          val BaseDtab(baseDtab) = params[BaseDtab]
          new BindingFactory(path, newStack(path.show, _), timer, baseDtab, stats.scope("namer"))
      }

      // if enabled, eagerly bind the name in order to trigger the creation of the load balancer,
      // which in turn will eagerly create connections.
      if (eagerlyConnect) {
        factory().onSuccess(_.close())
      }

      Stack.leaf(role, factory)
    }
  }

  /**
   * Creates a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.naming.BindingFactory]].
   *
   * Ignores bound residual paths.
   */
  private[finagle] def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Module[Req, Rep] {
      private[this] val f = Filter.identity[Req, Rep]
      protected[this] def boundPathFilter(path: Path): Filter[Req, Rep, Req, Rep] = f
    }
}
