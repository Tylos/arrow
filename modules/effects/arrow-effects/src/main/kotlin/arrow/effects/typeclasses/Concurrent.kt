package arrow.effects.typeclasses

import arrow.Kind
import arrow.core.Either
import arrow.core.Tuple2
import arrow.core.left
import arrow.core.right
import kotlin.coroutines.CoroutineContext

/**
 * [Concurrent] defines behavior for [Async] data types that are cancelable and can be started concurrently.
 *
 * It allows abstracting over data types that can provide logic for cancellation, to be used in race
 * conditions in order to release resources early.
 **/
interface Concurrent<F> : Async<F> {

  /**
   * Start concurrent execution of the source suspended in the [F] context.
   *
   * @return [Fiber] that can be used to either join or cancel the running computation.
   */
  fun <A> Kind<F, A>.startF(ctx: CoroutineContext): Kind<F, Fiber<F, A>>

  /**
   * Race two tasks concurrently
   *
   * @return a containing both the winner's successful value and
   * the loser represented as a still-unfinished [Fiber].
   **/
  fun <A, B> racePair(ctx: CoroutineContext, lh: Kind<F, A>, rh: Kind<F, B>): Kind<F, Either<Tuple2<A, Fiber<F, B>>, Tuple2<Fiber<F, A>, B>>>

  /**
   * Race two tasks concurrently
   *
   * @return the first to finish, either in success or error. The loser of the race is canceled.
   **/
  fun <A, B> race(ctx: CoroutineContext, lh: Kind<F, A>, rh: Kind<F, B>): Kind<F, Either<A, B>> =
    racePair(ctx, lh, rh).flatMap {
      it.fold({ (a, b) ->
        b.cancel.map { a.left() }
      }, { (a, b) ->
        a.cancel.map { b.right() }
      })
    }

  /**
   * Creates a cancelable `F[A]` instance that executes an asynchronous process on evaluation.
   **/
  fun <A> cancelable(cb: ((Either<Throwable, A>) -> Unit) -> Kind<F, Unit>): Kind<F, A> =
    TODO("cancelable is derived from asyncF and from bracketCase," +
      "however it is expected to be overridden in instances for optimization purposes")

}
