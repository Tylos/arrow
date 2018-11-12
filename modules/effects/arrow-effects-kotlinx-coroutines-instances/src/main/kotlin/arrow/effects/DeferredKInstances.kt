package arrow.effects

import arrow.Kind
import arrow.core.*
import arrow.deprecation.ExtensionsDSLDeprecated
import arrow.effects.deferredk.applicative.applicative
import arrow.effects.deferredk.monad.flatMap
import arrow.effects.typeclasses.Async
import arrow.effects.typeclasses.Bracket
import arrow.effects.typeclasses.ConcurrentEffect
import arrow.effects.typeclasses.Disposable
import arrow.effects.typeclasses.Effect
import arrow.effects.typeclasses.ExitCase
import arrow.effects.typeclasses.MonadDefer
import arrow.effects.typeclasses.Proc
import arrow.extension
import arrow.typeclasses.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import me.eugeniomarletti.kotlin.metadata.shadow.utils.join
import arrow.typeclasses.Applicative
import arrow.typeclasses.ApplicativeError
import arrow.typeclasses.Functor
import arrow.typeclasses.Monad
import arrow.typeclasses.MonadError
import arrow.typeclasses.Traverse
import kotlin.coroutines.CoroutineContext
import arrow.effects.handleErrorWith as deferredHandleErrorWith
import arrow.effects.runAsync as deferredRunAsync

@extension
interface DeferredKFunctorInstance : Functor<ForDeferredK> {
  override fun <A, B> Kind<ForDeferredK, A>.map(f: (A) -> B): DeferredK<B> =
    fix().map(f)
}

@extension
interface DeferredKApplicativeInstance : Applicative<ForDeferredK> {
  override fun <A, B> Kind<ForDeferredK, A>.map(f: (A) -> B): DeferredK<B> =
    fix().map(f)

  override fun <A> just(a: A): DeferredK<A> =
    DeferredK.just(a)

  override fun <A, B> DeferredKOf<A>.ap(ff: DeferredKOf<(A) -> B>): DeferredK<B> =
    fix().ap(ff)
}

suspend fun <F, A> Kind<F, DeferredKOf<A>>.awaitAll(T: Traverse<F>): Kind<F, A> = T.run {
  this@awaitAll.sequence(DeferredK.applicative()).await()
}

@extension
interface DeferredKMonadInstance : Monad<ForDeferredK> {
  override fun <A, B> Kind<ForDeferredK, A>.flatMap(f: (A) -> Kind<ForDeferredK, B>): DeferredK<B> =
    fix().flatMap(f = f)

  override fun <A, B> Kind<ForDeferredK, A>.map(f: (A) -> B): DeferredK<B> =
    fix().map(f)

  override fun <A, B> tailRecM(a: A, f: (A) -> DeferredKOf<Either<A, B>>): DeferredK<B> =
    DeferredK.tailRecM(a, f)

  override fun <A, B> DeferredKOf<A>.ap(ff: DeferredKOf<(A) -> B>): DeferredK<B> =
    fix().ap(ff)

  override fun <A> just(a: A): DeferredK<A> =
    DeferredK.just(a)
}

@extension
interface DeferredKApplicativeErrorInstance : ApplicativeError<ForDeferredK, Throwable>, DeferredKApplicativeInstance {
  override fun <A> raiseError(e: Throwable): DeferredK<A> =
    DeferredK.raiseError(e)

  override fun <A> DeferredKOf<A>.handleErrorWith(f: (Throwable) -> DeferredKOf<A>): DeferredK<A> =
    deferredHandleErrorWith { f(it).fix() }
}

@extension
interface DeferredKMonadErrorInstance : MonadError<ForDeferredK, Throwable>, DeferredKMonadInstance {
  override fun <A> raiseError(e: Throwable): DeferredK<A> =
    DeferredK.raiseError(e)

  override fun <A> DeferredKOf<A>.handleErrorWith(f: (Throwable) -> DeferredKOf<A>): DeferredK<A> =
    deferredHandleErrorWith { f(it).fix() }
}

@extension
interface DeferredKBracketInstance : Bracket<ForDeferredK, Throwable>, DeferredKMonadErrorInstance {
  override fun <A, B> Kind<ForDeferredK, A>.bracketCase(
    use: (A) -> Kind<ForDeferredK, B>,
    release: (A, ExitCase<Throwable>) -> Kind<ForDeferredK, Unit>): DeferredK<B> =
    fix().bracketCase({ a -> use(a).fix() }, { a, e -> release(a, e).fix() })
}

