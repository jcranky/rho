package org.http4s
package rho
package bits

import cats.Applicative
import cats.effect.IO
import fs2.Chunk
import org.http4s.rho.io._
import org.http4s.{Status => S}
import org.specs2.mutable.Specification
import shapeless.HList

import scala.reflect.runtime.universe._

class ResultMatcherSpec extends Specification {

  class TRhoService[F[_]] extends bits.MethodAliases {
    var statuses: Set[(Status, Type)] = Set.empty

    implicit final protected def compileSrvc: CompileService[F, RhoRoute.Tpe[F]] = {
      new CompileService[F, RhoRoute.Tpe[F]] {
        override def compile[T <: HList](route: RhoRoute[F, T]): RhoRoute.Tpe[F] = {
          statuses = route.resultInfo.collect { case StatusAndType(s, t) => (s, t) }
          route
        }
      }
    }
  }

  "ResponseGenerator" should {
    "Match a single result type" in {
      val srvc = new TRhoService[IO] {
        PUT / "foo" |>> { () => Ok("updated").unsafeRunSync() }
      }

      srvc.statuses.map(_._1) should_== Set(S.Ok)
    }

    "Match two results with different status with different result type" in {
      val srvc = new TRhoService[IO] {
        PUT / "foo" |>> { () =>
          val a = 0
          a match {
            case 0 => NotFound(s"Not found")
            case 1 => Ok("Hello world".getBytes())
          }
        }
      }

      srvc.statuses.map(_._1) should_== Set(S.NotFound, S.Ok)
      srvc.statuses.collect{ case (S.NotFound, t) => t }.head =:= weakTypeOf[String] must_== true
      srvc.statuses.collect{ case (S.Ok, t) => t }.head =:= weakTypeOf[Array[Byte]] must_== true
    }

    "Match two results with same stat different result type" in {
      val srvc = new TRhoService[IO] {
        PUT / "foo" |>> { () =>
          val a = 0
          a match {
            case 0 => Ok(s"Not found")
            case 1 => Ok("Hello world".getBytes())
          }
        }
      }

      srvc.statuses.map(_._1) should_== Set(S.Ok)
    }

    "Match an empty result type" in {
      val srvc = new TRhoService[IO] {
        PUT / "foo" |>> { () => NoContent.apply }
      }

      srvc.statuses.map(_._1) should_== Set(S.NoContent)
      srvc.statuses.head._2 =:= weakTypeOf[org.http4s.rho.bits.ResponseGenerator.EmptyRe]
    }

    "Match three results with different status but same result type" in {
      val srvc = new TRhoService[IO] {
        PUT / "foo" |>> { () =>
          val a = 0
          a match {
            case 0 => NotFound(s"Not found")
            case 1 => Ok("updated")
            case 2 => Accepted("it was accepted")
          }
        }
      }

      srvc.statuses.map(_._1) should_== Set(S.NotFound, S.Ok, S.Accepted)
    }

    "Match four results with different status but same result type" in {
      val srvc = new TRhoService[IO] {
        PUT / "foo" |>> { () =>
          val a = 0
          a match {
            case 0 => NotFound(s"Not found")
            case 1 => Ok("updated")
            case 2 => Accepted("it was accepted")
            case 4 => Created("it was created")
          }
        }
      }

      srvc.statuses.map(_._1) should_== Set(S.NotFound, S.Ok, S.Accepted, S.Created)
    }

    "Match results with locally defined types" in {

      case class ModelA(name: String, color: Int)
      case class ModelB(name: String, id: Long)

      implicit def w1[F[_]: Applicative]: EntityEncoder[F, ModelA] =
        EntityEncoder.simple[F, ModelA]()(_ => Chunk.bytes("A".getBytes))

      implicit def w2[F[_]: Applicative]: EntityEncoder[F, ModelB] =
        EntityEncoder.simple[F, ModelB]()(_ => Chunk.bytes("B".getBytes))

      val srvc = new TRhoService[IO] {
        GET / "foo" |>> { () =>
          if (true) Ok(ModelA("test ok", 1))
          else NotFound(ModelB("test not found", 234))
        }
      }

      srvc.statuses.map(_._1) should_== Set(S.Ok, S.NotFound)

      // the type equality for locally defined types is a bit "murkey" so we use the String name
      srvc.statuses.collect{ case (S.Ok, t) => t }.head.toString must_== "ModelA"
      srvc.statuses.collect{ case (S.NotFound, t) => t }.head.toString must_== "ModelB"
    }

    "Match complex models as well as simple ones" in {
      import Foo._

      val srvc = new TRhoService[IO] {
        GET / "foo" |>> { () =>
          if (true) Ok(FooA("test ok", 1))
          else NotFound(FooB("test not found", 234))
        }
      }

      srvc.statuses.map(_._1) should_== Set(S.Ok, S.NotFound)
      srvc.statuses.collect{ case (S.Ok, t) => t }.head =:= weakTypeOf[FooA] must_== true
      srvc.statuses.collect{ case (S.NotFound, t) => t }.head =:= weakTypeOf[FooB] must_== true
    }
  }
}

object Foo {
  case class FooA(name: String, color: Int)
  case class FooB(name: String, id: Long)

  implicit def w1[F[_]: Applicative]: EntityEncoder[F, FooA] =
    EntityEncoder.simple[F, FooA]()(_ => Chunk.bytes("A".getBytes))

  implicit def w2[F[_]: Applicative]: EntityEncoder[F, FooB] =
    EntityEncoder.simple[F, FooB]()(_ => Chunk.bytes("B".getBytes))
}
