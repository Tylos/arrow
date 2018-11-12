//package arrow.effects
//
//import arrow.Kind
//import arrow.core.Either
//import arrow.core.Right
//import arrow.core.right
//import arrow.effects.internal.Promise
//import arrow.effects.internal.asyncIOContinuation
//import arrow.effects.typeclasses.*
//import java.util.concurrent.atomic.AtomicBoolean
//import kotlin.coroutines.experimental.Continuation
//import kotlin.coroutines.experimental.CoroutineContext
//import kotlin.coroutines.experimental.startCoroutine
//import kotlin.coroutines.experimental.suspendCoroutine
//
//fun <A> IOOf<A>.start(ctx: CoroutineContext): IO<Fiber<ForIO, A>> {
//  val start: Proc<Fiber<ForIO, A>> = { cb ->
//    val p = Promise.unsafe<ForIO, A>(IO.async())
//
//    val bool = AtomicBoolean(false)
//    val cancel = IO { bool.set(true); Unit }
//    val isCancelled = { bool.get() }
//
//    IORunLoop.start(source = this.fix().continueOn(ctx), isCancelled = isCancelled, cb = { either ->
//      either.fold({ p.error(it) }, { p.complete(it) })
//    })
//
//    cb(Right(Fiber(join = p.get, cancel = cancel)))
//  }
//
//  return IO.Async(start)
//}
//
//fun <A> IOOf<A>.start2(ctx: CoroutineContext): IO<Fiber<ForIO, A>> =
//  IO.Async(IO.concurrentEffect().start2(ctx, this))
//
//internal fun <A> ConcurrentEffect<ForIO>.start2(ctx: CoroutineContext, ioA: Kind<ForIO, A>): Proc<Fiber<ForIO, A>> = { cc ->
//
//  val p = Promise.unsafe<ForIO, A>(IO.async())
//  val cb: (Either<Throwable, A>) -> Unit = { cb ->
//    cb.fold({ p.error(it) }, { p.complete(it) })
//  }
//
//  val a: suspend () -> A = {
//    suspendCoroutine { ca: Continuation<A> ->
//      ioA.runAsyncCancellable { cb ->
//        cb.fold(
//          ifLeft = { invoke { ca.resumeWithException(it) } },
//          ifRight = { invoke { ca.resume(it) } }
//        )
//      }.let {
//        val cancel = it.fix().map { disp -> disp() }
//        cc(Fiber(p.get, cancel = cancel).right())
//        it.fix().unsafeRunAsync { _ -> }
//      }
//    }
//  }
//
//  a.startCoroutine(asyncIOContinuation(ctx, cb))
//}