@extension
interface DeferredKMonadDeferInstance : MonadDefer<ForDeferredK>, DeferredKBracketInstance {
  override fun <A> defer(fa: () -> DeferredKOf<A>): DeferredK<A> =
    DeferredK.defer(fa = fa)
}

@extension
interface DeferredKAsyncInstance : Async<ForDeferredK>, DeferredKMonadDeferInstance {
  override fun <A> async(fa: Proc<A>): DeferredK<A> =
    DeferredK.async(fa = fa)

  override fun <A> DeferredKOf<A>.continueOn(ctx: CoroutineContext): DeferredK<A> =
    fix().continueOn(ctx = ctx)

  override fun <A> invoke(f: () -> A): DeferredK<A> =
    DeferredK.invoke(f = f)

  override fun <A> invoke(ctx: CoroutineContext, f: () -> A): Kind<ForDeferredK, A> =
    DeferredK.invoke(ctx = ctx, f = f)
}

@extension
interface DeferredKEffectInstance : Effect<ForDeferredK>, DeferredKAsyncInstance {
  override fun <A> Kind<ForDeferredK, A>.runAsync(cb: (Either<Throwable, A>) -> DeferredKOf<Unit>): DeferredK<Unit> =
    fix().deferredRunAsync(cb = cb)
}

@extension
interface DeferredKConcurrentEffectInstance : ConcurrentEffect<ForDeferredK>, DeferredKEffectInstance {
  override fun <A> Kind<ForDeferredK, A>.runAsyncCancellable(cb: (Either<Throwable, A>) -> Kind<ForDeferredK, Unit>): Kind<ForDeferredK, Disposable> =
    fix().runAsyncCancellable(onCancel = OnCancel.ThrowCancellationException, cb = cb)
}

object DeferredKContext : DeferredKConcurrentEffectInstance

@Deprecated(ExtensionsDSLDeprecated)
infix fun <A> ForDeferredK.Companion.extensions(f: DeferredKContext.() -> A): A =
  f(DeferredKContext)

fun DeferredK.Companion.concurrent(): Concurrent<ForDeferredK> = object : Concurrent<ForDeferredK>, DeferredKAsyncInstance {

  override fun <A> Kind<ForDeferredK, A>.startF(): Kind<ForDeferredK, Fiber<ForDeferredK, A>> {
    val join = scope().asyncK(start = CoroutineStart.DEFAULT) { await() }
    val cancel = DeferredK(start = CoroutineStart.LAZY) { join.cancel() }
    return DeferredK.just(Fiber(join, cancel))
  }

  override fun <A, B> racePair(lh: Kind<ForDeferredK, A>, rh: Kind<ForDeferredK, B>): Kind<ForDeferredK, Either<Tuple2<A, Fiber<ForDeferredK, B>>, Tuple2<Fiber<ForDeferredK, A>, B>>> =
    lh.startF().flatMap { fiberA ->
      rh.startF().flatMap { fiberB ->
        DeferredK.async<Either<Tuple2<A, Fiber<ForDeferredK, B>>, Tuple2<Fiber<ForDeferredK, A>, B>>> { cb ->
          fiberA.join.fix().deferred.invokeOnCompletion { error ->
            error?.let { cb(it.left()) } ?: cb((fiberA.join.fix().deferred.getCompleted() toT fiberB).left().right())
          }
          fiberB.join.fix().deferred.invokeOnCompletion { error ->
            error?.let { cb(it.left()) } ?: cb((fiberA toT fiberB.join.fix().deferred.getCompleted()).right().right())
          }
        }
      }
    }

}


fun <F, A, B, C> Concurrent<F>.parMap(fa: Kind<F, A>, fb: Kind<F, B>, f: (A, B) -> C): Kind<F, C> =
  fa.startF().flatMap { (joinA, _) ->
    fb.startF().flatMap { (joinB, _) ->
      joinA.flatMap { a ->
        joinB.map { b ->
          f(a, b)
        }
      }
    }
  }

fun main(args: Array<String>) {
  val one =  GlobalScope.asyncK {
    delay(1000)
    println("Sleep 1000")
    1
  }

  val two = GlobalScope.asyncK {
    delay(100)
    println("Sleep 100")
    2
  }

  DeferredK.concurrent().race(one, two)
    .unsafeRunSync()
    .let(::println)


}