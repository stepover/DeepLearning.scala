/*
 * stateless-future
 * Copyright 2014 深圳岂凡网络有限公司 (Shenzhen QiFun Network Corp., LTD)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qifun.statelessFuture

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.Exception.Catcher
import scala.annotation.tailrec
import scala.util.control.TailCalls._
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.Left
import scala.Right

object Promise {
  def apply[AwaitResult] = new Promise[AwaitResult]

  private implicit class Scala210TailRec[A](underlying: TailRec[A]) {
    final def flatMap[B](f: A => TailRec[B]): TailRec[B] = {
      tailcall(f(underlying.result))
    }
  }

}

/**
 * A [[Future.Stateful]] that will be completed when another [[Future]] being completed.
 * @param state The internal state that should never be accessed by other modules.
 */
final class Promise[AwaitResult] private (val state: AtomicReference[Either[List[(AwaitResult => TailRec[Unit], Catcher[TailRec[Unit]])], Try[AwaitResult]]] = new AtomicReference[Either[List[(AwaitResult => TailRec[Unit], Catcher[TailRec[Unit]])], Try[AwaitResult]]](Left(Nil))) extends AnyVal with Future.Stateful[AwaitResult] { // TODO: 把List和Tuple2合并成一个对象，以减少内存占用

  // 为了能在Scala 2.10中编译通过
  import Promise.Scala210TailRec

  private def dispatch(handlers: List[(AwaitResult => TailRec[Unit], Catcher[TailRec[Unit]])], value: Try[AwaitResult]): TailRec[Unit] = {
    handlers match {
      case Nil => done(())
      case (body, catcher) :: tail => {
        (value match {
          case Success(a) => {
            body(a)
          }
          case Failure(e) => {
            if (catcher.isDefinedAt(e)) {
              catcher(e)
            } else {
              throw e
            }
          }
        }).flatMap { _ =>
          dispatch(tail, value)
        }
      }
    }
  }

  override final def value = state.get.right.toOption

  // @tailrec // Comment this because of https://issues.scala-lang.org/browse/SI-6574
  private def complete(value: Try[AwaitResult]): TailRec[Unit] = {
    state.get match {
      case oldState @ Left(handlers) => {
        if (state.compareAndSet(oldState, Right(value))) {
          tailcall(dispatch(handlers, value))
        } else {
          complete(value)
        }
      }
      case Right(origin) => {
        throw new IllegalStateException("Cannot complete a Promise twice!")
      }
    }
  }

  /**
   * Starts a waiting operation that will be completed when `other` being completed.
   * @throws java.lang.IllegalStateException Passed to `catcher` when this [[Promise]] being completed more once. 
   */
  final def completeWith[OriginalAwaitResult](other: Future[OriginalAwaitResult])(implicit view: OriginalAwaitResult => AwaitResult): TailRec[Unit] = {
    other.onComplete { b =>
      val value = Success(view(b))
      tailcall(complete(value))
    } {
      case e: Throwable => {
        val value = Failure(e)
        tailcall(complete(value))
      }
    }
  }

  // @tailrec // Comment this because of https://issues.scala-lang.org/browse/SI-6574
  private def tryComplete(value: Try[AwaitResult]): TailRec[Unit] = {
    state.get match {
      case oldState @ Left(handlers) => {
        if (state.compareAndSet(oldState, Right(value))) {
          tailcall(dispatch(handlers, value))
        } else {
          complete(value)
        }
      }
      case Right(origin) => {
        done(())
      }
    }
  }

  /**
   * Starts a waiting operation that will be completed when `other` being completed.
   * Unlike [[#completeWith]], no exception will be created when this [[Promise]] being completed more once.
   */
  final def tryCompleteWith[OriginalAwaitResult](other: Future[OriginalAwaitResult])(implicit view: OriginalAwaitResult => AwaitResult): TailRec[Unit] = {
    other.onComplete { b =>
      val value = Success(view(b))
      tailcall(tryComplete(value))
    } {
      case e: Throwable => {
        val value = Failure(e)
        tailcall(tryComplete(value))
      }
    }
  }

  // @tailrec // Comment this because of https://issues.scala-lang.org/browse/SI-6574
  override final def onComplete(body: AwaitResult => TailRec[Unit])(implicit catcher: Catcher[TailRec[Unit]]): TailRec[Unit] = {
    state.get match {
      case Right(value) => {
        value match {
          case Success(a) => {
            body(a)
          }
          case Failure(e) => {
            if (catcher.isDefinedAt(e)) {
              catcher(e)
            } else {
              throw e
            }
          }
        }
      }
      case oldState @ Left(tail) => {
        if (state.compareAndSet(oldState, Left((body, catcher) :: tail))) {
          done(())
        } else {
          onComplete(body)
        }
      }
    }
  }

}